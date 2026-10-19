# 学成在线 · AI 学习助手（xuecheng-plus-agent / Python 服务）

门户 AI 学习助手：支持通用对话、全站公开课程讲义知识问答（RAG）、Agent 主动推荐课程（回答末尾课程卡片直达详情页）、"我的课程"查询（JWT 透传网关）、长期记忆个性化问答。

> 技术栈：FastAPI + LangChain 0.3（AgentExecutor）+ 阿里百炼（千问 qwen-plus / text-embedding-v3）
> + Chroma + MySQL + RabbitMQ + MinIO。AI 模块独立部署，与学成在线 Java 工程通过
> **JWT（共享密钥 + 透传）/ RabbitMQ 消息 / HTTP 调用**交互，不侵入业务核心代码。

---

## 一、目录结构

```
xuecheng-plus-agent/
├── app/                        # Python 服务主代码
│   ├── main.py                 # FastAPI 入口（静态页面 + API + 消费者线程 + 路由注册）
│   ├── config.py               # 配置（.env）
│   ├── auth.py                 # JWT 本地校验 + 用户画像解析（user_name=XcUserExt JSON）
│   ├── llm.py                  # 百炼大模型/向量化封装（含无Key降级）
│   ├── db.py                   # MySQL：chat_history / user_preferences（历史会话为聚合查询，无独立表）
│   ├── memory.py               # 短期对话记忆 + 长期偏好 + SummarizationMiddleware
│   ├── rag.py                  # Chroma 向量库：入库 + 混合检索（向量+BM25+RRF）+ 阈值过滤
│   ├── tools.py                # Agent 工具：讲义检索 / 课程推荐 / 我的课程 / 偏好管理
│   ├── agent.py                # Agent 调度核心（流式 SSE + 请求级工具挂载 + 降级）
│   ├── minio_client.py         # MinIO 下载/上传
│   ├── rabbitmq.py             # 讲义消费（串行）+ 解析状态回调 Java
│   ├── consumer.py             # 独立消费进程入口
│   ├── schemas.py              # 接口数据模型
│   ├── routers/
│   │   ├── chat.py             # /api/chat（SSE 流式）、/api/sessions、会话消息
│   │   ├── lecture.py          # /api/lecture/*（转发 media-api）
│   │   └── preferences.py      # /api/preferences（偏好读写）
│   └── static/                 # 聊天前端页面（HTML/CSS/JS，含历史会话侧栏）
├── scripts/
│   └── get_token.py            # 生成测试 JWT
├── docs/
│   └── 对接文档.md             # Java 侧对接说明（JWT 约定 / 讲义链路 / 新接口）
├── venv/                       # Python 虚拟环境
├── requirements.txt
└── .env                        # 本机真实配置（不入库）
```

## 二、快速启动

```powershell
cd D:\ideafile\xuecheng-plus-project\xuecheng-plus-agent

# 1) 虚拟环境（已创建；重装依赖用：venv\Scripts\python -m pip install -r requirements.txt）
# 2) 配置 .env（DASHSCOPE_API_KEY 留空 = 降级模式，本地可跑通）
# 3) 启动服务
venv\Scripts\python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# 4) 打开页面 http://localhost:8000
#    生成测试 token：venv\Scripts\python scripts\get_token.py <userId>
```

启动后自动完成：MySQL 建表（xcplus_agent 库）→ Chroma 初始化 → Agent 构建 → 讲义消费线程启动。

> 表结构权威定义见仓库根 `sql/` 目录（`sql/xuecheng-plus-agent.sql`：chat_history / user_preferences；`sql/xuecheng-plus-media.sql`：lecture_parse_status）。agent 启动自动建表（IF NOT EXISTS），SQL 文件供审阅 / 部署 / 迁移。

## 三、接口一览

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/chat` | 问答（SSE 流式：thinking / answer / sources / courses / done 事件）`{message, session_id?}` | Bearer JWT |
| GET | `/api/sessions` | 用户最近会话列表（含首条消息摘要） | Bearer JWT |
| GET | `/api/sessions/{id}/messages` | 某会话历史消息（历史会话 UI 用） | Bearer JWT |
| GET | `/api/preferences` | 读取当前用户长期偏好 | Bearer JWT |
| PUT | `/api/preferences` | 更新偏好（learning_stage/explanation_style/focus_directions/extra） | Bearer JWT |
| POST | `/api/lecture/upload` | 转发 media-api：上传讲义触发解析 | Bearer JWT |
| GET | `/api/lecture/parse-status/{file_id}` | 转发 media-api：单条解析状态 | Bearer JWT |
| GET | `/api/lecture/parse-status/list` | 转发 media-api：按课程查解析状态列表 | Bearer JWT |
| GET | `/api/health` | 服务与中间件健康状态 | 公开 |
| GET | `/` | 聊天页面 | - |

## 四、讲义入库链路（教学机构端上传 → 知识库）

1. Java 侧（media-api）上传讲义 → 存入 MinIO（bucket `lecture`），讲义解析状态表新增记录（processing）
2. Java 侧发 RabbitMQ 消息（topic 交换机 `xc.lecture`，路由键 `lecture.upload`，队列 `xc.lecture.parse`）
3. AI 服务串行消费（prefetch=1）→ 下载 MinIO 文件 → 切分 → 百炼向量化 → 写入 Chroma（按 file_hash 去重）
4. 处理完回调 Java 接口（`POST {JAVA_BASE_URL}/api/lecture/parse-status`）：`{"fileId":"xx","status":"success|failed","message":"..."}`，Java 侧写入状态表（xcplus_media.lecture_parse_status）
5. 教学机构端轮询查询：media-api `GET /api/lecture/parse-status/list?courseId=`（agent 提供 `/api/lecture/*` 转发路由）

## 五、配置说明（.env 关键项）

| 配置 | 说明 |
|---|---|
| `DASHSCOPE_API_KEY` | 阿里百炼 Key；**留空 = 降级模式**（对话/检索/课程推荐本地可跑通） |
| `LLM_MODEL` / `EMBEDDING_MODEL` | 默认 `qwen-plus` / `text-embedding-v3`，可改 |
| `JWT_SECRET` / `JWT_ALGORITHM` | **必须与 Java 门户一致**（HS256），否则 401 |
| `MYSQL_*` | agent 本地库 xcplus_agent（会话/偏好） |
| `RABBITMQ_*` | 交换机 `xc.lecture`、路由键 `lecture.upload`、队列 `xc.lecture.parse` |
| `MINIO_*` | MinIO 地址 / bucket `lecture` |
| `JAVA_BASE_URL` | Java 后端地址（课程推荐 / 解析状态回调） |
| `CHROMA_PATH` / `RAG_TOP_K` / `RAG_MAX_DISTANCE` | 向量库路径、召回数、相似度阈值过滤（默认 0.7） |

## 六、Agent 工具

| 工具 | 说明 | 挂载方式 |
|---|---|---|
| `course_lecture_search` | 讲义 RAG 检索（带来源课程/页码） | 全局 |
| `course_info_query` | 公开课程推荐（走 Java 课程列表接口） | 全局 |
| `my_courses_query` | "我的课程"（走网关 `/learning/mycoursetable`，**携带当前用户原始 JWT 透传**） | **请求级动态挂载**（token 为空不挂） |
| `user_preference_read` / `user_preference_update` | 长期偏好读写 | 全局 |

## 七、核心设计点

- **降级模式**：未配置百炼 Key 时，对话模型/向量化模型自动切换为本地实现，RAG 检索、课程推荐、记忆落库全链路可验证；填入 Key 重启即切回真实大模型（AgentExecutor）。
- **SummarizationMiddleware**：对话上下文 token 超过阈值（默认 2000）自动调用大模型压缩，MySQL 保留原始全量对话用于审计。
- **混合检索**：向量语义召回 + BM25 关键词召回 → RRF 融合去重 → TopN → 相似度阈值过滤（`rag_max_distance`）。
- **串行消费**：RabbitMQ prefetch=1，逐份处理讲义，避免压垮模型接口。
- **请求级工具挂载**："我的课程"工具按请求级 token 动态构造并挂载，避免全局单例串号；token 为空不挂载。
- **JWT 透传**：agent 调用 Java 用户维接口时原样透传请求携带的 JWT，由网关负责身份解析。
- **用户画像注入**：解析 JWT `user_name`（XcUserExt JSON）提取昵称/姓名/ID，注入系统提示，支持"我是谁"等个性化问答（兼容早期 sub/user_id/uid/userId 格式）。
- **工具参数兼容**：百炼 DashScope 兼容层对单参数工具返回 `__arg1`，工具层用 `_first_str_arg` 通用键名兜底。

## 八、与 Java 侧交互总览

| 交互点 | 方向 | 说明 |
|---|---|---|
| JWT 共享密钥 | 双向 | HS256 同密钥；agent 本地校验 + 解析用户画像 |
| 讲义上传 + MQ 消息 | Java → RabbitMQ → agent | 异步解析讲义 |
| 解析状态回调 | agent → Java | `POST /api/lecture/parse-status` 更新状态表 |
| 讲义状态查询 | agent → media（转发） | `/api/lecture/parse-status/list` 等 |
| 课程信息查询 | agent → Java | 课程推荐工具 |
| 我的课程查询 | agent → 网关 → learning | JWT 透传 |
| 课程详情跳转 | 前端 → 门户 | 课程卡片链接 |

> Java 侧详细对接见 [docs/对接文档.md](docs/对接文档.md)。
