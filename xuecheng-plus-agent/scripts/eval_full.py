"""RAG/Agent 完整评测：检索指标 + 生成质量（LLM-as-Judge）→ 自动生成评测报告。

用法（在项目根执行，需向量库已入库 6 份演示讲义）：
    venv\\Scripts\\python scripts\\eval_full.py                     # 全量 48 题
    venv\\Scripts\\python scripts\\eval_full.py --limit 6           # 只跑前 6 题（验证流程）
    venv\\Scripts\\python scripts\\eval_full.py --lecture 130       # 只跑某讲义（course_id）
    venv\\Scripts\\python scripts\\eval_full.py --no-judge          # 只跑检索指标，跳过生成质量

指标口径：
- 检索层（每题对 rag.search 独立计算，rerank on/off 各一份）：
  Recall@K    TopK 内是否命中期望关键词（任一命中即算）
  Precision@K TopK 中命中结果占比
  MRR         第一个命中结果的倒数排名
  跨课误召回   TopK 中 expected_file 之外的结果数（检索是否串课）
- 生成层（完整 Agent 工具链路回答，LLM-as-Judge 打分）：
  faithfulness      回答是否忠于检索上下文、有无编造（0~1）
  answer_relevancy  回答是否切题（0~1）
  裁判模型 qwen3.8-max（失败自动降级 qwen-plus），两次独立调用取均值，失败标注 None
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import re
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s: %(message)s")

from app.agent import AgentCore  # noqa: E402
from app.config import settings  # noqa: E402
from app.llm import create_chat_model, create_embeddings  # noqa: E402
from app.memory import SessionMemory  # noqa: E402
from app.rag import RAGService  # noqa: E402
from app.tools import make_preference_tools  # noqa: E402

TOPK = 5
JUDGE_MODEL = "qwen3.7-max-2026-06-08"  # 裁判模型（3.8 系列免费额度耗尽；3.7 快照版配额独立，足够打分任务）
JUDGE_FALLBACK = "qwen-plus"      # 裁判失败降级
UID = "52"
SID_PREFIX = "eval-"
GEN_FILE = ROOT / "data" / "eval_generations.json"  # 生成结果缓存（断点续跑）


def load_cache() -> Dict[str, Any]:
    """读取生成结果缓存；裁判模型变了则缓存失效。"""
    if GEN_FILE.exists():
        try:
            c = json.loads(GEN_FILE.read_text(encoding="utf-8"))
            if c.get("judge_model") == JUDGE_MODEL:
                return c
        except Exception:
            pass
    return {"judge_model": JUDGE_MODEL, "top_k": TOPK, "cases": {}}


def save_cache(cache: Dict[str, Any]) -> None:
    GEN_FILE.write_text(json.dumps(cache, ensure_ascii=False, indent=1), encoding="utf-8")


def make_token() -> str:
    import time as _t
    import jwt as _jwt
    payload = {
        "sub": UID,
        "user_name": json.dumps({"id": UID, "username": "stu1", "name": "学生一",
                                 "nickname": "阿明", "utype": "101002"}, ensure_ascii=False),
        "aud": "xuecheng-plus",
        "iat": int(_t.time()),
        "exp": int(_t.time()) + 7200,
    }
    return _jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def hit_kw(case: Dict, texts: str) -> List[str]:
    return [kw for kw in case["kw"] if kw in texts]


# ---------------------------------------------------------------------------
# 检索层指标
# ---------------------------------------------------------------------------
def retrieval_eval(rag: RAGService, cases: List[Dict]) -> Dict[str, Any]:
    rows = []
    for case in cases:
        row = {"id": case["id"], "q": case["q"], "course_id": case["course_id"],
               "expected_file": case["expected_file"]}
        for mode in ("off", "on"):
            results = rag.search(case["q"], top_k=TOPK, rerank=(mode == "on"))
            texts = "\n".join(r["text"] for r in results)
            files = [r["source_file"] for r in results]
            kw_hits = hit_kw(case, texts)
            rank_first = next((i + 1 for i, r in enumerate(results)
                               if any(kw in r["text"] for kw in case["kw"])), None)
            row[f"recall_{mode}"] = 1 if kw_hits else 0
            row[f"prec_{mode}"] = sum(1 for r in results if any(kw in r["text"] for kw in case["kw"])) / len(results)
            row[f"mrr_{mode}"] = 1.0 / rank_first if rank_first else 0.0
            row[f"cross_{mode}"] = sum(1 for f in files if f != case["expected_file"])
        rows.append(row)
    return {"rows": rows, "top_k": TOPK}


# ---------------------------------------------------------------------------
# 生成层：完整 Agent 工具链路
# ---------------------------------------------------------------------------
async def agent_answer(core: AgentCore, case: Dict, token: str) -> Dict[str, Any]:
    mem = SessionMemory(user_id=UID, session_id=SID_PREFIX + str(case["id"]), llm=core.llm)
    answer_parts: List[str] = []
    sources: List[Dict] = []
    tools: List[str] = []
    try:
        async for ev in core.stream_run(case["q"], UID, mem, token=token):
            t = ev.get("type")
            if t == "answer":
                answer_parts.append(ev.get("delta", ""))
            elif t == "thinking" and "正在调用工具" in ev.get("text", ""):
                tools.append(ev["text"])
            elif t == "done":
                sources = ev.get("sources") or []
    except Exception as e:
        return {"answer": f"[Agent执行异常] {e}", "sources": [], "tools": tools}
    return {"answer": "".join(answer_parts).strip(), "sources": sources, "tools": tools}


def _extract_text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(str(c.get("text", "")) if isinstance(c, dict) else str(c) for c in content)
    return str(content or "")


def _parse_score(text: str) -> Optional[Dict[str, float]]:
    m = re.search(r"\{[^{}]*\}", text)
    if not m:
        return None
    try:
        d = json.loads(m.group(0))
        f, r = d.get("faithfulness"), d.get("answer_relevancy")
        if isinstance(f, (int, float)) and isinstance(r, (int, float)):
            return {"faithfulness": float(f), "answer_relevancy": float(r)}
    except Exception:
        pass
    return None


def judge_once(llm, question: str, context: str, answer: str) -> Optional[Dict[str, float]]:
    prompt = (
        "你是严谨的 RAG 回答质量裁判。给定【问题】【检索到的讲义上下文】【AI的回答】，"
        "分别给两个指标打分（0~1 的小数，一位小数）：\n"
        "1. faithfulness 忠实度：回答是否完全基于上下文、有无编造上下文没有的信息"
        "（0=严重编造，1=完全忠于上下文）\n"
        "2. answer_relevancy 相关性：回答是否切题、是否直接回答了问题"
        "（0=完全跑题，1=完全切题）\n"
        "只输出一个 JSON 对象，不要其他文字：{\"faithfulness\": 0.0, \"answer_relevancy\": 0.0}\n\n"
        f"【问题】{question}\n"
        f"【上下文】{context[:3000]}\n"
        f"【回答】{answer[:2000]}"
    )
    resp = llm.invoke(prompt)
    return _parse_score(_extract_text(resp.content))


def build_judge_model(model: str):
    from langchain_openai import ChatOpenAI
    return ChatOpenAI(
        model=model,
        api_key=settings.dashscope_api_key,
        base_url=settings.llm_base_url,
        temperature=0,
        timeout=60,
        max_retries=1,
    )


def judge_quality(question: str, context: str, answer: str) -> Dict[str, Any]:
    """qwen3.8-max 裁判，失败降级 qwen-plus；两次取均值。"""
    if not answer or "[Agent执行异常]" in answer:
        return {"faithfulness": None, "answer_relevancy": None, "note": "无有效回答"}
    model = build_judge_model(JUDGE_MODEL)
    scores = []
    for _ in range(2):
        try:
            s = judge_once(model, question, context, answer)
            if s:
                scores.append(s)
        except Exception as e:
            log = logging.getLogger("eval")
            log.warning("裁判 %s 调用失败(%s)，降级 %s", JUDGE_MODEL, e, JUDGE_FALLBACK)
            try:
                fb = build_judge_model(JUDGE_FALLBACK)
                s = judge_once(fb, question, context, answer)
                if s:
                    scores.append(s)
            except Exception as e2:
                log.warning("降级裁判也失败: %s", e2)
    if not scores:
        return {"faithfulness": None, "answer_relevancy": None, "note": "裁判调用全部失败"}
    return {
        "faithfulness": round(sum(s["faithfulness"] for s in scores) / len(scores), 3),
        "answer_relevancy": round(sum(s["answer_relevancy"] for s in scores) / len(scores), 3),
        "note": "两次均值",
    }


# ---------------------------------------------------------------------------
# 报告
# ---------------------------------------------------------------------------
def build_report(meta: Dict[str, Any], ret: Dict[str, Any], gen: Dict[str, Any],
                 cases: List[Dict], topk: int) -> str:
    L = []
    L.append("# RAG / Agent 评测报告\n")
    L.append("## 一、评测范围\n")
    L.append(f"- 测试集：`data/eval_set.json`，共 **{len(cases)} 题**，6 份演示讲义 × 8 题\n")
    L.append("- 检索 K 值：Top-5；生成链路：完整 Agent 工具调用（查询改写→混合检索→rerank→回答）\n")
    L.append(f"- 裁判模型：{JUDGE_MODEL}（失败降级 {JUDGE_FALLBACK}），两次调用取均值\n")
    L.append(f"- 评测时间：{meta['time']}\n")

    # 检索指标汇总
    rows = ret["rows"]
    def agg(key):
        vals = [r[key] for r in rows]
        return sum(vals) / len(vals) if vals else 0
    L.append("## 二、检索层指标（rerank on/off 对比）\n")
    L.append("| 指标 | rerank off | rerank on | 说明 |\n|---|---|---|---|\n")
    L.append(f"| Recall@{topk} | {agg('recall_off'):.2%} | {agg('recall_on'):.2%} | 期望关键词命中率 |\n")
    L.append(f"| Precision@{topk} | {agg('prec_off'):.2%} | {agg('prec_on'):.2%} | TopK 中命中结果占比 |\n")
    L.append(f"| MRR | {agg('mrr_off'):.3f} | {agg('mrr_on'):.3f} | 第一个命中结果的倒数排名 |\n")
    L.append(f"| 跨课误召回(题均) | {agg('cross_off'):.2f} | {agg('cross_on'):.2f} | 平均每题召回的非目标讲义结果数 |\n")

    # 生成层汇总
    L.append("\n## 三、生成层指标（LLM-as-Judge）\n")
    by_course = defaultdict(list)
    for g in gen["rows"]:
        by_course[g["course_id"]].append(g)
    L.append("| 课程 | 题数 | faithfulness | answer_relevancy |\n|---|---|---|---|\n")
    for cid in sorted(by_course):
        gs = by_course[cid]
        fs = [g["scores"]["faithfulness"] for g in gs if g["scores"]["faithfulness"] is not None]
        rs = [g["scores"]["answer_relevancy"] for g in gs if g["scores"]["answer_relevancy"] is not None]
        name = next((c["expected_file"] for c in cases if c["course_id"] == cid), str(cid))
        L.append(f"| {name} | {len(gs)} | {sum(fs)/len(fs):.3f} / {len(fs)} | {sum(rs)/len(rs):.3f} / {len(rs)} |\n")
    all_f = [g["scores"]["faithfulness"] for g in gen["rows"] if g["scores"]["faithfulness"] is not None]
    all_r = [g["scores"]["answer_relevancy"] for g in gen["rows"] if g["scores"]["answer_relevancy"] is not None]
    L.append(f"| **整体** | {len(gen['rows'])} | **{sum(all_f)/len(all_f):.3f}** / {len(all_f)} | **{sum(all_r)/len(all_r):.3f}** / {len(all_r)} |\n")

    # 逐题明细
    L.append("\n## 四、逐题明细\n")
    L.append("| 题ID | 问题 | Recall(off→on) | MRR(off→on) | 串课(off→on) | faith | rel | 工具调用 |\n|---|---|---|---|---|---|---|---|\n")
    gen_map = {g["id"]: g for g in gen["rows"]}
    for r in rows:
        g = gen_map.get(r["id"])
        f = g["scores"]["faithfulness"] if g else None
        rel = g["scores"]["answer_relevancy"] if g else None
        tools = "、".join(g["tools"]) if g and g["tools"] else "-"
        L.append(f"| {r['id']} | {r['q'][:26]}… | {r['recall_off']}→{r['recall_on']} | "
                 f"{r['mrr_off']:.2f}→{r['mrr_on']:.2f} | {r['cross_off']}→{r['cross_on']} | "
                 f"{f if f is not None else '-'} | {rel if rel is not None else '-'} | {tools} |\n")

    L.append("\n## 五、结论与局限\n")
    L.append("- 结论一（检索）：rerank 对 Recall 的影响、MRR/串课的改善幅度见上表；若 on 显著优于 off，说明 qwen3-rerank 精排对命中顺序与跨课抑制有实际收益。\n")
    L.append("- 结论二（生成）：整体 faithfulness / answer_relevancy 均值反映 Agent 回答对讲义上下文的忠实度与切题度。\n")
    L.append("- 局限：测试集基于 6 份演示讲义人工标注，规模有限；裁判为模型评分（LLM-as-Judge），存在评分偏差可能；`None` 表示该题裁判调用失败未计入。\n")
    L.append("\n## 六、复现\n")
    L.append("```bash\nvenv\\Scripts\\python scripts\\eval_full.py          # 全量\nvenv\\Scripts\\python scripts\\eval_full.py --limit 6   # 前 6 题\n```\n")
    return "".join(L)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
async def run(rag: RAGService, core: AgentCore, cases: List[Dict], no_judge: bool, token: str) -> Dict:
    print(f"[评测] 检索指标（rerank on/off × {len(cases)} 题）…")
    ret = retrieval_eval(rag, cases)
    print(f"[评测] 检索完成：Recall(off)={sum(r['recall_off'] for r in ret['rows'])}/"
          f"{len(cases)}，Recall(on)={sum(r['recall_on'] for r in ret['rows'])}/{len(cases)}")

    gen = {"rows": [], "skipped": 0}
    if no_judge:
        return ret, gen
    cache = load_cache()
    pending = [c for c in cases if str(c["id"]) not in cache["cases"]]
    print(f"[评测] Agent 生成 + 裁判打分（{len(cases)} 题，缓存 {len(cases) - len(pending)} 题复用，"
          f"本次新跑 {len(pending)} 题）…")
    for i, case in enumerate(cases, 1):
        cid = str(case["id"])
        if cid in cache["cases"]:
            gen["skipped"] += 1
            continue
        t0 = time.perf_counter()
        out = await agent_answer(core, case, token)
        context = "\n".join(f"[{s.get('course_name')}·{s.get('source_file')}·P{s.get('page_num')}] {s.get('text', '')}"
                            for s in out["sources"])
        if not context:
            results = rag.search(case["q"], top_k=3, rerank=True)
            context = "\n".join(f"[{r.get('course_name')}·{r.get('source_file')}] {r['text']}" for r in results)
        scores = judge_quality(case["q"], context, out["answer"])
        cache["cases"][cid] = {"id": case["id"], "course_id": case["course_id"],
                               "q": case["q"], "answer": out["answer"], "sources": out["sources"],
                               "tools": out["tools"], "scores": scores}
        save_cache(cache)  # 每完成一题立即落盘，断点可续
        dt = time.perf_counter() - t0
        print(f"  [{i}/{len(cases)}] #{case['id']} {case['q'][:24]}… "
              f"faith={scores['faithfulness']} rel={scores['answer_relevancy']} "
              f"tools={len(out['tools'])} {dt:.0f}s")
    gen["rows"] = [cache["cases"][str(c["id"])] for c in cases if str(c["id"]) in cache["cases"]]
    return ret, gen


def main() -> None:
    parser = argparse.ArgumentParser(description="RAG/Agent 完整评测")
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 题")
    parser.add_argument("--lecture", type=int, default=0, help="只跑某 course_id")
    parser.add_argument("--no-judge", action="store_true", help="跳过生成质量打分")
    args = parser.parse_args()

    data = json.load(open(ROOT / "data" / "eval_set.json", encoding="utf-8"))
    cases: List[Dict] = data["cases"]
    if args.lecture:
        cases = [c for c in cases if c["course_id"] == args.lecture]
    if args.limit:
        cases = cases[: args.limit]
    print(f"[评测] 测试集 {len(cases)} 题 | judge={JUDGE_MODEL} | no_judge={args.no_judge}")

    rag = RAGService(embeddings=create_embeddings())
    llm = create_chat_model()
    core = AgentCore(rag, llm, make_preference_tools())
    token = make_token()

    ret, gen = asyncio.run(run(rag, core, cases, args.no_judge, token))

    if not args.limit and not args.lecture:  # 全量时写报告
        report = build_report({"time": time.strftime("%Y-%m-%d %H:%M:%S")}, ret, gen, cases, TOPK)
        out = ROOT / "docs" / "rag-eval-report.md"
        out.write_text(report, encoding="utf-8")
        print(f"[评测] 报告已生成: {out}")
    else:
        # 部分跑时也给出摘要
        rows = ret["rows"]
        print(f"[摘要] Recall@5 off={sum(r['recall_off'] for r in rows)}/{len(rows)} "
              f"on={sum(r['recall_on'] for r in rows)}/{len(rows)}")
        if gen["rows"]:
            fs = [g["scores"]["faithfulness"] for g in gen["rows"] if g["scores"]["faithfulness"] is not None]
            rs = [g["scores"]["answer_relevancy"] for g in gen["rows"] if g["scores"]["answer_relevancy"] is not None]
            print(f"[摘要] faithfulness={sum(fs)/len(fs):.3f} answer_relevancy={sum(rs)/len(rs):.3f}")


if __name__ == "__main__":
    main()
