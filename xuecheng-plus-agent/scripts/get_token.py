# -*- coding: utf-8 -*-
"""获取学成在线门户 JWT 令牌（走网关 OAuth2 password 模式登录）。

用法:
    python scripts/get_token.py                # 默认 itcast/111111
    python scripts/get_token.py stu1 111111    # 指定账号密码

返回:
    HTTP 200 时打印 access_token（JWT），可直接作为
    Authorization: Bearer <token> 调用 agent 服务 /api/chat 等接口。
"""
import json
import sys

import requests

GATEWAY = "http://localhost:63010"  # 生产环境改为 http://192.168.101.65:63010

username = sys.argv[1] if len(sys.argv) > 1 else "itcast"
password = sys.argv[2] if len(sys.argv) > 2 else "111111"


def get_token(user: str, pwd: str) -> str:
    json_body = json.dumps(
        {"username": user, "password": pwd, "authType": "password"},
        ensure_ascii=False,
    )
    resp = requests.post(
        f"{GATEWAY}/auth/oauth/token",
        data={
            "username": json_body,
            "grant_type": "password",
            "client_id": "XcWebApp",
            "client_secret": "XcWebApp",
        },
        timeout=10,
    )
    if resp.status_code != 200:
        raise RuntimeError(f"登录失败 HTTP {resp.status_code}: {resp.text[:300]}")
    return json.loads(resp.text)["access_token"]


if __name__ == "__main__":
    token = get_token(username, password)
    print(token)
    print(f"\n[OK] 用户 {username} 登录成功，token 长度 {len(token)}，"
          f"有效期内可直接使用。示例：")
    print(f'  curl -X POST http://localhost:8000/api/chat \\')
    print(f'    -H "Authorization: Bearer {token[:40]}..." \\')
    print(f'    -H "Content-Type: application/json" \\')
    print(f'    -d \'{{"message": "你好"}}\'')
