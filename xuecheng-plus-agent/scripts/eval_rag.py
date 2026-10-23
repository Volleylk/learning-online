"""RAG 检索质量评估：Recall@K（贯穿评估脚本）。

用法（在项目根执行，需先入库测试讲义）：
    venv\\Scripts\\python scripts\\eval_rag.py            # 全量评估
    venv\\Scripts\\python scripts\\eval_rag.py --topk 3   # 自定义 K
    venv\\Scripts\\python scripts\\eval_rag.py --query "给教师的建议是谁写的"  # 单查

指标口径：
- Recall@K：每个测试问题在 TopK 结果中是否召回预期关键词（任意一个命中即算召回），
  命中问题数 / 问题总数。K=1 看第一名，K=5 看整体召回。
- 同时输出每个问题 TopK 命中的课程名/文件名，便于核对父分块、rerank 效果。
"""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

# 保证能 import app.*（脚本放在 scripts/ 下）
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s: %(message)s")

from app.config import settings  # noqa: E402
from app.rag import RAGService  # noqa: E402

# 演示测试集：期望关键词与该问题应命中的讲义内容对应
TEST_SET = [
    {
        "q": "给教师的建议是谁写的",
        "kw": ["苏霍姆林斯基", "给教师的建议", "作者"],
        "note": "书名题：向量语义 + BM25 书名命中",
    },
    {
        "q": "什么是微服务架构",
        "kw": ["微服务", "架构", "服务"],
        "note": "概念题：父子分块回传大块",
    },
    {
        "q": "Spring Cloud 的核心组件有哪些",
        "kw": ["Spring Cloud", "网关", "注册中心", "Nacos"],
        "note": "组件枚举题",
    },
    {
        "q": "面向对象三大特性是什么",
        "kw": ["封装", "继承", "多态", "面向对象"],
        "note": "Java 基础题",
    },
]


def evaluate(rag: RAGService, top_k: int, only_query: str | None = None) -> None:
    cases = [t for t in TEST_SET if only_query is None or only_query in t["q"]]
    if not cases:
        print(f"[评估] 测试集为空（only_query 未命中），现有 {len(TEST_SET)} 条")
        return

    hits = 0
    print(f"{'=' * 70}")
    print(f"RAG Recall@{top_k} 评估 | 问题数={len(cases)} | rerank={'on' if (settings.rag_rerank and settings.llm_enabled) else 'off'}")
    print(f"{'=' * 70}")
    for case in cases:
        results = rag.search(case["q"], top_k=top_k)
        texts = "\n".join(r["text"] for r in results)
        hit_kw = [kw for kw in case["kw"] if kw in texts]
        ok = len(hit_kw) > 0
        hits += 1 if ok else 0
        print(f"[{'✓' if ok else '✗'}] {case['q']}  ({case['note']})")
        print(f"    期望关键词: {case['kw']} → 命中: {hit_kw or '无'}")
        for r in results[:3]:
            print(f"      - [{r['course_name']}·{r['source_file']}·P{r['page_num']}] score={r['score']}"
                  f"{' rerank=' + str(r.get('rerank_score')) if 'rerank_score' in r else ''}  {r['text'][:60]}…")
        print()
    recall = hits / len(cases)
    print(f"Recall@{top_k} = {hits}/{len(cases)} = {recall:.2%}")


def main() -> None:
    parser = argparse.ArgumentParser(description="RAG Recall@K 评估")
    parser.add_argument("--topk", type=int, default=5, help="K 值（默认 5）")
    parser.add_argument("--query", type=str, default=None, help="只评估含该文本的单条问题")
    args = parser.parse_args()

    rag = RAGService()  # embeddings 由 lazy init（见 llm.py）或直接配置注入
    try:
        from app.llm import create_embeddings

        rag.embeddings = create_embeddings()
    except Exception as e:
        print(f"[评估] 警告：无法加载 embedding（{e}），将跳过向量召回（仅 BM25）")
    evaluate(rag, top_k=args.topk, only_query=args.query)


if __name__ == "__main__":
    main()
