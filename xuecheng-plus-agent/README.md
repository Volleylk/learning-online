# 学成在线 · AI 学习助手（xuecheng-plus-agent / Python 服务）

门户 AI 学习助手：支持通用对话、全站公开课程讲义知识问答（RAG）、Agent 主动推荐课程（回答末尾课程卡片直达详情页）、"我的课程"查询（JWT 透传网关）、长期记忆个性化问答。

> 技术栈：**FastAPI + LangChain 1.x（LangGraph：create_agent + astream_events v3）+ 阿里百炼（qwen3.8-max / text-embedding-v3）**
> + Chroma + MySQL + RabbitMQ + MinIO。AI 模块独立部署，与学成在线 Java 工程通过
> **JWT（共享密钥 + 透传）/ RabbitMQ 消息 / HTTP 调用**交互，不侵入业务核心代码。

---

## 一、目录结构

```
xuecheng-plus-agent/
├── app/                        # Python 服务主代码
│   ├── main.py                 # FastAPI 入口（静态页面 + API + 消费者线程 + 结构化日志）
│   ├── config.py               # 配置（.env，pydantic-settings）
│   ├── auth.py                 # JWT 本地校验 + 用户画像解析 + get_auth_context（单次解码）
│   ├── llm.py                  # 百炼大模型/向量化封装（embedding 指数退避；含无Key降级）
│   ├── db.py                   # MySQL：chat_history（含 courses 卡片列）/ user_preferences
│   ├── memory.py               # 短期对话记忆 + 长期偏好 + 自动压缩 + 内存上限裁剪
│   ├── rag.py                  # Chroma：父子分块入库（标题注入）+ 混合检索（向量+BM25+RRF+rerank）+ 健壮性
│   ├── tools.py                # Agent 工具（@tool）：讲义检索 / 课程推荐 / 我的课程 / 偏好管理
│   ├── agent.py                # Agent 调度核心（LangGraph create_agent + v3 事件流式 + 降级）
│   ├── minio_client.py         # MinIO 下载/上传
│   ├── rabbitmq.py             # 讲义消费（串行 prefetch=1）+ 解析状态回调 Java
│   ├── consumer.py             # 独立消费进程入口
│   ├── schemas.py              # 接口数据模型
│   ├── routers/
│   │   ├── chat.py             # /api/chat（SSE 流式 + 心跳）、/api/sessions、会话消息
│   │   ├── lecture.py          # /api/lecture/*（转发 media-api）
│   │   └── preferences.py      # /api/preferences（偏好读写）
│   └── static/                 # 聊天前端页面（HTML/CSS/JS：Markdown 渲染 + 课程卡片 + 历史会话侧栏）
├── scripts/
│   ├── get_token.py            # 生成测试 JWT
│   └── eval_rag.py             # RAG 检索质量评估（Recall@K，贯穿验证用）
├── docs/
│   └── 对接文档.md             # Java 侧对接说明（JWT 约定 / 讲义链路 / 新接口）
├── venv/                       # Python 虚拟环境（3.13）
├── data/chroma                 # 向量库持久化目录（自动生成）
├── start.ps1 / 一键启动.bat    # 一键启动（自动检测端口/进程，打开页面）
├── stop.ps1 / 停止服务.bat     # 停止服务
├── requirements.txt
├── .env                        # 本机真实配置（不入库，模板见 .env.example）
└── README.md
```

## 二、快速启动

```powershell
cd D:\ideafile\xuecheng-plus-project\xuecheng-plus-agent

# 1) 虚拟环境（已创建；重装依赖用：venv\Scripts\python -m pip install -r requirements.txt）
# 2) 配置 .env（DASHSCOPE_API_KEY 留空 = 降级模式，本地可跑通）
# 3) 启动服务（或直接双击 一键启动.bat）
venv\Scripts\python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# 4) 打开页面 http://localhost:8000
#    生成测试 token：venv\Scripts\python scripts\get_token.py <userId>
```

启动后自动完成：MySQL 建表（xcplus_agent 库）→ Chroma 初始化 → LangGraph Agent 构建 → 讲义消费线程启动。

> 表结构权威定义见仓库根 `sql/` 目录（`sql/xuecheng-plus-agent.sql`：chat_history / user_preferences；`sql/xuecheng-plus-media.sql`：lecture_parse_status）。agent 启动自动建表（IF NOT EXISTS + 幂等迁移），SQL 文件供审阅 / 部署 / 迁移。

## 三、接口一览

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/chat` | 问答（SSE 流式：thinking / answer / sources / courses / done 事件，15s 心跳）`{message, session_id?}` | Bearer JWT |
| GET | `/api/sessions` | 用户最近会话列表（含首条消息摘要） | Bearer JWT |
| GET | `/api/sessions/{id}/messages` | 某会话历史消息（含课程卡片，重开会话可重新渲染） | Bearer JWT |
| GET | `/api/preferences` | 读取当前用户长期偏好 | Bearer JWT |
| PUT | `/api/preferences` | 更新偏好（learning_stage/explanation_style/focus_directions/extra） | Bearer JWT |
| POST | `/api/lecture/upload` | 转发 media-api：上传讲义触发解析 | Bearer JWT |
| GET | `/api/lecture/parse-status/{file_id}` | 转发 media-api：单条解析状态 | Bearer JWT |
| GET | `/api/lecture/parse-status/list` | 转发 media-api：按课程查解析状态列表 | Bearer JWT |
| GET | `/api/health` | 服务与中间件健康状态（mysql/rabbitmq/minio/rag） | 公开 |
| GET | `/` | 聊天页面 | - |

**SSE 事件协议**：

| 事件 | data 载荷 | 说明 |
|---|---|---|
| `thinking` | `{text}` | 思考过程 / 工具调用提示（前端气泡式展示） |
| `answer` | `{delta}` | 流式文本增量（逐字/逐块推送） |
| `sources` | `[{course_id, course_name, source_file, page_num, text}]` | 讲义引用来源（RAG 命中时） |
| `courses` | `[{id, name, cover, url, price}]` | 课程推荐卡片（找课命中时，与 done 一起推送） |
| `done` | `{session_id, courses, sources, mode}` | 本轮结束（携带聚合后的卡片/来源） |

## 四、讲义入库链路（教学机构端上传 → 知识库）

1. Java 侧（media-api）上传讲义 → 存入 MinIO（bucket `lecture`），讲义解析状态表新增记录（processing）
2. Java 侧发 RabbitMQ 消息（topic 交换机 `xc.lecture`，路由键 `lecture.upload`，队列 `xc.lecture.parse`）
3. AI 服务串行消费（prefetch=1）→ 下载 MinIO 文件 → 提取文本（PDF/DOCX/TXT/MD）→ **父子分块**（大块 800 字 / 小块 200 字，小块入库、检索命中小块后归并回大块）+ **标题注入**（课程名/文件名拼入向量输入，Chroma 存正文原文）→ jieba 分词 → 百炼向量化 → 写入 Chroma（按 file_hash 去重，重复上传覆盖旧切片）
4. 处理完回调 Java 接口（`POST {JAVA_BASE_URL}/api/lecture/parse-status`）：`{"fileId":"xx","status":"success|failed","message":"..."}`，Java 侧写入状态表（xcplus_media.lecture_parse_status）
5. 教学机构端轮询查询：media-api `GET /api/lecture/parse-status/list?courseId=`（agent 提供 `/api/lecture/*` 转发路由）

## 五、配置说明（.env 关键项）

| 配置 | 说明 |
|---|---|
| `DASHSCOPE_API_KEY` | 阿里百炼 Key；**留空 = 降级模式**（对话/检索/课程推荐本地可跑通） |
| `LLM_MODEL` / `EMBEDDING_MODEL` | 对话模型 / 向量模型；本项目使用 `qwen3.8-max` / `text-embedding-v3`（config 默认 qwen-plus 可改） |
| `JWT_SECRET` / `JWT_ALGORITHM` | **必须与 Java 门户一致**（HS256），否则 401 |
| `MYSQL_*` | agent 本地库 xcplus_agent（会话/偏好） |
| `RABBITMQ_*` | 交换机 `xc.lecture`、路由键 `lecture.upload`、队列 `xc.lecture.parse` |
| `MINIO_*` | MinIO 地址 / bucket `lecture` |
| `JAVA_BASE_URL` | Java 后端地址（课程推荐 / 解析状态回调） |
| `CHROMA_PATH` / `RAG_TOP_K` / `RAG_MAX_DISTANCE` | 向量库路径、召回数、相似度阈值过滤（默认 0.7） |
| `PARENT_CHUNK_SIZE` / `CHILD_CHUNK_SIZE` | 父子分块尺寸（默认 800 / 200，overlap 80 / 30） |
| `RAG_RERANK` / `RERANK_MODEL` | 精排开关 / 模型（`qwen3-rerank`，百炼原生协议；调用失败自动降级 RRF 原序） |
| `QUERY_REWRITE_ENABLED` | 讲义检索前 LLM 查询改写 + 对话补全指代（默认开，改写失败退回原查询） |
| `MEMORY_MAX_MESSAGES` | 单会话内存缓冲消息数上限（默认 40，超出裁剪最旧；DB 全量保留） |

## 六、Agent 工具（LangGraph 固定注册 + contextvar 注入）

| 工具 | 说明 | 请求级数据 |
|---|---|---|
| `course_lecture_search` | 讲义 RAG 检索：查询改写（LLM 改写 + 对话补全指代）→ 双路召回合并 → 返回大块（含来源课程/页码） | `set_query_history(history)` 注入最近对话 |
| `course_info_query` | 公开课程推荐（走 Java 课程列表接口，httpx 连接池复用） | — |
| `my_courses_query` | "我的课程"（走网关 `/learning/mycoursetable`，**透传当前用户原始 JWT**） | `set_my_courses_token(token)` 注入 |
| `user_preference_read` / `user_preference_update` | 长期偏好读写 | `set_agent_user(user_id)` 注入 |

> 工具全部以 `@tool` 装饰器定义，请求级 user_id / JWT 通过 **contextvar** 注入——Agent 无需按请求重建，全局单例也不会串号；工具调用结果以 ToolMessage 回传，agent 按 `tool_call_id` 反查工具名聚合课程卡片/讲义来源。

## 七、Agent 调度核心（LangChain 1.x / LangGraph）

- **构建**：`langchain.agents.create_agent(model, tools)` → 编译为 LangGraph `CompiledStateGraph`，模型自主决策工具调用，支持多轮工具循环（recursion_limit=30）
- **流式**：`astream_events(version="v3")` 事件协议（Pregel 实验特性）——文本增量在 `content-block-delta` 的 `delta.type=="text-delta"`；工具调用在 `content-block-finish`（结构化 args，无需键名兼容）；工具结果在 `tools` method 的 `tool-finished`
- **上下文**：输入为 `{"messages": [...]}`（系统提示 + 会话历史 + 当前问题），历史由 SessionMemory 管理
- **降级模式**：无 Key 时切规则引擎（闲聊直答 / 知识点走 RAG / 找课出课程卡片），全链路可本地验证

## 八、核心设计点

- **JWT 单次解码**：`get_auth_context` 一次解码同时产出 user_id / 原始 token / 用户画像，替代三次依赖重复解码；门户 JWT 用户身份在 `user_name`（XcUserExt JSON），兼容早期 `sub/user_id/uid/userId`，显示名优先级：昵称 > 姓名 > 用户名
- **混合检索**：向量语义召回（阈值过滤 `rag_max_distance=0.7`，embedding 失败自动降级 BM25-only）+ BM25 关键词召回（**jieba 分词 + 停用词过滤**）→ RRF 融合去重（k=60）→ **qwen3-rerank 精排**（失败降级 RRF 原序）→ **来源去重**（同一讲义最多 2 条）→ TopN
- **父子分块**：入库切大块（800 字）+ 小块（200 字）；小块入库检索，命中小块归并回大块喂 LLM，同一大块只保留最高分小块，避免重复回传
- **查询重写**：讲义检索工具调用前由 LLM 改写查询（补全"它/这个/那门课"等指代），改写结果与原查询双路召回合并去重
- **迭代检索**：LangGraph 工具循环天然支持多轮检索（recursion_limit=30），系统提示显式授权"信息不足可换一种问法再次检索"
- **检索健壮性**：空库防御 / `count + dirty` 双对账（修复"删N加N后 count 不变"导致的 BM25 与库不一致盲区）/ refresh 失败保留旧索引继续服务
- **记忆分层**：短期会话记忆（内存缓冲 + 自动压缩 SummarizationMiddleware，token 超阈值调大模型摘要）+ 长期偏好记忆（MySQL 持久化）+ 内存上限裁剪（`memory_max_messages`，DB 全量保留用于审计）
- **SSE 心跳**：无事件超 15s 补发注释型 ping，防代理/网关掐断长连接
- **串行消费**：RabbitMQ prefetch=1，逐份处理讲义，坏消息 ack 丢弃、临时错误 nack 重试一次，避免死循环刷屏
- **embedding 指数退避**：百炼向量化对 429/5xx/网络错误自动重试（0.5s/1s，最多 3 次），RAG 入库/检索抗抖动
- **结构化日志**：核心模块统一 `logging`（时间戳 + 级别 + 模块名），启动/消费/回调/鉴权均有可观测日志
- **课程卡片持久化**：SSE `courses` 事件聚合去重后落库 `chat_history.courses` 列，重开历史会话前端自动重新渲染卡片

## 九、与 Java 侧交互总览

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

## 十、聊天前端（app/static）

- **Markdown 渲染**：内置轻量渲染器（表格/标题/列表/代码块/引用/加粗/行内代码/链接/分隔线），原文先转义再注入白名单标签防 XSS；流式半截表格、未闭合代码块容错渲染
- **课程卡片**：封面 + 标题（无简介无箭头），排在气泡正下方，点击跳转门户课程详情页；模型正文禁止输出课程网址
- **历史会话侧栏**：会话列表 + 消息记录，重开会话自动重渲课程卡片
