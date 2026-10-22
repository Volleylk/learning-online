"""大模型接入模块。

统一封装阿里百炼平台（OpenAI 兼容模式）：
- 对话模型：千问系列（qwen3.8-max），供 Agent 调度、上下文总结使用
- 向量化模型：百炼 text-embedding 系列，供 RAG 入库与检索使用

未配置 DASHSCOPE_API_KEY 时进入降级模式：
- 对话模型 → FallbackChatModel（不依赖外部网络，可本地跑通流程）
- 向量化模型 → FallbackEmbeddings（确定性哈希向量，保证 RAG 流程可验证）
"""
from __future__ import annotations

import hashlib
import math
import re
import time
from typing import Any, List, Optional

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_openai import ChatOpenAI

from app.config import settings

# ---------------------------------------------------------------------------
# 对话模型
# ---------------------------------------------------------------------------


class FallbackChatModel(BaseChatModel):
    """无 Key 时的降级对话模型：按模板生成可验证的回复，不发起真实网络调用。"""

    model_name: str = "fallback-local"
    temperature: float = 0.0

    @property
    def _llm_type(self) -> str:
        return "fallback-local"

    def _generate(
        self,
        messages: List[BaseMessage],
        stop: Optional[List[str]] = None,
        run_manager: Any = None,
        **kwargs: Any,
    ) -> ChatResult:
        text = "\n".join(
            f"{m.type}: {m.content if isinstance(m.content, str) else str(m.content)}"
            for m in messages[-4:]
        )
        # 从最后一条人类消息里取问题
        question = ""
        for m in reversed(messages):
            if m.type == "human" and m.content:
                question = m.content if isinstance(m.content, str) else str(m.content)
                break
        answer = (
            "[降级模式·未配置DASHSCOPE_API_KEY] 我已收到你的问题："
            f"“{question}”\n"
            "当前为本地降级模式，无法调用千问大模型生成自然语言回答。"
            "请在 .env 中填入阿里百炼 DASHSCOPE_API_KEY 后重启服务，即可获得真实 AI 回答。\n"
            "本轮上下文摘要：" + text[-200:]
        )
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content=answer))])


def create_chat_model() -> BaseChatModel:
    """按配置创建对话模型：有 Key 用千问，无 Key 用降级模型。

    LangChain 1.x 说明：qwen3.8-max 在百炼 OpenAI 兼容端点上实测无需
    result_format=message / incremental_output（0.3 时代为流式工具调用
    必需的 extra_body 透传），1.x 新版结构化流式协议直接产出 tool_call
    增量，故这里使用干净配置；若日后换用旧模型可显式传 extra_body 参数。
    """
    if settings.llm_enabled:
        return ChatOpenAI(
            model=settings.llm_model,
            api_key=settings.dashscope_api_key,
            base_url=settings.llm_base_url,
            temperature=settings.llm_temperature,
            timeout=60,
            max_retries=2,
        )
    return FallbackChatModel()


# ---------------------------------------------------------------------------
# 向量化模型
# ---------------------------------------------------------------------------


def _hash_vec(text: str, dim: int) -> List[float]:
    """确定性哈希向量（降级用）：同一文本永远得到同一向量，支持相似度排序。"""
    vec = [0.0] * dim
    tokens = re.findall(r"[\u4e00-\u9fff]|[a-zA-Z0-9]+", text)
    for tok in tokens:
        h = int(hashlib.md5(tok.encode("utf-8")).hexdigest(), 16)
        idx = h % dim
        sign = 1.0 if (h >> 8) % 2 == 0 else -1.0
        vec[idx] += sign
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / norm for v in vec]


class FallbackEmbeddings:
    """无 Key 时的降级向量化：确定性哈希向量，维度与真实模型一致。"""

    def __init__(self, dim: int = 1024):
        self.dim = dim

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        return [_hash_vec(t, self.dim) for t in texts]

    def embed_query(self, text: str) -> List[float]:
        return _hash_vec(text, self.dim)

    def embed_texts(self, texts: List[str]) -> List[List[float]]:
        return self.embed_documents(texts)


class DashScopeEmbeddings:
    """百炼 embedding 封装（httpx 直调 compatible-mode 标准格式）。

    背景：langchain-openai 新版 OpenAIEmbeddings 会把 input 包装成
    [{"content": ...}] 数组对象格式，百炼兼容端点不识别（报
    "contents is neither str nor list of str.: input.contents"）。
    这里按 OpenAI 标准格式 {"input": ["文本", ...]} 直调，规避该兼容性问题。
    """

    def __init__(self, model: str, api_key: str, base_url: str, dim: int = 1024):
        self.model = model
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.dim = dim

    def _post(self, texts: List[str]) -> List[List[float]]:
        import httpx

        # 指数退避重试：5xx / 429（限流）/ 网络错误 最多重试 3 次
        last_err: Exception | None = None
        for attempt in range(3):
            try:
                resp = httpx.post(
                    f"{self.base_url}/embeddings",
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                    json={"model": self.model, "input": texts},
                    timeout=60,
                )
                if resp.status_code != 200:
                    if resp.status_code in (429, 500, 502, 503, 504) and attempt < 2:
                        raise RuntimeError(f"HTTP {resp.status_code}（可重试）")
                    raise RuntimeError(
                        f"百炼embedding调用失败 {resp.status_code}: {resp.text[:300]}"
                    )
                data = resp.json()["data"]
                data.sort(key=lambda x: x.get("index", 0))  # 按请求顺序返回
                return [item["embedding"] for item in data]
            except Exception as e:  # 网络/超时/可重试状态码
                last_err = e
                if attempt < 2:
                    time.sleep(0.5 * (2 ** attempt))  # 0.5s / 1s
        raise RuntimeError(f"百炼embedding调用连续失败: {last_err}")

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        if not texts:
            return []
        out: List[List[float]] = []
        for i in range(0, len(texts), 10):  # 分批（百炼单次上限 10 条）
            out.extend(self._post(texts[i : i + 10]))
        return out

    def embed_query(self, text: str) -> List[float]:
        return self._post([text])[0]

    def embed_texts(self, texts: List[str]) -> List[List[float]]:
        return self.embed_documents(texts)


def create_embeddings():
    """按配置创建向量化模型：有 Key 用百炼 text-embedding，无 Key 用哈希向量。"""
    if settings.llm_enabled:
        return DashScopeEmbeddings(
            model=settings.embedding_model,
            api_key=settings.dashscope_api_key,
            base_url=settings.llm_base_url,
            dim=settings.embedding_dim,
        )
    return FallbackEmbeddings(dim=settings.embedding_dim)
