"""Agent 调度核心模块。

- 有 Key：基于 LangChain AgentExecutor + create_tool_calling_agent 构建决策中枢，
  自行判断是否调用讲义检索 / 课程查询 / 偏好管理工具，直到生成最终回答；
  挂载 SummarizationMiddleware 自动压缩历史上下文。
- 无 Key（降级模式）：规则引擎决策工具调用，本地可完整跑通
  "闲聊直答 / 知识点走RAG / 找课出课程卡片" 三条链路。

对外统一返回 {"answer": str, "courses": [课程卡片], "sources": [讲义片段], "mode": ...}
"""
from __future__ import annotations

import json
import re
from typing import Any, Dict, List, Optional

from langchain.agents import AgentExecutor, create_tool_calling_agent
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder

from app.config import settings
from app.memory import SessionMemory, build_system_prompt
from app.rag import RAGService
from app.tools import (
    make_course_query_tool,
    make_lecture_search_tool,
    make_my_courses_tool,
    make_preference_tools,
    pref_handler,
)

# 找课意图关键词（降级模式判断用）
FIND_COURSE_RE = re.compile(r"(推荐|找课|选课|学什么|入门|进阶|课程|有哪些|适合.*学|报.*班)")
# 知识点/讲义意图关键词
KNOWLEDGE_RE = re.compile(r"(讲义|知识点|概念|是什么|怎么理解|解释|原理|区别|为什么|怎么做|步骤)")


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


def _first_str_arg(args: Dict[str, Any]) -> str:
    """从工具参数 dict 中提取第一个有意义的字符串值。

    百炼 DashScope 兼容层对单参数工具返回的参数名是 __arg1（非 OpenAI
    标准的关键字名），因此不能只按 query/keyword 取，需兼容常见命名并
    兜底取任意第一个非空字符串。
    """
    if not isinstance(args, dict):
        return ""
    for key in ("query", "keyword", "__arg1", "input", "text", "search"):
        v = args.get(key)
        if isinstance(v, str) and v.strip():
            return v.strip()
    for v in args.values():
        if isinstance(v, str) and v.strip():
            return v.strip()
    return ""


def _parse_tool_args(raw: str) -> Dict[str, Any]:
    """容错解析百炼增量流式下的工具参数。

    百炼 incremental_output 的 tool_call_chunks.args 存在两种异常形态：
    1) 每次 chunk 携带全量 JSON（非增量片段），直接拼接导致 json.loads 失败；
    2) args 是字符串化的 JSON（内嵌转义引号）。
    这里逐级尝试：直接解析 → 取最后一个完整 JSON 对象 → 正则提取 keyword/query。
    """
    if not raw:
        return {}
    raw = raw.strip()
    # 1) 直接 JSON
    try:
        obj = json.loads(raw)
        if isinstance(obj, dict):
            return obj
        if isinstance(obj, str):
            obj2 = json.loads(obj)
            if isinstance(obj2, dict):
                return obj2
    except Exception:
        pass
    # 2) 重复全量拼接时取最后一个 JSON 对象（如 {"keyword":"A"}{"keyword":"AB"}）
    try:
        idx = raw.rfind("{")
        obj = json.loads(raw[idx:])
        if isinstance(obj, dict):
            return obj
    except Exception:
        pass
    # 3) 正则兜底
    for key in ("keyword", "query"):
        m = re.search(r'"%s"\s*:\s*"([^"]*)"' % key, raw)
        if m:
            return {key: m.group(1)}
    return {}


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
        self._lecture_tool = make_lecture_search_tool(rag)
        self._pref_tools = preference_tools or make_preference_tools(
            lambda u, a: pref_handler(u, a, "")
        )
        self._executor: Optional[AgentExecutor] = None
        if settings.llm_enabled:
            self._build_executor()

    # ------------------------------------------------------------------
    # 有 Key 路径：AgentExecutor
    # ------------------------------------------------------------------
    def _build_executor(self) -> None:
        tools = [self._lecture_tool, self._course_tool] + self._pref_tools
        prompt = ChatPromptTemplate.from_messages(
            [
                ("system", "{system_prompt}"),
                MessagesPlaceholder(variable_name="chat_history"),
                ("human", "{input}"),
                MessagesPlaceholder(variable_name="agent_scratchpad"),
            ]
        )
        agent = create_tool_calling_agent(self.llm, tools, prompt)
        self._executor = AgentExecutor(
            agent=agent,
            tools=tools,
            max_iterations=6,
            handle_parsing_errors=True,
            verbose=False,
            return_intermediate_steps=True,
        )

    def _run_agent_executor(
        self, query: str, session_memory: SessionMemory, user_id: str, profile: dict | None = None
    ) -> Dict[str, Any]:
        assert self._executor is not None
        system_prompt = build_system_prompt(user_id, _display_name(profile))
        try:
            result = self._executor.invoke(
                {
                    "system_prompt": system_prompt,
                    "input": query,
                    **session_memory.load_variables(),
                }
            )
            answer = result.get("output", "")
        except Exception as e:
            answer = f"抱歉，处理你的问题时出了点岔子：{e}"
            result = {}  # 兜底：invoke 异常时保证 result 有值，避免后续 UnboundLocalError

        # 从中间步骤提取课程卡片与讲义来源
        courses: List[Dict[str, Any]] = []
        sources: List[Dict[str, Any]] = []
        for step in result.get("intermediate_steps", []):
            action = step[0]
            # 精确取工具名（AgentAction.tool），避免把模型日志里的字样误当工具名
            tool_name = getattr(action, "tool", None) or getattr(action, "name", "") or ""
            try:
                parsed = json.loads(step[1])
            except Exception:
                continue
            if not isinstance(parsed, dict):
                continue
            if tool_name == "course_info_query" and parsed.get("found"):
                if isinstance(parsed.get("courses"), list):
                    # 多次命中时聚合去重，避免后一次查询覆盖前一次卡片
                    for c in parsed["courses"]:
                        if not any(
                            existing.get("id") == c.get("id") for existing in courses
                        ):
                            courses.append(c)
            elif tool_name == "course_lecture_search" and parsed.get("found"):
                items = parsed.get("items")
                if isinstance(items, list):
                    # 工具返回 page 字段，对外统一为 page_num
                    sources = [
                        {
                            "course_id": it.get("course_id"),
                            "course_name": it.get("course_name"),
                            "source_file": it.get("source_file"),
                            "page_num": it.get("page", it.get("page_num")),
                            "text": it.get("text", ""),
                        }
                        for it in items
                    ]
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
            prefs = build_system_prompt(user_id, _display_name(profile)).split("该用户的长期学习偏好：")[-1].split("\n")[0]
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
            return self._run_agent_executor(query, session_memory, user_id, profile)
        return self._run_fallback(query, session_memory, user_id, profile)

    # ------------------------------------------------------------------
    # 流式入口（SSE 事件序列）
    # 事件类型：thinking / answer(delta) / sources / courses / done
    # ------------------------------------------------------------------
    def stream_run(
        self,
        query: str,
        user_id: str,
        session_memory: SessionMemory,
        token: str = "",
        profile: dict | None = None,
    ):
        """token 为当前请求的原始 JWT（透传 Java 用户维接口）；profile 为用户画像（注入昵称）。"""
        if settings.llm_enabled:
            yield from self._stream_agent(query, user_id, session_memory, token, profile)
        else:
            yield from self._stream_fallback(query, user_id, session_memory, profile)

    def _stream_agent(
        self,
        query: str,
        user_id: str,
        session_memory: SessionMemory,
        token: str = "",
        profile: dict | None = None,
    ):
        """有 Key 流式：LangChain 原生 stream() + tool_call_chunks 手动累积。

        依赖 create_chat_model() 透传的百炼专属参数（extra_body 里的
        result_format=message / incremental_output=true），否则流式 chunk
        拿不到 tool_calls。LangChain 自带的 tool_call_chunks→tool_calls 聚合
        对百炼增量格式有 bug（末 chunk 聚合为空），这里按官方字段手动累积：
        每轮先收 chunk，工具轮聚合调用并执行工具，最终轮实时推送正文增量。"""
        yield {"type": "thinking", "text": "正在理解问题…"}
        system_prompt = build_system_prompt(user_id, _display_name(profile))
        history = session_memory.load_variables().get("chat_history", [])
        tools = [self._lecture_tool, self._course_tool] + self._pref_tools
        if token:
            # 请求级工具：携带当前用户 JWT，供"我的课程"查询透传 Java
            tools = tools + [make_my_courses_tool(token)]
        tool_map = {t.name: t for t in tools}
        courses: List[Dict[str, Any]] = []
        sources: List[Dict[str, Any]] = []
        messages: List[BaseMessage] = [
            SystemMessage(content=system_prompt),
            *history,
            HumanMessage(content=query),
        ]
        model = self.llm.bind_tools(tools)
        answer_parts: List[str] = []
        try:
            for step in range(6):
                tool_acc: Dict[int, Dict[str, str]] = {}
                is_tool_round = False
                for chunk in model.stream(messages):
                    content = getattr(chunk, "content", "")
                    if isinstance(content, str) and content and not is_tool_round:
                        answer_parts.append(content)
                        yield {"type": "answer", "delta": content}
                    # tool_call_chunks 是官方增量字段：首块带 id/name，后续块带 args 片段
                    for tcc in getattr(chunk, "tool_call_chunks", []) or []:
                        is_tool_round = True
                        idx = tcc.get("index", 0)
                        acc = tool_acc.setdefault(idx, {"name": "", "args": "", "id": ""})
                        if tcc.get("id"):
                            acc["id"] = tcc["id"]
                        if tcc.get("name"):
                            acc["name"] = tcc["name"]
                        if tcc.get("args"):
                            acc["args"] += tcc["args"]
                if not is_tool_round:
                    break  # 最终回答已流式输出完毕
                calls = []
                for i in sorted(tool_acc):
                    e = tool_acc[i]
                    if not e["name"]:
                        continue
                    print(f"[agent] tool_chunk name={e['name']!r} id={e['id']!r} args_raw={e['args']!r}")
                    args = _parse_tool_args(e["args"])
                    calls.append({"name": e["name"], "args": args, "id": e["id"]})
                if not calls:
                    break
                messages.append(
                    AIMessage(
                        content="",
                        tool_calls=[
                            {"name": c["name"], "args": c["args"], "id": c["id"], "type": "tool_call"}
                            for c in calls
                        ],
                    )
                )
                for call in calls:
                    tool = tool_map.get(call["name"])
                    # 规范化工具输入：兼容百炼 __arg1 / OpenAI keyword / query 等命名
                    raw_args = call["args"] if isinstance(call["args"], dict) else {}
                    tool_input = _first_str_arg(raw_args)[:200]
                    if not tool_input:
                        tool_input = "课程"
                    if tool is None:
                        out = json.dumps({"found": False, "message": f"未知工具: {call['name']}"}, ensure_ascii=False)
                    else:
                        try:
                            out = tool.func(tool_input)
                        except Exception as e:
                            out = json.dumps({"found": False, "message": str(e)[:200]}, ensure_ascii=False)
                    messages.append(ToolMessage(content=out, tool_call_id=call["id"]))
                    try:
                        parsed = json.loads(out)
                    except Exception:
                        parsed = None
                    if call["name"] == "course_info_query" and parsed and parsed.get("found") and isinstance(parsed.get("courses"), list):
                        # 多次命中时聚合去重，避免后一次查询覆盖前一次卡片
                        for c in parsed["courses"]:
                            if not any(
                                existing.get("id") == c.get("id") for existing in courses
                            ):
                                courses.append(c)
                        yield {"type": "thinking", "text": f"找到 {len(courses)} 门相关课程…"}
                    elif call["name"] == "course_lecture_search" and parsed and parsed.get("found"):
                        items = parsed.get("items")
                        if isinstance(items, list):
                            sources = [
                                {
                                    "course_id": it.get("course_id"),
                                    "course_name": it.get("course_name"),
                                    "source_file": it.get("source_file"),
                                    "page_num": it.get("page", it.get("page_num")),
                                    "text": it.get("text", ""),
                                }
                                for it in items
                            ]
                            yield {"type": "thinking", "text": f"检索到 {len(sources)} 条讲义片段…"}
                    elif call["name"] in ("user_preference_update", "user_preference_read"):
                        yield {"type": "thinking", "text": "正在读取/更新你的学习偏好…"}
        except Exception as e:
            msg = f"抱歉，处理你的问题时出了点岔子：{e}"
            answer_parts.append(msg)
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
