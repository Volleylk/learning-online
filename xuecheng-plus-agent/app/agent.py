"""Agent 调度核心模块（LangChain 1.x + LangGraph）。

- 有 Key：基于 langchain.agents.create_agent（LangGraph CompiledStateGraph）
  构建决策中枢，自行判断是否调用讲义检索 / 课程查询 / 偏好管理工具，
  直到生成最终回答；挂载 SummarizationMiddleware 自动压缩历史上下文。
  流式走 astream_events(version="v3") 事件协议（Pregel 实验特性，1.3.0+）。
- 无 Key（降级模式）：规则引擎决策工具调用，本地可完整跑通
  "闲聊直答 / 知识点走RAG / 找课出课程卡片" 三条链路。

对外统一返回 {"answer": str, "courses": [课程卡片], "sources": [讲义片段], "mode": ...}
"""
from __future__ import annotations

import json
import re
from typing import Any, AsyncIterator, Dict, List, Optional

from langchain.agents import create_agent
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage

from app.config import settings
from app.memory import SessionMemory, build_system_prompt, get_preferences_text
from app.rag import RAGService
from app.tools import (
    make_course_query_tool,
    make_lecture_search_tool,
    make_my_courses_tool,
    make_preference_tools,
    pref_handler,
    set_agent_user,
    set_my_courses_token,
    set_query_history,
)

# 找课意图关键词（降级模式判断用）
FIND_COURSE_RE = re.compile(r"(推荐|找课|选课|学什么|入门|进阶|课程|有哪些|适合.*学|报.*班)")
# 知识点/讲义意图关键词
KNOWLEDGE_RE = re.compile(r"(讲义|知识点|概念|是什么|怎么理解|解释|原理|区别|为什么|怎么做|步骤)")

# LangGraph 递归深度上限（约对应 0.3 时代 max_iterations=6 的循环轮数）
RECURSION_LIMIT = 30


def _display_name(profile: dict | None) -> str:
    """从用户画像中取展示名：昵称 > 姓名 > 用户名。"""
    if not profile:
        return ""
    return (
        profile.get("nickname")
        or profile.get("name")
        or profile.get("username")
        or ""
    ).strip()


def _tool_name_for_call_id(messages: List[BaseMessage], tool_call_id: str) -> str:
    """在消息序列中按 tool_call_id 反查工具名。"""
    for m in messages:
        if getattr(m, "type", None) == "ai":
            for tc in getattr(m, "tool_calls", None) or []:
                if tc.get("id") == tool_call_id:
                    return tc.get("name", "")
    return ""


def _aggregate_tool_output(name: str, out_text: str, courses: List[Dict], sources: List[Dict]) -> str:
    """解析工具返回 JSON，聚合课程卡片 / 讲义来源；返回给前端的 thinking 文案。"""
    if not isinstance(out_text, str):
        return ""
    try:
        parsed = json.loads(out_text)
    except Exception:
        return ""
    if not isinstance(parsed, dict) or not parsed.get("found"):
        return ""
    if name == "course_info_query" and isinstance(parsed.get("courses"), list):
        for c in parsed["courses"]:
            if not any(existing.get("id") == c.get("id") for existing in courses):
                courses.append(c)
        return f"找到 {len(courses)} 门相关课程…"
    if name == "course_lecture_search" and isinstance(parsed.get("items"), list):
        items = parsed["items"]
        sources[:] = [
            {
                "course_id": it.get("course_id"),
                "course_name": it.get("course_name"),
                "source_file": it.get("source_file"),
                "page_num": it.get("page", it.get("page_num")),
                "text": it.get("text", ""),
            }
            for it in items
        ]
        return f"检索到 {len(sources)} 条讲义片段…"
    if name in ("user_preference_update", "user_preference_read"):
        return "正在读取/更新你的学习偏好…"
    return ""


def _history_text(session_memory: SessionMemory) -> str:
    """取最近对话历史纯文本（查询改写/对话补全用；失败返回空串）。"""
    try:
        msgs = session_memory.load_variables().get("chat_history", [])
    except Exception:
        return ""
    return "\n".join(
        f"{m.type}: {m.content if isinstance(m.content, str) else str(m.content)}"
        for m in msgs[-4:]
    )


class AgentCore:
    def __init__(
        self,
        rag: RAGService,
        llm: BaseChatModel,
        preference_tools: Optional[List] = None,
    ):
        self.rag = rag
        self.llm = llm
        self._course_tool = make_course_query_tool()
        # 查询重写（含对话补全）仅真实百炼 Key 时启用；降级模型不参与改写
        self._lecture_tool = make_lecture_search_tool(rag, llm if settings.llm_enabled else None)
        self._pref_tools = preference_tools or make_preference_tools()
        self._agent = None
        if settings.llm_enabled:
            self._build_agent()

    # ------------------------------------------------------------------
    # 有 Key 路径：LangGraph（create_agent，LangChain 1.x 新入口）
    # ------------------------------------------------------------------
    def _build_agent(self):
        """构建 LangGraph agent（tools 固定注册；动态内容经输入消息注入）。

        - 系统提示不绑定在构建期：每请求动态系统提示作为输入消息首条 SystemMessage。
        - "我的课程" / 偏好工具通过 contextvar 读取请求级 user_id / JWT，
          无需每次请求重建 agent。
        """
        tools = [
            self._lecture_tool,
            self._course_tool,
            make_my_courses_tool(),
            *self._pref_tools,
        ]
        self._agent = create_agent(model=self.llm, tools=tools)

    def _input_messages(
        self, query: str, session_memory: SessionMemory, user_id: str, profile: dict | None
    ) -> List[BaseMessage]:
        system_prompt = build_system_prompt(user_id, _display_name(profile))
        history = session_memory.load_variables().get("chat_history", [])
        return [
            SystemMessage(content=system_prompt),
            *history,
            HumanMessage(content=query),
        ]

    def _run_agent(
        self, query: str, session_memory: SessionMemory, user_id: str, profile: dict | None = None
    ) -> Dict[str, Any]:
        assert self._agent is not None
        set_agent_user(user_id)
        set_my_courses_token("")  # 非流式入口不携带 JWT（与 0.3 行为一致）
        set_query_history(_history_text(session_memory))  # 讲义检索前对话补全/查询改写
        courses: List[Dict[str, Any]] = []
        sources: List[Dict[str, Any]] = []
        try:
            result = self._agent.invoke(
                {"messages": self._input_messages(query, session_memory, user_id, profile)},
                config={"recursion_limit": RECURSION_LIMIT},
            )
        except Exception as e:
            answer = f"抱歉，处理你的问题时出了点岔子：{e}"
            return {"answer": answer, "courses": courses, "sources": sources, "mode": "agent"}

        msgs: List[BaseMessage] = result.get("messages", [])
        # 最终回答 = 最后一条 AI 消息正文
        answer = ""
        for m in reversed(msgs):
            if getattr(m, "type", None) == "ai":
                content = m.content
                answer = content if isinstance(content, str) else str(content)
                break
        # 工具输出聚合：按 ToolMessage.tool_call_id 反查工具名
        for m in msgs:
            if getattr(m, "type", None) == "tool":
                name = _tool_name_for_call_id(msgs, getattr(m, "tool_call_id", ""))
                _aggregate_tool_output(name, m.content, courses, sources)
        return {"answer": answer, "courses": courses, "sources": sources, "mode": "agent"}

    # ------------------------------------------------------------------
    # 无 Key 路径：规则引擎降级
    # ------------------------------------------------------------------
    def _run_fallback(
        self, query: str, session_memory: SessionMemory, user_id: str, profile: dict | None = None
    ) -> Dict[str, Any]:
        courses: List[Dict[str, Any]] = []
        sources: List[Dict[str, Any]] = []
        knowledge_hit = bool(KNOWLEDGE_RE.search(query))
        course_hit = bool(FIND_COURSE_RE.search(query))

        if knowledge_hit and not course_hit:
            # 走 RAG 讲义检索
            results = self.rag.search(query)
            sources = results
            if results:
                context = "\n\n".join(
                    f"[{r['course_name']}·{r['source_file']}·第{r['page_num']}页]\n{r['text']}"
                    for r in results
                )
                answer = (
                    "[降级模式·讲义检索] 根据讲义知识库，回答你的问题：\n\n"
                    f"{context[:600]}\n\n"
                    f"（以上内容来自 {len(results)} 条讲义片段，可点击右侧卡片跳转对应课程）"
                )
            else:
                answer = "[降级模式] 讲义知识库中暂未检索到相关内容。"
        else:
            # 闲聊 / 找课：若含找课意图则查询课程
            if course_hit:
                tool_out = self._course_tool.func(query)
                try:
                    parsed = json.loads(tool_out)
                    if parsed.get("found"):
                        courses = parsed["courses"]
                except Exception:
                    pass
            prefs = get_preferences_text(user_id)
            if courses:
                names = "、".join(c["name"] for c in courses[:3])
                answer = (
                    f"[降级模式] 根据你的问题，为你推荐以下课程：{names}。\n"
                    "课程卡片已展示在右侧，点击即可跳转课程详情页。\n"
                    f"（你的学习偏好：{prefs}；配置 DASHSCOPE_API_KEY 后可获得个性化详细推荐）"
                )
            else:
                answer = (
                    "[降级模式·通用对话] 我已收到你的问题，但没有命中讲义检索或课程推荐。\n"
                    f"你的问题：{query}\n你的学习偏好：{prefs}\n"
                    "配置 DASHSCOPE_API_KEY 后，千问大模型将为你生成自然流畅的回答。"
                )
        return {"answer": answer, "courses": courses, "sources": sources, "mode": "fallback"}

    # ------------------------------------------------------------------
    # 对外统一入口
    # ------------------------------------------------------------------
    def run(
        self,
        query: str,
        user_id: str,
        session_memory: SessionMemory,
        profile: dict | None = None,
    ) -> Dict[str, Any]:
        if settings.llm_enabled:
            return self._run_agent(query, session_memory, user_id, profile)
        return self._run_fallback(query, session_memory, user_id, profile)

    # ------------------------------------------------------------------
    # 流式入口（SSE 事件序列）
    # 事件类型：thinking / answer(delta) / sources / courses / done
    # ------------------------------------------------------------------
    async def stream_run(
        self,
        query: str,
        user_id: str,
        session_memory: SessionMemory,
        token: str = "",
        profile: dict | None = None,
    ) -> AsyncIterator[Dict[str, Any]]:
        """token 为当前请求的原始 JWT（透传 Java 用户维接口）；profile 为用户画像（注入昵称）。"""
        set_agent_user(user_id)
        set_my_courses_token(token)
        set_query_history(_history_text(session_memory))  # 讲义检索前对话补全/查询改写
        if settings.llm_enabled:
            async for ev in self._stream_agent(query, user_id, session_memory, token, profile):
                yield ev
        else:
            for ev in self._stream_fallback(query, session_memory, user_id, profile):
                yield ev

    async def _stream_agent(
        self,
        query: str,
        user_id: str,
        session_memory: SessionMemory,
        token: str = "",
        profile: dict | None = None,
    ) -> AsyncIterator[Dict[str, Any]]:
        """有 Key 流式：LangGraph astream_events(version="v3")。

        v3 事件（Pregel 实验协议）：
        - messages: data=(事件dict, 元数据)；content-block-delta 的 text 增量 = 流式正文；
          content-block-finish 的 tool_call = 完整工具调用（工具由 LangGraph 自动执行）；
        - tools: tool-finished 携带 ToolMessage 输出 → 聚合课程卡片/讲义来源；
        - values: 每步状态快照（messages 列表），可作兜底。
        """
        assert self._agent is not None
        yield {"type": "thinking", "text": "正在理解问题…"}
        courses: List[Dict[str, Any]] = []
        sources: List[Dict[str, Any]] = []
        tool_names: Dict[str, str] = {}  # tool_call_id → 工具名
        try:
            stream = await self._agent.astream_events(
                {"messages": self._input_messages(query, session_memory, user_id, profile)},
                version="v3",
                config={"recursion_limit": RECURSION_LIMIT},
            )
            async for ev in stream:
                method = ev.get("method")
                data = (ev.get("params") or {}).get("data")
                if method == "messages" and isinstance(data, tuple):
                    evt = data[0]
                    e = evt.get("event")
                    if e == "content-block-delta":
                        delta = evt.get("delta") or {}
                        # 文本增量：delta.type == "text-delta"（工具轮是 block-delta，忽略）
                        if delta.get("type") == "text-delta":
                            text = delta.get("text", "")
                            if text:
                                yield {"type": "answer", "delta": text}
                    elif e == "content-block-finish":
                        content = evt.get("content") or {}
                        if content.get("type") == "tool_call":
                            cid = content.get("id", "")
                            name = content.get("name", "")
                            if cid and name:
                                tool_names[cid] = name
                            yield {"type": "thinking", "text": f"正在调用工具：{name}…"}
                elif method == "tools" and isinstance(data, dict):
                    if data.get("event") == "tool-finished":
                        cid = data.get("tool_call_id", "")
                        name = tool_names.get(cid, "")
                        out = data.get("output")
                        out_text = getattr(out, "content", "") if out is not None else ""
                        thinking_text = _aggregate_tool_output(name, out_text, courses, sources)
                        if thinking_text:
                            yield {"type": "thinking", "text": thinking_text}
        except Exception as e:
            msg = f"抱歉，处理你的问题时出了点岔子：{e}"
            yield {"type": "answer", "delta": msg}
        yield {"type": "done", "session_id": session_memory.session_id,
               "courses": courses, "sources": sources, "mode": "agent"}

    def _stream_fallback(
        self,
        query: str,
        user_id: str,
        session_memory: SessionMemory,
        profile: dict | None = None,
    ):
        """无 Key 流式：规则引擎一次算出 answer，按块模拟打字机输出。"""
        yield {"type": "thinking", "text": "正在分析问题…"}
        result = self._run_fallback(query, session_memory, user_id, profile)
        answer = result.get("answer", "")
        courses = result.get("courses", [])
        sources = result.get("sources", [])
        for i in range(0, len(answer), 40):
            yield {"type": "answer", "delta": answer[i : i + 40]}
        yield {"type": "done", "session_id": session_memory.session_id,
               "courses": courses, "sources": sources, "mode": "fallback"}
