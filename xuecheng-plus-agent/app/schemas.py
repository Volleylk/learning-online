"""接口数据模型。"""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=2000, description="用户问题")
    session_id: Optional[str] = Field(None, description="会话ID，不传则服务端新建")


class CourseCard(BaseModel):
    id: str
    name: str
    intro: str = ""
    cover: str = ""
    url: str = ""


class SourceItem(BaseModel):
    course_id: Optional[str] = None
    course_name: Optional[str] = None
    source_file: Optional[str] = None
    page_num: Optional[int] = None
    text: str = ""
    score: float = 0.0


class ChatResponse(BaseModel):
    answer: str
    session_id: str
    courses: List[CourseCard] = []
    sources: List[SourceItem] = []
    mode: str = "agent"   # agent=真实LangGraph / fallback=无Key降级


class PreferenceUpdate(BaseModel):
    learning_stage: Optional[str] = None
    explanation_style: Optional[str] = None
    focus_directions: Optional[str] = None
    extra: Optional[Dict[str, Any]] = None


class PreferenceResponse(BaseModel):
    user_id: str
    learning_stage: Optional[str] = None
    explanation_style: Optional[str] = None
    focus_directions: Optional[str] = None
    extra: Dict[str, Any] = {}


class SessionItem(BaseModel):
    session_id: str
    last_time: str = ""


class HealthResponse(BaseModel):
    status: str
    llm_enabled: bool
    rag_chunks: int
    mysql: str
    rabbitmq: str
    minio: str
