"""讲义上传/解析状态转发接口。

门户前端（机构端）直接调用 AI 服务的 /api/lecture/* 接口，
由本路由统一转发到 Java media-api（走网关 63010），避免浏览器跨域与鉴权差异。
"""
from __future__ import annotations

import httpx
from fastapi import APIRouter, Depends, File, Form, UploadFile
from fastapi.responses import JSONResponse

from app.auth import get_current_credentials, get_current_user
from app.config import settings

router = APIRouter(prefix="/api", tags=["lecture"])


def _media_url(path: str) -> str:
    base = (settings.java_base_url or "http://localhost:63010").rstrip("/")
    return f"{base}/media{path}"


@router.post("/lecture/upload")
async def upload_lecture(
    file: UploadFile = File(...),
    courseId: str = Form(...),
    courseName: str = Form(""),
    token: str = Depends(get_current_credentials),
    user_id: str = Depends(get_current_user),
):
    """转发 Java media-api 上传接口（multipart），触发 AI 解析。"""
    try:
        content = await file.read()
        files = {
            "file": (
                file.filename or "lecture.pdf",
                content,
                file.content_type or "application/octet-stream",
            ),
            "courseId": (None, courseId),
            "courseName": (None, courseName),
        }
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        async with httpx.AsyncClient(timeout=120) as client:
            resp = await client.post(
                _media_url("/api/lecture/upload"),
                files=files,
                headers=headers,
            )
        try:
            body = resp.json()
        except Exception:
            body = {"code": resp.status_code, "msg": resp.text[:200], "result": None}
        return JSONResponse(status_code=resp.status_code, content=body)
    except Exception as e:
        return JSONResponse(
            status_code=502,
            content={"code": 502, "msg": f"转发上传失败: {e}", "result": None},
        )


@router.get("/lecture/parse-status/{file_id}")
def parse_status(
    file_id: str,
    user_id: str = Depends(get_current_user),
):
    """查询单条讲义解析状态。"""
    try:
        resp = httpx.get(_media_url(f"/api/lecture/parse-status/{file_id}"), timeout=10)
        try:
            body = resp.json()
        except Exception:
            body = {"code": resp.status_code, "msg": resp.text[:200], "result": None}
        return JSONResponse(status_code=resp.status_code, content=body)
    except Exception as e:
        return JSONResponse(
            status_code=502,
            content={"code": 502, "msg": f"转发查询失败: {e}", "result": None},
        )


@router.get("/lecture/parse-status/list")
def parse_status_list(
    courseId: str = "",
    user_id: str = Depends(get_current_user),
):
    """按课程查询讲义解析状态列表（课程讲义管理页用）。"""
    try:
        resp = httpx.get(
            _media_url("/api/lecture/parse-status/list"),
            params={"courseId": courseId},
            timeout=10,
        )
        try:
            body = resp.json()
        except Exception:
            body = {"code": resp.status_code, "msg": resp.text[:200], "result": None}
        return JSONResponse(status_code=resp.status_code, content=body)
    except Exception as e:
        return JSONResponse(
            status_code=502,
            content={"code": 502, "msg": f"转发查询失败: {e}", "result": None},
        )
