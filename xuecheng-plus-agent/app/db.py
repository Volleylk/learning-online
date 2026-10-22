"""MySQL 数据层（AI 模块独立维护，不修改原有业务表）。

- chat_history       按 user_id + session_id 存储每轮对话（短期对话记忆 + 审计）
- user_preferences   按 user_id 唯一存储长期学习偏好
"""
from __future__ import annotations

import json
import logging
from contextlib import contextmanager
from datetime import datetime
from typing import Iterator, List, Optional

from sqlalchemy import (
    BigInteger,
    Column,
    DateTime,
    Index,
    String,
    Text,
    create_engine,
    func,
)
from sqlalchemy.orm import Session, declarative_base, sessionmaker

from app.config import settings

log = logging.getLogger(__name__)

Base = declarative_base()


class ChatHistory(Base):
    __tablename__ = "chat_history"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    user_id = Column(String(64), nullable=False, index=True)
    session_id = Column(String(64), nullable=False, index=True)
    role = Column(String(16), nullable=False)          # user / assistant
    content = Column(Text, nullable=False)
    courses = Column(Text, nullable=True)              # 课程卡片 JSON 列表（assistant 消息携带）
    created_at = Column(DateTime, nullable=False, default=datetime.now)

    __table_args__ = (
        # 按 user + session + 时间检索
        Index("idx_user_session_time", "user_id", "session_id", "created_at"),
    )


class UserPreference(Base):
    __tablename__ = "user_preferences"

    user_id = Column(String(64), primary_key=True)
    learning_stage = Column(String(64), nullable=True)      # 学习阶段：如 入门/进阶/求职
    explanation_style = Column(String(64), nullable=True)   # 解释风格：如 通俗/严谨/举例
    focus_directions = Column(Text, nullable=True)          # 关注方向：逗号分隔
    extra = Column(Text, nullable=True)                     # 扩展字段 JSON
    updated_at = Column(DateTime, nullable=False, default=datetime.now, onupdate=datetime.now)


def _db_url() -> str:
    return (
        f"mysql+pymysql://{settings.mysql_user}:{settings.mysql_password}"
        f"@{settings.mysql_host}:{settings.mysql_port}/{settings.mysql_db}"
        f"?charset=utf8mb4"
    )


engine = create_engine(
    _db_url(),
    pool_pre_ping=True,
    pool_recycle=3600,
    pool_size=5,
    max_overflow=5,
    echo=False,
)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


@contextmanager
def session_scope() -> Iterator[Session]:
    """事务型会话上下文。"""
    session = SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def init_db() -> None:
    """初始化 AI 模块两张表（IF NOT EXISTS 语义）。"""
    # 确保数据库存在（MySQL 的 create database if not exists）
    import pymysql

    conn = pymysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_password,
        charset="utf8mb4",
    )
    try:
        with conn.cursor() as cur:
            cur.execute(
                f"CREATE DATABASE IF NOT EXISTS `{settings.mysql_db}` "
                "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            )
        conn.commit()
    finally:
        conn.close()
    Base.metadata.create_all(engine)
    _migrate_chat_history_courses()


def _migrate_chat_history_courses() -> None:
    """老库补列：chat_history.courses（幂等）。"""
    from sqlalchemy import text as sa_text

    with session_scope() as session:
        exists = session.execute(
            sa_text(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                "WHERE TABLE_SCHEMA = :db AND TABLE_NAME = 'chat_history' AND COLUMN_NAME = 'courses'"
            ),
            {"db": settings.mysql_db},
        ).scalar()
        if not exists:
            session.execute(sa_text("ALTER TABLE chat_history ADD COLUMN courses TEXT NULL"))
            log.info("chat_history.courses 列已添加（迁移）")


# ---------------------------------------------------------------------------
# 短期对话记忆
# ---------------------------------------------------------------------------

def save_chat_turn(
    user_id: str,
    session_id: str,
    role: str,
    content: str,
    courses: list | None = None,
) -> None:
    with session_scope() as session:
        session.add(
            ChatHistory(
                user_id=user_id,
                session_id=session_id,
                role=role,
                content=content,
                courses=json.dumps(courses, ensure_ascii=False) if courses else None,
            )
        )


def load_chat_history(user_id: str, session_id: str, max_turns: int = 20) -> List[dict]:
    """加载最近 max_turns 轮对话（按时间正序），供上下文注入。"""
    with session_scope() as session:
        rows = (
            session.query(ChatHistory)
            .filter(ChatHistory.user_id == user_id, ChatHistory.session_id == session_id)
            .order_by(ChatHistory.created_at.desc(), ChatHistory.id.desc())
            .limit(max_turns * 2)
            .all()
        )
    rows.reverse()
    return [
        {
            "role": r.role,
            "content": r.content,
            "courses": json.loads(r.courses) if r.courses else None,
        }
        for r in rows
    ]


# ---------------------------------------------------------------------------
# 长期偏好记忆
# ---------------------------------------------------------------------------

def get_preferences(user_id: str) -> dict:
    with session_scope() as session:
        row = session.query(UserPreference).filter(UserPreference.user_id == user_id).first()
    if row is None:
        return {
            "user_id": user_id,
            "learning_stage": None,
            "explanation_style": None,
            "focus_directions": None,
            "extra": {},
        }
    return {
        "user_id": row.user_id,
        "learning_stage": row.learning_stage,
        "explanation_style": row.explanation_style,
        "focus_directions": row.focus_directions,
        "extra": json.loads(row.extra) if row.extra else {},
    }


def upsert_preferences(user_id: str, **fields) -> dict:
    """按字段名更新用户偏好（只更新传入的非空字段）。"""
    allowed = {"learning_stage", "explanation_style", "focus_directions", "extra"}
    updates = {k: v for k, v in fields.items() if k in allowed and v is not None}
    if "extra" in updates and not isinstance(updates["extra"], str):
        updates["extra"] = json.dumps(updates["extra"], ensure_ascii=False)

    with session_scope() as session:
        row = session.query(UserPreference).filter(UserPreference.user_id == user_id).first()
        if row is None:
            row = UserPreference(user_id=user_id)
            session.add(row)
        for k, v in updates.items():
            setattr(row, k, v)
        # 直接返回内存中的最新值（避免在未提交事务里重查数据库读到旧值）
        current = {
            "user_id": row.user_id,
            "learning_stage": row.learning_stage,
            "explanation_style": row.explanation_style,
            "focus_directions": row.focus_directions,
            "extra": json.loads(row.extra) if row.extra else {},
        }
    return current


def preferences_to_prompt(prefs: dict) -> str:
    """把用户偏好渲染成系统提示词片段。"""
    parts = []
    if prefs.get("learning_stage"):
        parts.append(f"学习阶段：{prefs['learning_stage']}")
    if prefs.get("explanation_style"):
        parts.append(f"解释风格偏好：{prefs['explanation_style']}")
    if prefs.get("focus_directions"):
        parts.append(f"关注方向：{prefs['focus_directions']}")
    if prefs.get("extra"):
        try:
            extra = prefs["extra"] if isinstance(prefs["extra"], dict) else json.loads(prefs["extra"])
            for k, v in extra.items():
                parts.append(f"{k}：{v}")
        except Exception:
            pass
    return "；".join(parts) if parts else "（暂无）"


def list_sessions(user_id: str, limit: int = 20) -> List[dict]:
    """列出用户最近会话（前端"历史会话"用），附带各会话首条用户消息作摘要。"""
    with session_scope() as session:
        rows = (
            session.query(ChatHistory.session_id, func.max(ChatHistory.created_at).label("last"))
            .filter(ChatHistory.user_id == user_id)
            .group_by(ChatHistory.session_id)
            .order_by(func.max(ChatHistory.created_at).desc())
            .limit(limit)
            .all()
        )
        result = []
        for r in rows:
            first = (
                session.query(ChatHistory.content)
                .filter(
                    ChatHistory.user_id == user_id,
                    ChatHistory.session_id == r[0],
                    ChatHistory.role == "user",
                )
                .order_by(ChatHistory.id.asc())
                .first()
            )
            first_text = (first[0] or "") if first else ""
            result.append(
                {
                    "session_id": r[0],
                    "last_time": r[1].isoformat(),
                    "first_message": first_text[:40],
                }
            )
    return result


def last_session_id(user_id: str) -> Optional[str]:
    with session_scope() as session:
        row = (
            session.query(ChatHistory.session_id)
            .filter(ChatHistory.user_id == user_id)
            .order_by(ChatHistory.created_at.desc(), ChatHistory.id.desc())
            .first()
        )
    return row[0] if row else None
