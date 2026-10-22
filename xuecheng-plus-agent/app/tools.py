"""Agent 工具集模块。

五个标准化工具（LangChain 1.x @tool 装饰器风格）：
1. course_lecture_search  课程讲义检索（RAG）
2. course_info_query      课程信息查询（调用学成在线 Java 后端公开课程列表接口）
3. my_courses_query       当前登录用户已选课程（JWT 透传）
4. user_preference_read   用户长期偏好读取
5. user_preference_update 用户长期偏好更新

请求级上下文（contextvar）：
- _user_ctx / _token_ctx 由 agent 调度入口在每次请求开始时写入，
  工具函数内部读取，避免把 user_id / JWT 参数透传到工具构建期。
"""
from __future__ import annotations

import contextvars
import json
import logging
import re
from typing import Any, Dict, List

import httpx
from langchain_core.tools import BaseTool, tool

from app.config import settings
from app.rag import RAGService

log = logging.getLogger(__name__)

# 请求级上下文（async 请求各自隔离，并发安全）
_user_ctx: contextvars.ContextVar[str] = contextvars.ContextVar("agent_user_id", default="")
_token_ctx: contextvars.ContextVar[str] = contextvars.ContextVar("agent_jwt", default="")

# 共享 HTTP 客户端（连接池复用；httpx.Client 线程安全）
_http = httpx.Client(timeout=8, follow_redirects=True)


def set_agent_user(user_id: str) -> None:
    """请求入口写入当前用户 ID（偏好工具使用）。"""
    _user_ctx.set(user_id)


def set_my_courses_token(token: str) -> None:
    """请求入口写入当前 JWT（我的课程工具透传 Java）。"""
    _token_ctx.set(token)


def _build_course_url(course: Dict[str, Any]) -> str:
    cid = str(course.get("id", ""))
    template = settings.java_course_detail_url_template
    # 模板必须是完整 URL（http/https），相对路径会导致前端跳转到错误地址
    if template and re.match(r"^https?://", template):
        return template.format(id=cid)
    if course.get("url"):
        return course["url"]
    if settings.java_base_url:
        return f"{settings.java_base_url}/course/detail/{cid}"
    return f"http://192.168.101.65:8601/course/detail/{cid}"


def _normalize_course(course: Dict[str, Any]) -> Dict[str, Any]:
    item = {
        "id": str(course.get("id", "")),
        "name": course.get("name", course.get("courseName", "")),
        "intro": course.get("intro", course.get("brief", course.get("description", ""))),
        "cover": course.get("cover", course.get("pic", "")),
        "url": _build_course_url(course),
    }
    if course.get("price") is not None:
        item["price"] = course["price"]
    return item


def _query_java_courses(keyword: str) -> List[Dict[str, Any]]:
    """查询已发布课程：调用 Java 后端免权限接口 /open/course/agent/list。

    只返回后端真实课程数据；接口不可用或没有匹配时返回空列表。
    """
    if not settings.java_base_url:
        return []
    url = f"{settings.java_base_url}{settings.course_list_path}"
    try:
        resp = _http.get(url, params={"keyword": keyword})
        resp.raise_for_status()
        data = resp.json()
        items = data.get("data", data) if isinstance(data, dict) else data
        if isinstance(items, list):
            return [_normalize_course(c) for c in items]
        return []
    except Exception as e:
        log.warning("Java课程接口调用失败: %s", e)
        return []


# ---------------------------------------------------------------------------
# 工具构造器（@tool 装饰器风格；工具名 = 函数名，description = docstring）
# ---------------------------------------------------------------------------

def make_lecture_search_tool(rag: RAGService) -> BaseTool:
    @tool
    def course_lecture_search(query: str) -> str:
        """检索全站公开课程讲义的知识库。当用户询问课程知识点、概念、讲义内容时调用。
输入：用户的问题/知识点关键词。输出：相关讲义片段（含课程名、文件名、页码）。"""
        results = rag.search(query)
        if not results:
            return json.dumps({"found": False, "message": "未检索到相关讲义片段"}, ensure_ascii=False)
        items = [
            {
                "course_id": r["course_id"],
                "course_name": r["course_name"],
                "source_file": r["source_file"],
                "page": r["page_num"],
                "text": r["text"],
            }
            for r in results
        ]
        return json.dumps({"found": True, "items": items}, ensure_ascii=False)

    return course_lecture_search


def make_course_query_tool() -> BaseTool:
    @tool
    def course_info_query(keyword: str) -> str:
        """查询学成在线公开课程信息用于课程推荐。当用户想找课、要推荐课程、询问学什么/入门/进阶方向时调用。
输入：课程关键词。输出：匹配的课程列表（含课程ID、名称、简介、封面、详情页URL）。"""
        courses = _query_java_courses(keyword)
        log.info("course_info_query keyword=%r 命中=%s -> %s", keyword, len(courses), [(c["id"], c["name"]) for c in courses[:5]])
        if not courses:
            return json.dumps({"found": False, "message": "未匹配到相关课程"}, ensure_ascii=False)
        return json.dumps({"found": True, "courses": courses}, ensure_ascii=False)

    return course_info_query


def make_my_courses_tool() -> BaseTool:
    """查询当前登录用户已选课程（learning-api /mycoursetable，JWT 透传）。

    token 由 set_my_courses_token() 按请求写入（contextvar），空或已过期时返回明确提示。
    """

    @tool
    def my_courses_query(input: str = "") -> str:
        """查询当前登录用户已选/在学的课程列表（含课程名、有效期）。
当用户问'我在学什么课''我选了哪些课''我的课程有哪些'时调用。输入任意内容即可。"""
        token = _token_ctx.get()
        if not settings.java_base_url:
            return json.dumps({"found": False, "message": "Java网关未配置"}, ensure_ascii=False)
        if not token:
            return json.dumps({"found": False, "message": "登录态缺失，请重新登录门户"}, ensure_ascii=False)
        url = f"{settings.java_base_url}/learning/mycoursetable"
        try:
            resp = _http.get(
                url,
                params={"page": 1, "size": 20},
                headers={"Authorization": f"Bearer {token}"},
            )
            if resp.status_code in (401, 403):
                return json.dumps({"found": False, "message": "登录已过期，请重新登录门户"}, ensure_ascii=False)
            resp.raise_for_status()
            data = resp.json()
            # PageResult 兼容：records / items / list / data
            items = None
            if isinstance(data, dict):
                items = data.get("records") or data.get("items") or data.get("list") or data.get("data")
            if items is None and isinstance(data, list):
                items = data
            courses = []
            for c in items or []:
                valid_end = str(c.get("validtimeEnd", "") or "")
                courses.append(
                    {
                        "course_id": str(c.get("courseId", "")),
                        "course_name": c.get("courseName", "") or "",
                        "course_type": c.get("courseType", "") or "",
                        "valid_until": valid_end[:10] if valid_end else "",
                    }
                )
            if not courses:
                return json.dumps({"found": False, "message": "当前还没有已选课程"}, ensure_ascii=False)
            return json.dumps({"found": True, "courses": courses}, ensure_ascii=False)
        except Exception as e:
            log.warning("我的课程接口调用失败: %s", e)
            return json.dumps({"found": False, "message": "查询我的课程失败"}, ensure_ascii=False)

    return my_courses_query


def make_preference_tools() -> List[BaseTool]:
    """偏好工具：user_id 由 set_agent_user() 按请求写入（contextvar）。"""

    @tool
    def user_preference_read(input: str = "") -> str:
        """读取当前用户的长期学习偏好（学习阶段、解释风格、关注方向）。需要个性化回答时先调用本工具。输入任意内容即可。"""
        user_id = _user_ctx.get()
        return pref_handler(user_id, "__read__", input)

    @tool
    def user_preference_update(instruction: str) -> str:
        """保存用户主动告知的长期学习偏好，如'我是零基础''我喜欢通俗解释''我最近在学Java'。
输入：用户原话。输出：更新结果。"""
        user_id = _user_ctx.get()
        return pref_handler(user_id, "__update__", instruction)

    return [user_preference_read, user_preference_update]


def pref_handler(user_id: str, action: str, instruction: str) -> str:
    """偏好工具的实际处理逻辑（user_id 由请求上下文注入）。"""
    import app.db as db

    if action == "__read__":
        prefs = db.get_preferences(user_id)
        return json.dumps(prefs, ensure_ascii=False, default=str)

    # 解析原话中的偏好信息（启发式）
    updates: Dict[str, str] = {}
    stage_kw = {
        "零基础": "零基础", "入门": "入门", "初级": "入门",
        "进阶": "进阶", "中级": "进阶", "提高": "进阶",
        "求职": "求职", "面试": "求职", "工作": "求职",
    }
    for kw, val in stage_kw.items():
        if kw in instruction:
            updates["learning_stage"] = val
            break
    style_kw = {
        "通俗": "通俗易懂", "简单": "通俗易懂", "大白话": "通俗易懂",
        "严谨": "严谨详细", "详细": "严谨详细", "例子": "多举例",
    }
    for kw, val in style_kw.items():
        if kw in instruction:
            updates["explanation_style"] = val
            break
    focus_kw = re.findall(r"(?:在学|学习|关注|想学|研究)[^，。；!?]{0,12}", instruction)
    if focus_kw:
        updates["focus_directions"] = "、".join(focus_kw)

    if not updates:
        return json.dumps({"updated": False, "message": "未识别到可保存的偏好信息"}, ensure_ascii=False)
    current = db.upsert_preferences(user_id, **updates)
    return json.dumps({"updated": True, "preferences": current}, ensure_ascii=False, default=str)
