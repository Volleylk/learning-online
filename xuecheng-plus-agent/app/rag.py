"""RAG 检索模块。

- 向量库：Chroma（持久化本地目录 data/chroma）
- 入库：讲义文本 → 切分 → 百炼 embedding 向量化 → 写入切片（带 course_id/course_name/source_file/page_num 元数据）
- 检索：向量语义召回 + BM25 关键词召回 → RRF 融合去重 → 返回 TopN 片段
"""
from __future__ import annotations

import hashlib
import logging
import os
import re
import tempfile
import uuid
from typing import Dict, List, Optional, Tuple

import chromadb
from langchain_text_splitters import RecursiveCharacterTextSplitter
from rank_bm25 import BM25Okapi

from app.config import settings

log = logging.getLogger(__name__)


def _tokenize(text: str) -> List[str]:
    """轻量中文分词：中文按单字 + 英文/数字按词，够 BM25 演示用。"""
    return re.findall(r"[\u4e00-\u9fff]|[a-zA-Z0-9]+", text.lower())


class RAGService:
    def __init__(self, embeddings=None):
        self.embeddings = embeddings
        self._client = chromadb.PersistentClient(path=settings.chroma_path)
        self._collection = self._client.get_or_create_collection(
            settings.chroma_collection,
            metadata={"hnsw:space": "cosine"},
        )
        self._corpus_ids: List[str] = []
        self._corpus_texts: List[str] = []
        self._corpus_meta: List[Dict] = []
        self._bm25: Optional[BM25Okapi] = None
        self.refresh_corpus()

    # ------------------------------------------------------------------
    # 语料维护
    # ------------------------------------------------------------------
    def refresh_corpus(self) -> None:
        """全量加载当前向量库内容，重建 BM25 索引。"""
        data = self._collection.get(include=["documents", "metadatas"])
        self._corpus_ids = list(data.get("ids", []))
        self._corpus_texts = list(data.get("documents", []))
        self._corpus_meta = list(data.get("metadatas", []))
        if self._corpus_texts:
            self._bm25 = BM25Okapi([_tokenize(t) for t in self._corpus_texts])
        else:
            self._bm25 = None

    @property
    def count(self) -> int:
        return len(self._corpus_ids)

    # ------------------------------------------------------------------
    # 入库
    # ------------------------------------------------------------------
    def add_chunks(self, texts: List[str], metadatas: List[Dict]) -> int:
        """向量化并写入 Chroma；返回写入条数。"""
        if not texts:
            return 0
        ids = [str(uuid.uuid4()) for _ in texts]
        embeddings = self.embeddings.embed_documents(texts)
        self._collection.add(
            ids=ids,
            embeddings=embeddings,
            documents=texts,
            metadatas=metadatas,
        )
        self.refresh_corpus()
        return len(ids)

    @staticmethod
    def _file_hash(file_path: str) -> str:
        """计算文件内容 MD5（去重键：同一文件重复上传时 hash 相同）。"""
        h = hashlib.md5()
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        return h.hexdigest()

    def ingest_file(
        self,
        file_path: str,
        course_id: str,
        course_name: str,
        source_file: str,
    ) -> Dict:
        """解析讲义文件（PDF/DOCX/TXT/MD），切分后入库。返回统计信息。"""
        ext = os.path.splitext(file_path)[1].lower()
        pages: List[Tuple[int, str]] = []

        if ext == ".pdf":
            from pypdf import PdfReader

            reader = PdfReader(file_path)
            for i, page in enumerate(reader.pages, start=1):
                text = (page.extract_text() or "").strip()
                if text:
                    pages.append((i, text))
        elif ext == ".docx":
            import docx2txt

            text = docx2txt.process(file_path) or ""
            pages = [(1, text)] if text.strip() else []
        elif ext in (".txt", ".md", ".text"):
            with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                text = f.read()
            pages = [(1, text)] if text.strip() else []
        else:
            raise ValueError(f"不支持的讲义格式: {ext}")

        if not pages:
            return {"status": "empty", "chunks": 0, "pages": 0}

        # 解析成功：按 course_id + 文件内容hash 查重，删除旧版本切片再写新（覆盖式），
        # 避免同一份讲义重复上传导致 Chroma 里累积重复切片
        file_hash = self._file_hash(file_path)
        old = self._collection.get(where={"$and": [{"course_id": str(course_id)}, {"file_hash": file_hash}]})
        old_ids = old.get("ids", []) if old else []
        if old_ids:
            self._collection.delete(ids=old_ids)
            log.info("讲义重复入库，已删除旧切片 {} 条: course_id={}, file_hash={}",
                     len(old_ids), course_id, file_hash[:8])

        splitter = RecursiveCharacterTextSplitter(
            chunk_size=settings.chunk_size,
            chunk_overlap=settings.chunk_overlap,
            separators=["\n\n", "\n", "。", "！", "？", "；", " ", ""],
        )
        texts: List[str] = []
        metadatas: List[Dict] = []
        for page_num, page_text in pages:
            for chunk in splitter.split_text(page_text):
                chunk = chunk.strip()
                if not chunk:
                    continue
                texts.append(chunk)
                metadatas.append(
                    {
                        "course_id": str(course_id),
                        "course_name": course_name,
                        "source_file": source_file,
                        "page_num": page_num,
                        "chunk_text": chunk[:500],
                        "file_hash": file_hash,
                    }
                )

        added = self.add_chunks(texts, metadatas)
        return {"status": "ok", "chunks": added, "pages": len(pages)}

    # ------------------------------------------------------------------
    # 检索
    # ------------------------------------------------------------------
    def search(self, query: str, top_k: Optional[int] = None) -> List[Dict]:
        """混合检索：向量召回 + BM25 召回 → RRF 融合去重 → TopN。"""
        # 入口防御：非字符串/空查询直接返回空，避免 embedding 接口 400
        query = str(query or "").strip()
        if not query:
            return []
        top_k = top_k or settings.rag_top_k
        # 语料过期自动刷新（其他进程/消费者可能已写入新切片）
        if self._collection.count() != len(self._corpus_ids):
            self.refresh_corpus()
        if not self._corpus_ids:
            return []

        # 1) 向量语义召回（按相似度阈值过滤：distance 大于 rag_max_distance 的
        #    低相关片段不参与后续 RRF 融合；过滤后为空则退回原始召回，避免检索失效）
        q_vec = self.embeddings.embed_query(query)
        vec_res = self._collection.query(
            query_embeddings=[q_vec],
            n_results=min(top_k * 3, len(self._corpus_ids)),
            include=["documents", "metadatas", "distances"],
        )
        vec_ids: List[str] = vec_res["ids"][0]
        vec_docs: List[str] = vec_res["documents"][0]
        vec_meta: List[dict] = vec_res["metadatas"][0]
        vec_dists: List[float] = vec_res["distances"][0]
        filtered = [
            (cid, d, m)
            for cid, d, m, dist in zip(vec_ids, vec_docs, vec_meta, vec_dists)
            if dist <= settings.rag_max_distance
        ]
        if not filtered:
            filtered = list(zip(vec_ids, vec_docs, vec_meta))
        vec_ids = [x[0] for x in filtered]
        vec_docs = [x[1] for x in filtered]
        vec_meta = [x[2] for x in filtered]

        # 2) BM25 关键词召回
        bm25_ids: List[str] = []
        bm25_docs: List[str] = []
        bm25_meta: List[dict] = []
        if self._bm25 is not None:
            scores = self._bm25.get_scores(_tokenize(query))
            ranked = sorted(
                range(len(self._corpus_ids)),
                key=lambda i: scores[i],
                reverse=True,
            )[: top_k * 3]
            for i in ranked:
                if scores[i] > 0:
                    bm25_ids.append(self._corpus_ids[i])
                    bm25_docs.append(self._corpus_texts[i])
                    bm25_meta.append(self._corpus_meta[i])

        # 3) RRF 融合
        k = settings.rrf_k
        rrf_score: Dict[str, float] = {}
        for ids in (vec_ids, bm25_ids):
            for rank, cid in enumerate(ids, start=1):
                rrf_score[cid] = rrf_score.get(cid, 0.0) + 1.0 / (k + rank)

        doc_map = {cid: (d, m) for cid, d, m in zip(vec_ids, vec_docs, vec_meta)}
        for cid, d, m in zip(bm25_ids, bm25_docs, bm25_meta):
            doc_map.setdefault(cid, (d, m))

        merged = sorted(rrf_score.items(), key=lambda x: x[1], reverse=True)[:top_k]
        results = []
        for cid, score in merged:
            doc, meta = doc_map[cid]
            results.append(
                {
                    "id": cid,
                    "text": doc,
                    "score": round(score, 4),
                    "course_id": meta.get("course_id"),
                    "course_name": meta.get("course_name"),
                    "source_file": meta.get("source_file"),
                    "page_num": meta.get("page_num"),
                }
            )
        return results


# 单例（FastAPI 启动时由 main 初始化并注入 embeddings）
_rag: Optional[RAGService] = None


def get_rag() -> RAGService:
    if _rag is None:
        raise RuntimeError("RAGService 尚未初始化，请先调用 init_rag()")
    return _rag


def init_rag(embeddings) -> RAGService:
    global _rag
    _rag = RAGService(embeddings=embeddings)
    return _rag
