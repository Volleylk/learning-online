"""MinIO 对象存储客户端。

讲义文件由教学机构端上传至 MinIO，AI 服务消费消息后按 bucket/object 下载解析。
"""
from __future__ import annotations

import os
from typing import Optional

from minio import Minio

from app.config import settings

_client: Optional[Minio] = None


def get_minio_client() -> Minio:
    global _client
    if _client is None:
        _client = Minio(
            settings.minio_endpoint,
            access_key=settings.minio_access_key,
            secret_key=settings.minio_secret_key,
            secure=settings.minio_secure,
        )
    return _client


def ensure_bucket(bucket: Optional[str] = None) -> str:
    """确保 bucket 存在（不存在则创建），返回 bucket 名。"""
    bucket = bucket or settings.minio_bucket
    client = get_minio_client()
    if not client.bucket_exists(bucket):
        client.make_bucket(bucket)
    return bucket


def download_object(bucket: str, object_name: str, dest_dir: str) -> str:
    """下载对象到本地临时目录，返回本地文件路径。"""
    os.makedirs(dest_dir, exist_ok=True)
    local_path = os.path.join(dest_dir, os.path.basename(object_name) or "lecture.bin")
    get_minio_client().fget_object(bucket, object_name, local_path)
    return local_path


def upload_file(bucket: str, object_name: str, local_path: str) -> None:
    """上传文件（测试/演示用）。"""
    ensure_bucket(bucket)
    with open(local_path, "rb") as f:
        stat = os.stat(local_path)
        get_minio_client().put_object(
            bucket, object_name, f, length=stat.st_size
        )
