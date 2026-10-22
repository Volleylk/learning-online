"""聊天接口路由（SSE 流式）。"""
from __future__ import annotations

import json
import logging
import time
import uuid
from typing import List

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app.agent import AgentCore
from app.auth import AuthContext, get_auth_context, get_current_user
from app.memory import SessionMemory
from app.schemas import ChatRequest

router = APIRouter(prefix="/api", tags=["chat"])

log = logging.getLogger(__name__)

# SSE 心跳间隔（秒）：注释型 ping 防代理/网关掐断长连接
_HEARTBEAT_SEC = 15


def _agent_core() -> AgentCore:
    from app.main import agent_core

    return agent_core


async def _event_stream(
    core: AgentCore,
    message: str,
    user_id: str,
    memory: SessionMemory,
    session_id: str,
    token: str = "",
    profile: dict | None = None,
):
    """把 AgentCore.stream_run（LangGraph v3 异步流式）的事件序列组装成 SSE 文本，结束后落库。"""
    answer_parts: List[str] = []
    courses_acc: List[dict] = []
    last_sent = time.monotonic()

    def _merge_courses(items: list | None) -> None:
        for c in items or []:
            if not any(x.get("id") == c.get("id") for x in courses_acc):
                courses_acc.append(c)

    try:
        async for ev in core.stream_run(message, user_id, memory, token, profile):
            # 心跳：无事件超过阈值时补发注释行，保持连接活跃
            now = time.monotonic()
            if now - last_sent >= _HEARTBEAT_SEC:
                yield ": ping\n\n"
                last_sent = now
            if ev.get("type") == "answer":
                answer_parts.append(ev.get("delta", ""))
            elif ev.get("type") == "courses":
                _merge_courses(ev.get("courses"))
            elif ev.get("type") == "done":
                _merge_courses(ev.get("courses"))
            yield "data: " + json.dumps(ev, ensure_ascii=False) + "\n\n"
            last_sent = time.monotonic()
    finally:
        # 客户端断开也尽量落库（审计）；answer 可能不完整
        try:
            memory.save_turn(message, "".join(answer_parts), courses_acc or None)
        except Exception as e:
            log.warning("对话落库失败: %s", e)


@router.post("/chat")
def chat(
    req: ChatRequest,
    auth: AuthContext = Depends(get_auth_context),
) -> StreamingResponse:
    core = _agent_core()
    session_id = req.session_id or str(uuid.uuid4())

    # 短期记忆：加载该会话最近历史
    import app.db as db

    history = db.load_chat_history(auth.user_id, session_id, max_turns=20)
    memory = SessionMemory(
        user_id=auth.user_id,
        session_id=session_id,
        llm=core.llm,
        history_turns=history,
    )

    return StreamingResponse(
        _event_stream(
            core,
            req.message,
            auth.user_id,
            memory,
            session_id,
            auth.token,
            auth.profile,
        ),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )


@router.get("/sessions")
def sessions(
    limit: int = 20, user_id: str = Depends(get_current_user)
) -> list:
    import app.db as db

    return [s for s in db.list_sessions(user_id, limit)]


@router.get("/sessions/{session_id}/messages")
def session_messages(
    session_id: str, user_id: str = Depends(get_current_user)
) -> list:
    """加载某会话的历史消息（历史会话 UI 用）。"""
    import app.db as db

    return db.load_chat_history(user_id, session_id, max_turns=100)
