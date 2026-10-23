"""RAG 检索模块。

- 向量库：Chroma（持久化本地目录 data/chroma）
- 入库：讲义文本 → 父子分块（大块 parent / 小块 child，标题注入）→ 百炼 embedding → 写小块切片
- 检索：向量语义召回 + BM25（jieba+停用词）→ RRF 融合 → qwen3-rerank 精排 → 来源去重 → 返回大块
- 健壮性：空库防御 / embedding 降级 BM25-only / 语料刷新保底 / dirty 对账（修删N加N盲区）
"""
from __future__ import annotations

import hashlib
import logging
import os
import re
import tempfile
import time
import uuid
from typing import Dict, List, Optional, Tuple

import chromadb
from langchain_text_splitters import RecursiveCharacterTextSplitter
from rank_bm25 import BM25Okapi

from app.config import settings

log = logging.getLogger(__name__)

# 高频无义单字/词（BM25 停用词，过滤后 idf 更有区分度）
_STOP_CHARS = set("的了是在有我他这那和就都与到说要去会也你对还个没看但只被")
# 英文数字词项（jieba 对英文按整词保留，这里不额外处理）

# 词项正则：中文单字或英文/数字整词（jieba 不可用时的降级分词）
_TOKEN_RE = re.compile(r"[\u4e00-\u9fff]|[a-zA-Z0-9]+")


def _coerce_query_text(query) -> str:
    """查询参数归一化：兼容模型把字符串参数编码成 content-block 数组
    （[{type:'text',text:...,index:N}]）的情况，统一转回纯文本。"""
    if isinstance(query, str):
        return query.strip()
    if isinstance(query, list):
        parts: List[str] = []
        for item in query:
            if isinstance(item, dict):
                parts.append(str(item.get("text", "")))
            elif isinstance(item, str):
                parts.append(item)
        return " ".join(p for p in parts if p).strip()
    if isinstance(query, dict):
        return str(query.get("text", query)).strip()
    return str(query or "").strip()


def _tokenize(text: str) -> List[str]:
    """分词（BM25 词项）：优先 jieba 精确+搜索引擎模式；未安装时降级为单字+英文词。"""
    if not text:
        return []
    text_l = text.lower()
    try:
        import jieba
    except ImportError:
        return [t for t in _TOKEN_RE.findall(text_l) if t not in _STOP_CHARS]
    tokens: List[str] = []
    for tok in jieba.cut_for_search(text_l):
        tok = tok.strip()
        if not tok or tok in _STOP_CHARS:
            continue
        # 只保留中文词（1~N字）或英文数字整词，滤掉标点/符号
        if re.fullmatch(r"[\u4e00-\u9fff]+|[a-zA-Z0-9]+", tok):
            tokens.append(tok)
    return tokens


def _dedupe_by_source(results: List[Dict], max_per_file: int = 2) -> List[Dict]:
    """来源去重：同一 source_file 最多保留 max_per_file 条（保持原排序）。"""
    seen: Dict[str, int] = {}
    out: List[Dict] = []
    for r in results:
        key = r.get("source_file") or r.get("course_id") or "unknown"
        if seen.get(key, 0) < max_per_file:
            out.append(r)
            seen[key] = seen.get(key, 0) + 1
    return out


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
        # 本进程写入标记：add/delete 后置 True；search 对账优先看它，
        # 避免"删N加N后 count 不变"导致 BM25 与库不一致（跨进程写入靠 count 兜底）
        self._dirty: bool = False
        self.refresh_corpus()

    # ------------------------------------------------------------------
    # 语料维护
    # ------------------------------------------------------------------
    def refresh_corpus(self) -> None:
        """全量加载当前向量库内容，重建 BM25 索引（刷新失败保留旧索引继续服务）。"""
        try:
            data = self._collection.get(include=["documents", "metadatas"])
        except Exception as e:
            log.warning("语料刷新失败，保留旧索引继续服务: %s", e)
            return
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
    def add_chunks(
        self,
        texts: List[str],
        metadatas: List[Dict],
        embed_texts: Optional[List[str]] = None,
    ) -> int:
        """向量化并写入 Chroma；返回写入条数。

        embed_texts 用于"标题注入"：向量输入 = 标题前缀 + 正文，Chroma 存正文原文。
        """
        if not texts:
            return 0
        ids = [str(uuid.uuid4()) for _ in texts]
        embeddings = self.embeddings.embed_documents(
            embed_texts if embed_texts is not None else texts
        )
        self._collection.add(
            ids=ids,
            embeddings=embeddings,
            documents=texts,
            metadatas=metadatas,
        )
        self.refresh_corpus()
        self._dirty = False
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
        """解析讲义文件（PDF/DOCX/TXT/MD），父子分块后入库。返回统计信息。

        覆盖式去重：按 course_id + 文件内容hash 查重，重复上传先删旧切片再写新。
        """
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
            # 标准库解析 docx（word/document.xml 提取文本），无额外依赖
            try:
                import html as _html
                import zipfile
                from xml.etree import ElementTree as ET

                with zipfile.ZipFile(file_path) as zf:
                    xml_bytes = zf.read("word/document.xml")
                root = ET.fromstring(xml_bytes)
                ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
                parts: List[str] = []
                for para in root.iter("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}p"):
                    texts = [
                        t.text or ""
                        for t in para.iter("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t")
                    ]
                    parts.append("".join(texts))
                text = _html.unescape("\n".join(parts))
            except Exception as e:
                log.warning("docx 解析失败: %s", e)
                text = ""
            pages = [(1, text)] if text.strip() else []
        elif ext in (".txt", ".md", ".text"):
            with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                text = f.read()
            pages = [(1, text)] if text.strip() else []
        else:
            raise ValueError(f"不支持的讲义格式: {ext}")

        if not pages:
            return {"status": "empty", "chunks": 0, "pages": 0}

        file_hash = self._file_hash(file_path)
        old = self._collection.get(where={"$and": [{"course_id": str(course_id)}, {"file_hash": file_hash}]})
        old_ids = old.get("ids", []) if old else []
        if old_ids:
            self._collection.delete(ids=old_ids)
            self._dirty = True  # 本进程已改库：删N加N时 count 可能不变，靠 dirty 触发刷新
            log.info("讲义重复入库，已删除旧切片 {} 条: course_id={}, file_hash={}",
                     len(old_ids), course_id, file_hash[:8])

        parent_splitter = RecursiveCharacterTextSplitter(
            chunk_size=settings.parent_chunk_size,
            chunk_overlap=settings.parent_chunk_overlap,
            separators=["\n\n", "\n", "。", "！", "？", "；", " ", ""],
        )
        child_splitter = RecursiveCharacterTextSplitter(
            chunk_size=settings.child_chunk_size,
            chunk_overlap=settings.child_chunk_overlap,
            separators=["\n\n", "\n", "。", "！", "？", "；", " ", ""],
        )

        texts: List[str] = []
        metadatas: List[Dict] = []
        for page_num, page_text in pages:
            for parent_text in parent_splitter.split_text(page_text):
                parent_text = parent_text.strip()
                if not parent_text:
                    continue
                parent_id = str(uuid.uuid4())
                for child in child_splitter.split_text(parent_text):
                    child = child.strip()
                    if not child:
                        continue
                    texts.append(child)
                    metadatas.append(
                        {
                            "course_id": str(course_id),
                            "course_name": course_name,
                            "source_file": source_file,
                            "page_num": page_num,
                            "chunk_text": child[:500],
                            "file_hash": file_hash,
                            "parent_block_id": parent_id,
                            "parent_text": parent_text,
                        }
                    )

        # 标题注入：向量输入带"课程名｜文件名"前缀，Chroma 存正文原文
        embed_texts = [
            f"{course_name}｜{source_file}\n第{page_num}页\n{child}"
            for child, page_num in zip(texts, [m["page_num"] for m in metadatas])
        ]
        added = self.add_chunks(texts, metadatas, embed_texts=embed_texts)
        return {"status": "ok", "chunks": added, "pages": len(pages)}

    # ------------------------------------------------------------------
    # 精排（rerank）
    # ------------------------------------------------------------------
    def _rerank(self, query: str, results: List[Dict], top_n: Optional[int] = None) -> List[Dict]:
        """百炼 qwen3-rerank 精排：对粗排候选重打分（交叉编码），失败自动降级为粗排结果。"""
        if not results:
            return results
        top_n = top_n or settings.rag_top_k
        docs = [r["text"] for r in results]
        url = f"{settings.rerank_base_url.rstrip('/')}/services/rerank/text-rerank/text-rerank"
        try:
            import httpx

            resp = httpx.post(
                url,
                headers={
                    "Authorization": f"Bearer {settings.dashscope_api_key}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": settings.rerank_model,
                    "input": {"query": query, "documents": docs},
                    "parameters": {"top_n": len(docs)},
                },
                timeout=settings.rerank_timeout,
            )
            resp.raise_for_status()
            data = resp.json()
            # 兼容响应格式：新版百炼返回 {"output":{"results":[...]}}，老协议为顶层 results
            items = (data.get("output") or {}).get("results") or data.get("results") or []
            if not items:
                return results[:top_n]
            # results 按 relevance_score 降序，index 指向原 docs 下标
            ordered = sorted(items, key=lambda x: x.get("relevance_score", 0.0), reverse=True)
            ranked = [
                results[i.get("index", 0)]
                for i in ordered
                if isinstance(i.get("index"), int) and 0 <= i.get("index", 0) < len(results)
            ]
            for i in ordered:
                idx = i.get("index")
                if isinstance(idx, int) and 0 <= idx < len(results):
                    results[idx]["rerank_score"] = round(i.get("relevance_score", 0.0), 4)
            return ranked[:top_n]
        except Exception as e:
            log.warning("rerank调用失败，保留RRF粗排: %s", e)
            return results[:top_n]

    # ------------------------------------------------------------------
    # 检索
    # ------------------------------------------------------------------
    def search(
        self,
        query: str,
        top_k: Optional[int] = None,
        rerank: Optional[bool] = None,
    ) -> List[Dict]:
        """混合检索：向量召回 + BM25 召回 → RRF 融合 → rerank 精排 → 来源去重 → TopN。

        rerank: None=跟随 settings.rag_rerank；True/False=强制开/关（评测 rerank 对比用）。
        """
        # 入口防御：非字符串/空查询直接返回空，避免 embedding 接口 400
        query = _coerce_query_text(query)
        if not query:
            return []
        top_k = top_k or settings.rag_top_k
        use_rerank = settings.rag_rerank if rerank is None else bool(rerank)
        # 语料对账：本进程 dirty 或 跨进程 count 变化 → 刷新 BM25
        if self._dirty or self._collection.count() != len(self._corpus_ids):
            self.refresh_corpus()
            self._dirty = False
        if not self._corpus_ids:
            return []

        t0 = time.perf_counter()

        # 1) 向量语义召回（embedding 失败 → 降级 BM25-only，不炸对话）
        vec_ids: List[str] = []
        vec_docs: List[str] = []
        vec_meta: List[dict] = []
        try:
            q_vec = self.embeddings.embed_query(query)
            vec_res = self._collection.query(
                query_embeddings=[q_vec],
                n_results=min(top_k * 3, len(self._corpus_ids)),
                include=["documents", "metadatas", "distances"],
            )
            _ids: List[str] = vec_res["ids"][0]
            _docs: List[str] = vec_res["documents"][0]
            _meta: List[dict] = vec_res["metadatas"][0]
            _dists: List[float] = vec_res["distances"][0]
            # 相似度阈值过滤：距离大于 rag_max_distance 的低相关片段不参与 RRF；
            # 过滤后为空则退回原始召回，避免检索失效
            filtered = [
                (cid, d, m)
                for cid, d, m, dist in zip(_ids, _docs, _meta, _dists)
                if dist <= settings.rag_max_distance
            ]
            if not filtered:
                filtered = list(zip(_ids, _docs, _meta))
            vec_ids = [x[0] for x in filtered]
            vec_docs = [x[1] for x in filtered]
            vec_meta = [x[2] for x in filtered]
        except Exception as e:
            log.warning("embedding调用失败，降级BM25-only: %s", e)

        # 2) BM25 关键词召回（jieba+停用词；分数>0 才算命中）
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

        # 3) RRF 融合（候选放大 top_k*2，留给 rerank 精排）
        k = settings.rrf_k
        rrf_score: Dict[str, float] = {}
        for ids in (vec_ids, bm25_ids):
            for rank, cid in enumerate(ids, start=1):
                rrf_score[cid] = rrf_score.get(cid, 0.0) + 1.0 / (k + rank)

        doc_map = {cid: (d, m) for cid, d, m in zip(vec_ids, vec_docs, vec_meta)}
        for cid, d, m in zip(bm25_ids, bm25_docs, bm25_meta):
            doc_map.setdefault(cid, (d, m))

        merged = sorted(rrf_score.items(), key=lambda x: x[1], reverse=True)[: top_k * 2]
        results: List[Dict] = []
        for cid, score in merged:
            doc, meta = doc_map[cid]
            results.append(
                {
                    "id": cid,
                    "text": meta.get("parent_text") or doc,  # 命中小块 → 回传所属大块
                    "score": round(score, 4),
                    "course_id": meta.get("course_id"),
                    "course_name": meta.get("course_name"),
                    "source_file": meta.get("source_file"),
                    "page_num": meta.get("page_num"),
                    "parent_block_id": meta.get("parent_block_id"),
                }
            )

        # 父子归并：同一大块（parent_block_id）只保留分数最高的小块，避免重复回传
        if any(r.get("parent_block_id") for r in results):
            best: Dict[str, Dict] = {}
            for r in results:
                key = r.get("parent_block_id") or r["id"]
                if key not in best or r["score"] > best[key]["score"]:
                    best[key] = r
            results = sorted(best.values(), key=lambda x: x["score"], reverse=True)

        # 4) rerank 精排（仅真实 Key 时启用；失败内部降级为粗排）
        if use_rerank and settings.llm_enabled:
            results = self._rerank(query, results)

        # 5) 来源去重：同一讲义最多 2 条，保证回答/卡片多样性
        results = _dedupe_by_source(results, max_per_file=2)[:top_k]

        log.info(
            "search query=%r top_k=%s 向量命中=%s BM25命中=%s 融合候选=%s 返回=%s rerank=%s 耗时=%.2fs",
            query, top_k, len(vec_ids), len(bm25_ids), len(rrf_score), len(results),
            use_rerank, time.perf_counter() - t0,
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
