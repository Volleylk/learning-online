"""记忆管理模块。

分层记忆（全部持久化到 MySQL）：
1. 短期对话记忆：按 user_id + session_id 关联存储，会话内自动注入上下文
2. 长期偏好记忆：按 user_id 唯一存储，每次对话自动加载注入提示词

LangChain 1.x 已移除 ConversationBufferMemory，这里改为自管理消息列表
（内存缓冲 + 压缩），MySQL 仍保留全量对话用于审计。

上下文压缩由 SummarizationMiddleware 完成：对话上下文 token 数超过阈值时，
调用大模型对历史做摘要并替换运行时上下文。
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage

import app.db as db
from app.config import settings
from app.llm import FallbackChatModel


def estimate_tokens(text: str) -> int:
    """粗略 token 估算：中文约 1.5 字符/token，英文约 4 字符/token。"""
    cjk = sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")
    other = len(text) - cjk
    return cjk // 1 + other // 4 + 1


class SummarizationMiddleware:
    """对话上下文压缩中间件。

    挂载在对话记忆上：每次写入新对话后检查 token 总量，
    超过阈值则调用大模型对历史做摘要，压缩运行时上下文；
    原始全量对话保留在 MySQL 中用于审计。
    """

    def __init__(self, llm: BaseChatModel, threshold_tokens: int = 2000):
        self.llm = llm
        self.threshold_tokens = threshold_tokens
        self.summary: str = ""

    def check_and_compress(self, messages: List[BaseMessage]) -> None:
        """在写入新对话之后调用：超阈值则压缩。"""
        raw = "\n".join(
            f"{m.type}: {m.content if isinstance(m.content, str) else str(m.content)}"
            for m in messages
        )
        if estimate_tokens(raw) <= self.threshold_tokens:
            return

        if isinstance(self.llm, FallbackChatModel):
            # 降级模式：无大模型可用，保留最近部分作为摘要（不丢审计数据）
            self.summary = raw[-800:]
        else:
            try:
                prompt = (
                    "请把以下对话历史压缩为一段中文摘要，保留关键事实、用户偏好、"
                    "已讨论的课程与结论，200字以内。\n\n" + raw
                )
                resp = self.llm.invoke([HumanMessage(content=prompt)])
                self.summary = resp.content if isinstance(resp.content, str) else str(resp.content)
            except Exception as e:  # 摘要失败不阻断对话
                self.summary = raw[-800:]

        # 压缩运行时上下文：清空缓冲，由 to_context 注入摘要
        messages.clear()

    def to_context(self, messages: List[BaseMessage]) -> List[BaseMessage]:
        """把（摘要 + 剩余未压缩消息）组装为注入上下文的消息列表。"""
        context: List[BaseMessage] = []
        if self.summary:
            context.append(SystemMessage(content=f"之前的对话摘要：{self.summary}"))
        context.extend(messages)
        return context


class SessionMemory:
    """单个会话的短期记忆封装（内存缓冲 + MySQL 全量落盘 + 自动压缩）。"""

    def __init__(
        self,
        user_id: str,
        session_id: str,
        llm: BaseChatModel,
        history_turns: Optional[List[dict]] = None,
    ):
        self.user_id = user_id
        self.session_id = session_id
        self.llm = llm
        self.middleware = SummarizationMiddleware(
            llm, threshold_tokens=settings.memory_token_threshold
        )
        self.messages: List[BaseMessage] = []
        # 预置最近历史（来自 MySQL），保证多轮连贯
        for turn in history_turns or []:
            if turn["role"] == "user":
                self.messages.append(HumanMessage(content=turn["content"]))
            else:
                self.messages.append(AIMessage(content=turn["content"]))

    def load_variables(self) -> Dict[str, Any]:
        """返回注入给 Agent 的上下文变量。"""
        msgs = self.middleware.to_context(self.messages)
        return {"chat_history": msgs}

    def save_turn(
        self,
        user_msg: str,
        assistant_msg: str,
        assistant_courses: Optional[list] = None,
    ) -> None:
        # 1) 全量落盘 MySQL（审计用；assistant 消息携带课程卡片 JSON）
        db.save_chat_turn(self.user_id, self.session_id, "user", user_msg)
        db.save_chat_turn(
            self.user_id,
            self.session_id,
            "assistant",
            assistant_msg,
            assistant_courses,
        )
        # 2) 更新内存缓冲
        self.messages.append(HumanMessage(content=user_msg))
        self.messages.append(AIMessage(content=assistant_msg))
        # 3) 内存上限裁剪：超出丢弃最旧消息（DB 全量落盘不受影响）
        max_msgs = settings.memory_max_messages
        if max_msgs > 0 and len(self.messages) > max_msgs:
            self.messages = self.messages[-max_msgs:]
        # 4) 触发上下文压缩
        self.middleware.check_and_compress(self.messages)


def build_system_prompt(user_id: str, display_name: str = "", extra_rules: str = "") -> str:
    """构建 Agent 系统提示词：注入用户身份/长期偏好 + 模块行为规则。"""
    prefs = db.get_preferences(user_id)
    pref_text = db.preferences_to_prompt(prefs)
    identity = f"用户ID：{user_id}"
    if display_name:
        identity += f"，用户昵称/姓名：{display_name}"
    return (
        "你是学成在线门户的AI学习助手，负责通用聊天、课程讲义知识问答、课程推荐。\n"
        f"{identity}\n"
        f"该用户的长期学习偏好：{pref_text}\n"
        "行为规则：\n"
        "1. 通用闲聊直接回答，不调用工具；\n"
        "2. 涉及课程知识点时调用课程讲义检索工具，基于检索内容回答，并标注来源课程；\n"
        "3. 用户表现出找课需求（如'推荐课程''学什么''入门''进阶'）时调用课程信息查询工具；\n"
        "4. 如果检索到课程推荐，用《课程名》+「推荐」标签+一句话简介在正文说明课程即可；不要在正文输出任何课程网址（裸链接或Markdown链接都禁止），系统会自动在回答下方展示可点击的课程卡片；\n"
        "5. 使用用户偏好的解释风格作答，贴合其学习阶段。\n"
        + extra_rules
    )
