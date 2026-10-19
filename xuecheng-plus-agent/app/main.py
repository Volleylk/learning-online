"""学成在线门户 AI 助手 - FastAPI 入口。

启动流程：初始化数据库 → 初始化 RAG 向量库 → 构建 Agent → 启动讲义消费线程。
托管聊天前端页面（static/）与全部后端 AI 接口。
"""
from __future__ import annotations

import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from app.agent import AgentCore
from app.config import settings
from app.db import init_db
from app.llm import create_chat_model, create_embeddings
from app.rag import init_rag
from app.rabbitmq import LectureConsumer
from app.routers import chat as chat_router
from app.routers import lecture as lecture_router
from app.routers import preferences as pref_router

agent_core: AgentCore
consumer: LectureConsumer | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global agent_core, consumer
    # 1) 数据库建表
    init_db()
    # 2) RAG 向量库
    rag = init_rag(create_embeddings())
    print(f"[startup] RAG向量库就绪，切片数={rag.count}")
    # 3) Agent 调度核心
    llm = create_chat_model()
    agent_core = AgentCore(rag=rag, llm=llm)
    print(f"[startup] Agent就绪，模式={'AgentExecutor(百炼qwen)' if settings.llm_enabled else '无Key降级规则引擎'}")
    # 4) 讲义消费线程
    if settings.rabbit_consumer_enabled:
        consumer = LectureConsumer(rag)
        consumer.start()
    yield
    if consumer is not None:
        consumer.stop()
    print("[shutdown] 服务已停止")


app = FastAPI(
    title=settings.app_name,
    description="学成在线门户 AI 助手（FastAPI + LangChain + 百炼 + Chroma + MySQL + RabbitMQ + MinIO）",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(chat_router.router)
app.include_router(lecture_router.router)
app.include_router(pref_router.router)


@app.get("/api/health")
def health() -> dict:
    status = {
        "status": "ok",
        "llm_enabled": settings.llm_enabled,
        "llm_model": settings.llm_model if settings.llm_enabled else "fallback-local",
        "rag_chunks": agent_core.rag.count if "agent_core" in globals() else 0,
        "mysql": _ping_mysql(),
        "rabbitmq": _ping_rabbitmq(),
        "minio": _ping_minio(),
        "mode": "agent" if settings.llm_enabled else "fallback",
    }
    return status


def _ping_mysql() -> str:
    try:
        from sqlalchemy import text

        with app_db_session() as s:
            s.execute(text("SELECT 1"))
        return "ok"
    except Exception as e:
        return f"error: {e}"


def _ping_rabbitmq() -> str:
    try:
        import pika

        from app.config import settings as st

        cred = pika.PlainCredentials(st.rabbitmq_user, st.rabbitmq_password)
        conn = pika.BlockingConnection(
            pika.ConnectionParameters(
                host=st.rabbitmq_host, port=st.rabbitmq_port,
                virtual_host=st.rabbitmq_vhost, credentials=cred, connection_attempts=1,
            )
        )
        conn.close()
        return "ok"
    except Exception as e:
        return f"error: {e}"


def _ping_minio() -> str:
    try:
        from app.minio_client import get_minio_client

        get_minio_client().bucket_exists(settings.minio_bucket)
        return "ok"
    except Exception as e:
        return f"error: {e}"


def app_db_session():
    from app.db import session_scope

    return session_scope()


# 静态文件挂载必须放在所有 API 路由之后，否则会拦截 /api/* 请求
_static_dir = os.path.join(os.path.dirname(__file__), "static")
app.mount("/", StaticFiles(directory=_static_dir, html=True), name="static")
