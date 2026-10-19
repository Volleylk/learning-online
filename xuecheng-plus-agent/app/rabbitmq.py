"""RabbitMQ 讲义消费模块。

监听讲义处理 Topic：
- 消息体：{url: 讲义MinIO访问URL, courseId, courseName, fileId}（兼容 bucket/object 字段）
- 串行处理每份讲义（prefetch=1），避免并发过大压垮模型接口
- 处理完成/失败后回调 Java 侧讲义状态更新接口

支持两种运行方式：
1. 随 FastAPI 启动的守护线程（RABBIT_CONSUMER_ENABLED=true）
2. 独立进程：python -m app.consumer
"""
from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from typing import Any, Callable, Dict, Optional

import httpx
import pika

from app.config import settings
from app.minio_client import download_object
from app.rag import RAGService


class LectureConsumer:
    def __init__(self, rag: RAGService):
        self.rag = rag
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    # ------------------------------------------------------------------
    # 连接
    # ------------------------------------------------------------------
    def _connect(self) -> pika.BlockingConnection:
        cred = pika.PlainCredentials(settings.rabbitmq_user, settings.rabbitmq_password)
        params = pika.ConnectionParameters(
            host=settings.rabbitmq_host,
            port=settings.rabbitmq_port,
            virtual_host=settings.rabbitmq_vhost,
            credentials=cred,
            heartbeat=60,
            blocked_connection_timeout=30,
        )
        return pika.BlockingConnection(params)

    def _declare(self, channel) -> None:
        channel.exchange_declare(
            exchange=settings.rabbit_exchange,
            exchange_type="topic",
            durable=True,
        )
        channel.queue_declare(queue=settings.rabbit_queue, durable=True)
        channel.queue_bind(
            queue=settings.rabbit_queue,
            exchange=settings.rabbit_exchange,
            routing_key=settings.rabbit_routing_key,
        )

    # ------------------------------------------------------------------
    # 消息处理
    # ------------------------------------------------------------------
    def _handle(self, body: bytes) -> bool:
        """处理一条讲义消息。返回 True=处理成功（ack），False=失败（nack 重回队列）。"""
        try:
            msg = json.loads(body.decode("utf-8"))
        except Exception as e:
            print(f"[consumer] 消息解析失败，丢弃: {e}")
            return True  # 坏消息 ack 丢弃，避免死循环

        course_id = str(msg.get("courseId") or msg.get("course_id") or "")
        course_name = msg.get("courseName") or msg.get("course_name") or ""
        file_id = str(msg.get("fileId") or msg.get("file_id") or "")
        url = msg.get("url") or ""
        bucket = msg.get("bucket") or msg.get("minioBucket") or settings.minio_bucket
        obj = msg.get("object") or msg.get("minioObject") or ""

        # 从 URL 解析 bucket/object（http://host:9000/bucket/object...）
        if not obj and url:
            try:
                parts = url.split("/")
                if ":" in parts[2]:
                    parts = parts[3:]
                else:
                    parts = parts[3:]
                if len(parts) >= 2:
                    bucket, obj = parts[0], "/".join(parts[1:])
            except Exception:
                pass

        print(
            f"[consumer] 收到讲义任务 course={course_name}({course_id}) "
            f"file_id={file_id} bucket={bucket} object={obj}"
        )
        if not obj or not course_id:
            print("[consumer] 消息缺少 object/courseId，按成功 ack 丢弃")
            return True

        try:
            with tempfile.TemporaryDirectory() as tmp:
                local = download_object(bucket, obj, tmp)
                result = self.rag.ingest_file(
                    file_path=local,
                    course_id=course_id,
                    course_name=course_name,
                    source_file=os.path.basename(obj),
                )
            print(f"[consumer] 讲义入库完成: {result}")
            self._callback(file_id, "success", f"解析成功，切片{result.get('chunks', 0)}条")
            return True
        except ValueError as e:
            # 永久性错误（格式不支持等）：回调后 ack 丢弃，避免无限 requeue 刷屏
            print(f"[consumer] 讲义格式/内容错误，ack 丢弃: {e}")
            try:
                self._callback(file_id, "failed", str(e)[:200])
            except Exception as ce:
                print(f"[consumer] 状态回调失败: {ce}")
            return True
        except Exception as e:
            print(f"[consumer] 讲义处理失败: {e}")
            try:
                self._callback(file_id, "failed", str(e)[:200])
            except Exception as ce:
                print(f"[consumer] 状态回调失败: {ce}")
            return False  # 临时错误 nack 重回队列（on_message 限制最多重试一次）

    def _callback(self, file_id: str, status: str, message: str) -> None:
        """回调 Java 侧讲义解析状态接口。"""
        if not settings.java_base_url or not file_id:
            print(f"[consumer] 未配置回调地址或无file_id，跳过回调: {status} {message}")
            return
        url = f"{settings.java_base_url}{settings.lecture_callback_path}"
        payload = {"fileId": file_id, "status": status, "message": message}
        resp = httpx.post(url, json=payload, timeout=10)
        resp.raise_for_status()
        print(f"[consumer] 回调成功 file_id={file_id} status={status}")

    # ------------------------------------------------------------------
    # 消费循环
    # ------------------------------------------------------------------
    def _consume_loop(self) -> None:
        while not self._stop.is_set():
            try:
                conn = self._connect()
                channel = conn.channel()
                self._declare(channel)
                channel.basic_qos(prefetch_count=1)  # 串行，一次一条

                def on_message(ch, method, properties, body):
                    ok = self._handle(body)
                    if ok:
                        ch.basic_ack(delivery_tag=method.delivery_tag)
                    elif method.redelivered:
                        # 已 requeue 过一次仍失败：丢弃，避免死循环刷屏
                        print("[consumer] 消息重试一次仍失败，ack 丢弃")
                        ch.basic_ack(delivery_tag=method.delivery_tag)
                    else:
                        ch.basic_nack(delivery_tag=method.delivery_tag, requeue=True)

                channel.basic_consume(
                    queue=settings.rabbit_queue,
                    on_message_callback=on_message,
                    auto_ack=False,
                )
                print("[consumer] 开始监听讲义队列，Ctrl+C 退出")
                while not self._stop.is_set():
                    # 每 1s 轮询一次停止标志
                    conn.process_data_events(time_limit=1)
            except Exception as e:
                print(f"[consumer] 连接/消费异常: {e}，5秒后重连")
                self._stop.wait(5)
            finally:
                try:
                    conn.close()
                except Exception:
                    pass

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._thread = threading.Thread(target=self._consume_loop, daemon=True)
        self._thread.start()
        print("[consumer] 讲义消费线程已启动")

    def stop(self) -> None:
        self._stop.set()
