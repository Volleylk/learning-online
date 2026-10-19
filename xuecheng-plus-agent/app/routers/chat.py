"""聊天接口路由（SSE 流式）。"""
from __future__ import annotations

import json
import uuid
from typing import Iterator, List

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app.agent import AgentCore
from app.auth import get_current_credentials, get_current_user, get_current_user_profile
from app.memory import SessionMemory
from app.schemas import ChatRequest

router = APIRouter(prefix="/api", tags=["chat"])


def _agent_core() -> AgentCore:
    from app.main import agent_core

    return agent_core


def _event_stream(
    core: AgentCore,
    message: str,
    user_id: str,
    memory: SessionMemory,
    session_id: str,
    token: str = "",
    profile: dict | None = None,
) -> Iterator[str]:
    """把 AgentCore.stream_run 的事件序列组装成 SSE 文本，结束后落库。"""
    answer_parts: List[str] = []
    try:
        for ev in core.stream_run(message, user_id, memory, token, profile):
            if ev.get("type") == "answer":
                answer_parts.append(ev.get("delta", ""))
            yield "data: " + json.dumps(ev, ensure_ascii=False) + "\n\n"
    finally:
        # 客户端断开也尽量落库（审计）；answer 可能不完整
        try:
            memory.save_turn(message, "".join(answer_parts))
        except Exception as e:
            print(f"[chat] 对话落库失败: {e}")


@router.post("/chat")
def chat(
    req: ChatRequest,
    token: str = Depends(get_current_credentials),
    user_id: str = Depends(get_current_user),
    profile: dict = Depends(get_current_user_profile),
) -> StreamingResponse:
    core = _agent_core()
    session_id = req.session_id or str(uuid.uuid4())

    # 短期记忆：加载该会话最近历史
    import app.db as db

    history = db.load_chat_history(user_id, session_id, max_turns=20)
    memory = SessionMemory(
        user_id=user_id,
        session_id=session_id,
        llm=core.llm,
        history_turns=history,
    )

    return StreamingResponse(
        _event_stream(core, req.message, user_id, memory, session_id, token, profile),
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
