"""全局配置：从 .env / 环境变量加载，所有模块共享 settings 单例。"""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    # 服务
    app_name: str = "学成在线AI助手"
    host: str = "0.0.0.0"
    port: int = 8000

    # JWT（与 Java 门户共享；密钥须与 xuecheng-plus-auth TokenConfig.SIGNING_KEY 一致）
    jwt_secret: str = "mq123"
    jwt_algorithm: str = "HS256"

    # 阿里百炼
    dashscope_api_key: str = ""          # 为空 → 无Key降级模式
    llm_model: str = "qwen-plus"
    llm_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    llm_temperature: float = 0.7
    embedding_model: str = "text-embedding-v3"
    embedding_dim: int = 1024
    embedding_batch_size: int = 16

    # MySQL
    mysql_host: str = "192.168.101.65"
    mysql_port: int = 3306
    mysql_user: str = "root"
    mysql_password: str = ""
    mysql_db: str = "xcplus_agent"

    # RabbitMQ
    rabbitmq_host: str = "192.168.101.65"
    rabbitmq_port: int = 5672
    rabbitmq_user: str = "rabbitmq"
    rabbitmq_password: str = "rabbitmq"
    rabbitmq_vhost: str = "/"
    rabbit_exchange: str = "xc.lecture"
    rabbit_queue: str = "xc.lecture.parse"
    rabbit_routing_key: str = "lecture.upload"
    rabbit_consumer_enabled: bool = True

    # MinIO
    minio_endpoint: str = "192.168.101.65:9000"
    minio_access_key: str = "minioadmin"
    minio_secret_key: str = "minioadmin"
    minio_secure: bool = False
    minio_bucket: str = "lecture"

    # 学成在线 Java 后端
    java_base_url: str = ""
    course_list_path: str = "/api/course/list"
    lecture_callback_path: str = "/api/lecture/parse-status"
    java_course_detail_url_template: str = ""

    # 记忆
    memory_token_threshold: int = 2000
    history_max_turns: int = 20
    # 单会话内存缓冲消息数上限（超出裁剪最旧，防止长会话内存无限增长；DB 全量保留）
    memory_max_messages: int = 40

    # RAG
    chroma_path: str = "data/chroma"
    chroma_collection: str = "course_lectures"
    rag_top_k: int = 4
    # 向量召回相似度阈值（Chroma cosine 空间 distance=1-cos，0.7≈相似度0.3）；
    # 距离大于该值的低相关片段不参与 RRF 融合；过滤后为空则退回原始召回兜底
    rag_max_distance: float = 0.7
    chunk_size: int = 500
    chunk_overlap: int = 50
    rrf_k: int = 60

    @property
    def llm_enabled(self) -> bool:
        """是否配置了真实百炼 Key。"""
        return bool(self.dashscope_api_key.strip())


settings = Settings()
