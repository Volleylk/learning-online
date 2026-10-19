"""JWT 登录态校验。

AI 服务与 Java 门户共享同一套 JWT 密钥，本地解析 token 获取 user_id，
不调用 Java 远程接口。

兼容两套 JWT：
1. 学成在线门户（Java）签发的 OAuth2 JWT：密钥 mq123（HS256），
   用户身份在 user_name claim（XcUserExt 用户对象的 JSON 字符串），
   与 xuecheng-plus 的 SecurityUtil 反解方式一致。
2. 早期自定义 JWT：sub/user_id/uid/userId 直接携带用户 ID。
"""
from __future__ import annotations

import json
import warnings

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jwt.warnings import InsecureKeyLengthWarning

from app.config import settings

# 门户密钥 mq123 由 xuecheng-plus-auth TokenConfig.SIGNING_KEY 硬编码（5字节），
# 低于 PyJWT 推荐的 32 字节；该密钥由 Java 端决定，无法单方面改长，
# 抑制 InsecureKeyLengthWarning 避免每次请求都向日志刷警告。
warnings.filterwarnings("ignore", category=InsecureKeyLengthWarning)

_bearer = HTTPBearer(auto_error=False)


def decode_token(token: str) -> str:
    """解析 JWT，返回 user_id。

    严格模式：token 缺失 / 过期 / 签名无效 / 无用户ID 一律抛 ValueError，
    由 get_current_user 转成 401 拒绝请求（必须登录才能使用 AI 助手）。
    """
    if not token:
        raise ValueError("缺少JWT token")
    try:
        payload = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
            options={"verify_aud": False},  # 门户JWT带aud=xuecheng-plus，忽略以兼容
        )
    except jwt.PyJWTError as e:  # 过期/签名错误/格式错误 → 拒绝
        print(f"[auth] JWT校验失败: {e}")
        raise ValueError(f"无效的JWT: {e}") from e

    # 兼容早期自定义 JWT：sub/user_id/uid/userId 直接携带用户ID
    for key in ("sub", "user_id", "uid", "userId"):
        value = payload.get(key)
        if value is not None and str(value) != "":
            return str(value)

    # 门户 JWT（学成在线）：user_name = XcUserExt 用户对象的 JSON 字符串
    uname = payload.get("user_name")
    if uname:
        try:
            user = json.loads(uname)
            uid = user.get("id") if isinstance(user, dict) else None
            if uid is not None and str(uid) != "":
                return str(uid)
        except Exception:
            pass  # JSON 解析失败，落到下方统一拒绝
    print("[auth] JWT中未找到用户ID(user_name/sub)")
    raise ValueError("JWT中未找到用户ID")


def get_current_user(
    cred: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> str:
    """FastAPI 依赖：从 Authorization: Bearer <token> 中解析用户ID。"""
    if cred is None or not cred.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="缺少登录凭证，请携带JWT token访问",
        )
    try:
        return decode_token(cred.credentials)
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=str(e),
        ) from e


def get_current_credentials(
    cred: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> str:
    """FastAPI 依赖：返回原始 JWT（透传给 Java 侧调用用户维接口用）。"""
    if cred is None or not cred.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="缺少登录凭证，请携带JWT token访问",
        )
    return cred.credentials


def parse_user_profile(token: str) -> dict:
    """从 JWT 解析用户画像（id/username/name/nickname/utype），供系统提示注入。"""
    profile = {"id": "", "username": "", "name": "", "nickname": "", "utype": ""}
    if not token:
        return profile
    try:
        payload = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
            options={"verify_aud": False},
        )
    except jwt.PyJWTError:
        return profile
    # 早期自定义 JWT：sub/user_id/uid/userId 直接携带用户ID
    for key in ("sub", "user_id", "uid", "userId"):
        v = payload.get(key)
        if v is not None and str(v) != "":
            profile["id"] = str(v)
            break
    # 门户 JWT：user_name = XcUserExt 用户对象的 JSON 字符串
    uname = payload.get("user_name")
    if uname:
        try:
            user = json.loads(uname)
            if isinstance(user, dict):
                for k in ("id", "username", "name", "nickname", "utype"):
                    v = user.get(k)
                    if v is not None:
                        profile[k] = str(v)
        except Exception:
            pass
    return profile


def get_current_user_profile(
    cred: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> dict:
    """FastAPI 依赖：返回完整用户画像（先校验 token 有效性）。"""
    if cred is None or not cred.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="缺少登录凭证，请携带JWT token访问",
        )
    try:
        decode_token(cred.credentials)
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=str(e),
        ) from e
    return parse_user_profile(cred.credentials)
