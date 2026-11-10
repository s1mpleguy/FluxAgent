# FluxAgent Java 版本说明

FluxAgent 是一个面向企业客服场景的多 Agent 编排平台，当前实现基于 Java/Spring 技术栈，支持智能对话、知识库增强、动态 Skills、记忆管理、质量评测和运行监控。

本仓库是 FluxAgent 的 Java 版本实现。



当前版本已经覆盖智能客服主链路：HTTP / CLI 对话、Redis 工作记忆、知识库检索、多 Agent 路由、Spring AI 模型调用、回答校验、评测、监控、Swagger 文档和 Docker 部署。仓库是单模块后端工程，未包含前端界面。工程结构见 `rules/工程结构.md`。

## 技术栈

| 类型 | 技术 |
|------|------|
| 语言 | Java 21 |
| Web 框架 | Spring Boot 3.5 |
| AI 框架 | Spring AI 1.1 |
| LLM Provider | 默认 DMXAPI DeepSeek，兼容 Anthropic；Jev 负责意图判断 |
| 文档处理 | LangChain4j DocumentSplitter |
| 记忆缓存 | Spring Data Redis |
| RAG | BM25 + 本地 hash vector + LLM rerank |
| 持久化 | Redis 工作记忆 + JSON 知识库/长期记忆/用户画像 |
| 监控 | Spring Boot Actuator、Micrometer、Prometheus |
| API 文档 | Springdoc OpenAPI、Swagger UI |
| 部署 | Docker、Docker Compose、Nginx、Prometheus |
| 构建 | Maven Wrapper |

## 核心链路

```text
POST /chat
  -> MemoryManager 读取 Redis 工作记忆、会话摘要、长期记忆、用户画像
  -> IntentRecognizer 优先使用 Jev，失败或低置信度时回退 LLM / 规则
  -> KnowledgeToolManager 做查询改写、并行召回、LLM rerank
  -> AgentOrchestrator 根据意图做 Agent 路由
  -> General / Technical / Billing Agent 生成回复
  -> AnswerVerifier 校验回复是否可信、是否需要转人工
  -> 写入 Redis，并异步更新用户画像
```

相关实现：

- `src/main/java/com/fluxagent/api/FluxAgentController.java`
- `src/main/java/com/fluxagent/memory/MemoryManager.java`
- `src/main/java/com/fluxagent/tool/KnowledgeToolManager.java`
- `src/main/java/com/fluxagent/agent/AgentOrchestrator.java`
- `src/main/java/com/fluxagent/agent/AnswerVerifier.java`

## Python 与 Java 版本对照

下表保留历史迁移说明；本仓库没有 Python 源码，本次验证覆盖 Java 实现，不代表完成了两种实现的逐项对比测试。

| 能力 | Python 版本 | Java 版本 | 当前状态 |
|------|-------------|-----------|----------|
| Web 框架 | FastAPI | Spring Boot MVC | 已对齐 |
| 模型调用 | Anthropic Async SDK | Spring AI ChatModel | 已对齐，调用栈不同 |
| DeepSeek | 未内置 | Spring AI DeepSeek profile | Java 增强 |
| Agent 类型 | General / Technical / Billing | General / Technical / Billing | 已对齐 |
| Agent 路由 | 意图路由 + 性能路由 + 降级 | 意图路由 + 性能路由 + 降级 | 已对齐 |
| 复合问题并行处理 | 支持 | 支持 | 已对齐 |
| 意图识别 | LLM + embedding/hash + pattern | Jev 优先，LLM + char n-gram semantic + pattern 兜底 | 支持独立决策模型 |
| 工作记忆 | Redis | Redis | 已对齐 |
| 情景记忆 | ChromaDB `episodic` collection | JSON 持久化 + 本地向量检索 | 功能对齐，存储不同 |
| 用户画像 | ChromaDB `user_profile` collection | JSON 持久化 | 功能对齐，存储不同 |
| 知识库 | ChromaDB `knowledge_base` collection | JSON 持久化 Hybrid RAG | 功能对齐，主检索实现不同 |
| 查询改写 | LLM 改写 | LLM 改写 | 已对齐 |
| 检索重排 | LLM rerank | LLM rerank，失败回退融合分 | 已对齐 |
| 工具框架 | 通用 MCPToolManager | ToolDefinition / ToolRegistry / ToolExecutor | 本地注册与执行已支持；未提供远程 MCP 协议 |
| 熔断/缓存/超时/fallback | 支持 | 通用执行器支持，知识库已接入 | 支持 JSON Schema 校验与失败统计 |
| 评测 | Intent、Macro-F1、LLM Judge、baseline | Intent、Macro-F1、LLM Judge、baseline | 基本对齐 |
| 监控 | Prometheus client、Webhook、Z-score | Actuator、Micrometer、Webhook、阈值与 Z-score 告警 | 支持恢复状态、聊天直方图和工具成功率 |
| API 文档 | FastAPI `/docs` | Springdoc Swagger UI `/docs` | 已对齐 |
| Docker | App、Redis、ChromaDB、Prometheus、Nginx | App、Redis、Prometheus、Nginx，ChromaDB 可选 | 配置已提供 |
| CLI | `api/main.py --cli` | 可执行 Jar `--cli` | 复用完整聊天服务 |

## Java 版已补齐的能力

### DeepSeek 兼容

默认通过 DMXAPI 调用 DeepSeek。将 `.env.example` 复制为 `.env` 后填写密钥（已有 `.env` 时直接编辑）：

```env
SPRING_PROFILES_ACTIVE=deepseek
DMX_API_KEY=your_key
DEEPSEEK_BASE_URL=https://www.dmxapi.cn
DEEPSEEK_MODEL=deepseek-v4.1-flash-1017
JEV_ENABLED=true
JEV_BASE_URL=https://www.dmxapi.cn
JEV_MODEL=jev-latest
```

从项目根目录运行 Maven 或 Jar 时自动读取 `.env`；进程环境变量和命令行参数可以覆盖。Compose 使用 `env_file` 注入。`.env` 被 Git 和 Docker 构建上下文忽略，真实密钥不能写入源码或 `.env.example`。

自动化测试设置 `ECHOMIND_ENV_FILE=` 关闭本地配置导入；也可将该变量设为 `optional:file:/指定路径/.env[.properties]` 使用其他配置文件。

主模型与 Jev 默认共用 `DMX_API_KEY`，也可分别设置 `DEEPSEEK_API_KEY`、`JEV_API_KEY`。主模型使用 `/v1/chat/completions`（通过 `DEEPSEEK_COMPLETIONS_PATH` 可覆盖），Jev 使用独立的 `/typesafe/v1/systemone`，不需要给 Base URL 添加这些路径。

`JevIntentClient` 根据当前消息和最近对话执行 Choice 分类；高置信度结果直接进入现有路由，并在 `intent_source_scores` 中标识 `jev`。关闭 Jev、缺少密钥、请求失败、响应无效或置信度不足时，使用原有识别逻辑。可配置 `JEV_TIMEOUT`（默认 `8s`）及 `JEV_MIN_CONFIDENCE`（默认 `0.7`，是待业务样本校准的初始值）。`IntentResult.reasoning` 为程序生成的分类说明，不是 Jev 的推理文本。检索重排、回答校验和评测继续使用主模型。

当前还做了启动容错：缺少真实 API Key 时使用 `echomind-local-placeholder` 避免 Spring AI 自动配置阶段直接失败。真实调用时如果没有配置真实 key，并且：

```env
LLM_FALLBACK_ENABLED=true
```

系统会返回本地降级回复。

Anthropic 配置：

```env
SPRING_PROFILES_ACTIVE=anthropic
ANTHROPIC_API_KEY=your_key
ANTHROPIC_BASE_URL=https://api.anthropic.com
ANTHROPIC_MODEL=claude-3-5-sonnet-20241022
```

### 记忆和知识库持久化

Python 版：

- Redis 保存工作记忆。
- ChromaDB 保存情景记忆、用户画像和知识库。

Java 版：

- Redis 保存工作记忆。
- `data/java/memory-store.json` 保存情景记忆和用户画像。
- `data/java/knowledge-store.json` 保存知识库片段。
- Docker 中保存到 `/app/data/java`，由 `app-data` volume 持久化。

配置项：

```env
ECHOMIND_DATA_DIR=data/java
KNOWLEDGE_STORE_PATH=data/java/knowledge-store.json
MEMORY_STORE_PATH=data/java/memory-store.json
EVAL_BASELINE_PATH=data/eval/baseline.json
```

当前用户可见效果已经对齐：应用重启后，导入的知识库、长期记忆和画像可以恢复。底层差异仍然存在：Java 没有直接写入 ChromaDB collection，而是 JSON 持久化 + 本地 hash vector 检索。

### Hybrid RAG

Python 版知识库主要通过 ChromaDB 做语义检索。

Java 版当前检索链路：

```text
文档导入
  -> LangChain4j recursive splitter
  -> JSON 持久化
  -> 本地 documents 索引

查询
  -> LLM 查询改写
  -> 多子查询并行召回
  -> BM25 关键词得分
  -> 本地 hash vector 语义得分
  -> 加权融合
  -> LLM rerank
  -> fallback 到融合分排序
```

这比 Python 版多了 BM25 + vector 融合检索，但没有把 ChromaDB 作为主召回源。

### 评测和监控

Java 版已经实现：

- Intent Accuracy
- Macro-F1
- per-class Precision / Recall / F1
- LLM-as-Judge 四维评分
- baseline 保存和回归检测
- `/monitor`
- `/metrics`
- `/actuator/prometheus`
- Webhook 告警
- Agent 路由惩罚反馈
- Grafana 自动配置看板（请求、Agent、工具缓存/熔断、告警、JVM）
- 无 Docker 的本地监控启动、停止和链路检查脚本

IDE 或 `java -jar` 启动不会同时启动 Prometheus。macOS 安装 `brew install prometheus grafana` 后，在仓库执行：

```bash
python3 scripts/monitoring.py start
python3 scripts/monitoring.py check
```

Prometheus：`http://localhost:9091/graph`；Grafana：`http://localhost:3000/d/fluxagent-overview/fluxagent`。
运行、指标与排障说明见 [监控运行指南](docs/监控运行指南.md)，其余能力的真实状态见 [模块现状与后续规划](docs/模块现状与后续规划.md)。

相关实现：

- `src/main/java/com/fluxagent/evaluation/EndToEndEvaluator.java`
- `src/main/java/com/fluxagent/evaluation/LLMJudge.java`
- `src/main/java/com/fluxagent/monitor/PerformanceMonitor.java`

### Swagger / OpenAPI

Python 版 FastAPI 默认提供 `/docs`。

Java 版现在通过 Springdoc OpenAPI 提供同名入口：

- Swagger UI：`http://localhost:8080/docs`
- Nginx 代理：`http://localhost:8081/docs`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

相关实现：

- `pom.xml`
- `src/main/resources/application.yml`
- `src/main/java/com/fluxagent/config/OpenApiConfig.java`
- `src/main/java/com/fluxagent/api/SwaggerDocsController.java`
- `src/main/java/com/fluxagent/api/FluxAgentController.java`

`/docs` 页面会从 jsdelivr CDN 加载 Swagger UI 静态资源；如果部署在不能访问外网的环境，需要把 Swagger UI 静态资源放到项目本地。

## 当前仍然不同的地方

### 通用工具管理

Java 版通过 `ToolDefinition<T>` 定义工具，通过 `ToolRegistry` 注册/注销，通过 `ToolExecutor` 执行。支持：

- JSON Schema 2020-12 参数校验（networknt validator）。
- 每工具超时、失败阈值和熔断恢复；恢复时只允许一个探测请求。
- TTL 缓存，单工具最多 1024 条；支持显式失效，注销后重新注册不复用旧状态。
- 降级函数和工具统计。降级返回的数据不计为成功，也不进入缓存。
- 有界线程池与排队；超时会发出中断取消，工具函数应响应中断并设置自身 I/O 超时。

`KnowledgeToolManager` 已接入公共执行器，30 秒超时覆盖查询改写、并行召回和重排。导入知识后，缓存键中的数据版本更新，后续请求立即读取新内容。模型重排失败时回退融合分，并返回 `reranked=false`。

这是 Java 进程内工具扩展机制；远程 MCP 协议连接暂未实现。自定义工具注册示例：

```java
ToolDefinition<String> tool = new ToolDefinition<>(
    "echo", "回显消息",
    objectMapper.readTree("""
        {"type":"object","properties":{"message":{"type":"string"}},
         "required":["message"],"additionalProperties":false}
        """),
    Duration.ofSeconds(5), Duration.ZERO, 5, Duration.ofSeconds(60),
    parameters -> parameters.get("message").asText(),
    (parameters, error) -> "服务暂不可用"
);
toolRegistry.register(tool);
ToolResult<String> result = toolExecutor.execute(tool, Map.of("message", "hello"));
toolRegistry.unregister("echo");
```

缓存适合只读工具；存在副作用的工具应使用 `Duration.ZERO` 禁用缓存。缓存结果应为不可变对象。

### ChromaDB 不是 Java 版主检索源

Java 版保留可选 ChromaDB 容器（`docker compose --profile chroma up -d`），并引入了 Spring AI Chroma VectorStore starter，但当前 profile 中排除了 Chroma VectorStore 自动配置，主检索仍然走本地 Hybrid RAG。默认 Compose 启动不再依赖 ChromaDB 健康检查。

原因：

- DeepSeek Chat starter 不提供 EmbeddingModel。
- 当前实现优先保证 DeepSeek / Anthropic 都能启动和运行。

后续可增强为：

```text
ChromaDB VectorStore semantic search
  + BM25 keyword recall
  + RRF / weighted fusion
  + LLM rerank
```

### CLI 模式

CLI 与 `/chat` 共用 `ChatService`，支持记忆、知识检索、Agent 路由和回答校验。需要 JDK 21；Redis 可用时支持工作记忆，无真实模型 Key 时使用已有的本地降级回复。

```bash
./mvnw clean package
# Redis 使用本仓库 Compose 的宿主机映射端口时：
REDIS_PORT=6380 java -jar target/flux-agent-0.1.0.jar --cli --user-id=demo
```

也可使用 `./mvnw spring-boot:run -Dspring-boot.run.arguments="--cli --user-id=demo"`。

输入消息开始对话；`/new` 新建会话，`/help` 查看帮助，`/exit` 或 EOF 退出。可用 `--conversation-id=已有会话ID` 续聊。CLI 不启动 HTTP 监听，退出时关闭 Spring 上下文与工具执行线程。

### 监控

- 成功率、平均延迟阈值告警，并按照相同配置调整路由惩罚。
- 使用新增请求的区间平均延迟计算 Z-score；默认保留 30 个样本，至少 10 个样本后检测，阈值为 3。
- `/monitor` 提供 `active_alerts` 和最多 100 条 `resolved_alerts`；恢复后允许再次触发，触发和恢复均可发送 Webhook。
- `echomind_chat_duration_seconds_bucket`：HTTP / CLI 完整同步聊天流程耗时直方图，按 `outcome` 区分成功/异常。
- `echomind_tool_success_rate`：按调用量加权的工具成功率。

配置项：`MONITOR_ANOMALY_WINDOW_SIZE`、`MONITOR_ANOMALY_MIN_SAMPLES`、`MONITOR_ZSCORE_THRESHOLD`、`MONITOR_SUCCESS_RATE_THRESHOLD`、`MONITOR_LATENCY_MS_THRESHOLD`、`ALERT_WEBHOOK_URL`。Z-score 的最小样本数应不超过窗口大小。

## 主要接口

默认端口：`8080`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查 |
| POST | `/chat` | 主对话接口 |
| POST | `/search` | 知识库检索 |
| POST | `/knowledge/add` | 批量添加知识文档 |
| POST | `/knowledge/upload` | 上传 `.txt` / `.md` / `.json` 文件 |
| GET | `/knowledge/stats` | 知识库统计 |
| GET | `/monitor` | 监控摘要与告警恢复记录 |
| GET | `/skills` | Skills 加载摘要 |
| POST | `/skills/reload` | 重新扫描 Skills |
| GET | `/metrics` | Prometheus 指标，兼容 Python 版路径 |
| GET | `/actuator/prometheus` | Spring Actuator Prometheus 指标 |
| POST | `/eval/run` | 运行评测 |
| GET | `/docs` | Swagger UI，可在线调用接口 |
| GET | `/v3/api-docs` | OpenAPI JSON |

Java 版配置了 Jackson `SNAKE_CASE`，响应字段会输出为：

```json
{
  "conv_id": "...",
  "response": "...",
  "intent": "...",
  "agent_type": "...",
  "escalated": false,
  "latency_ms": 123,
  "knowledge_used": true,
  "verified": true,
  "grounded": true
}
```

和 Python 版主要差异：

- Python 使用 `conv_id`。
- Java 响应使用 `conv_id`，请求兼容 `conv_id` 和 `conversation_id`。
- Java 额外返回 `verified` 和 `grounded`。

推荐请求字段：

```json
{
  "message": "我想申请退款",
  "user_id": "u1001",
  "conversation_id": "optional-conversation-id"
}
```

## 本地运行

### macOS / Linux

准备环境：

- JDK 21 或更高版本
- Docker Desktop 或 Docker Engine

启动依赖：

```bash
docker compose up -d redis
```

DeepSeek 启动：

```bash
export REDIS_PORT=6380
export SPRING_PROFILES_ACTIVE=deepseek
# 在项目根目录的 .env 中填写 DMX_API_KEY
./mvnw spring-boot:run
```

Anthropic 启动：

```bash
export REDIS_PORT=6380
export SPRING_PROFILES_ACTIVE=anthropic
export ANTHROPIC_API_KEY=your_key
./mvnw spring-boot:run
```

健康检查：

```bash
curl http://localhost:8080/health
```

Swagger：

```text
http://localhost:8080/docs
```

### Windows PowerShell

启动依赖：

```powershell
docker compose up -d redis
```

DeepSeek 启动：

```powershell
$env:REDIS_PORT="6380"
$env:SPRING_PROFILES_ACTIVE="deepseek"
# 在项目根目录的 .env 中填写 DMX_API_KEY
.\mvnw.cmd spring-boot:run
```

Anthropic 启动：

```powershell
$env:REDIS_PORT="6380"
$env:SPRING_PROFILES_ACTIVE="anthropic"
$env:ANTHROPIC_API_KEY="your_key"
.\mvnw.cmd spring-boot:run
```

健康检查：

```powershell
curl http://localhost:8080/health
```

Swagger：

```text
http://localhost:8080/docs
```

## Docker 部署

复制配置：

```bash
test -f .env || cp .env.example .env
```

Windows PowerShell：

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

编辑 `.env`，选择模型 profile 并填写对应 API Key。

启动：

```bash
docker compose up -d --build
```

查看状态：

```bash
docker compose ps
```

查看日志：

```bash
docker compose logs -f echomind-java
```

停止：

```bash
docker compose down
```

Compose 服务和端口：

| 服务 | 容器名 | 地址 |
|------|--------|------|
| Java App | `echomind-java-app` | `http://localhost:8080` |
| Nginx | `echomind-java-nginx` | `http://localhost:8081` |
| Prometheus | `echomind-java-prometheus` | `http://localhost:9091` |
| Grafana | `fluxagent-grafana` | `http://localhost:3000` |
| ChromaDB（可选 profile） | `echomind-java-chromadb` | `http://localhost:8002` |
| Redis | `echomind-java-redis` | `localhost:6380` |

常用验证：

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
curl http://localhost:8080/metrics
```

Swagger：

```text
http://localhost:8080/docs
http://localhost:8081/docs
```

## API 示例

### 对话

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "我想申请退款，订单号是 #12345",
    "user_id": "u1001"
  }'
```

也可以打开 Swagger UI，通过页面直接调用：

```text
http://localhost:8080/docs
```

### 检索

```bash
curl -X POST "http://localhost:8080/search?query=退款多久能到账&topK=3"
```

### 添加知识库

```bash
curl -X POST http://localhost:8080/knowledge/add \
  -H "Content-Type: application/json" \
  -d '{
    "documents": [
      {
        "title": "退款补充政策",
        "content": "大促期间退款审核时间可能延长到 3-5 个工作日。"
      }
    ]
  }'
```

### 上传知识库文件

```bash
curl -X POST http://localhost:8080/knowledge/upload \
  -F "file=@docs.md"
```

### 运行评测

```bash
curl -X POST http://localhost:8080/eval/run \
  -H "Content-Type: application/json" \
  -d '{}'
```


## 开发验证

完整的操作步骤、预期结果和真实模型验收方法见 [功能测试指南](docs/功能测试指南.md)，覆盖 Swagger、聊天、知识库、Redis 多轮记忆、CLI、Skills、监控和评测。

```bash
./mvnw clean verify
```

测试覆盖公共工具执行、缓存与熔断、知识检索、告警、CLI、聊天流程、记忆压缩、上传校验与 HTTP 接口。集成测试使用受控的模型与记忆替身，不要求真实 API Key 或 Redis 服务。真实模型回答质量、Redis 服务联通性与容器部署需要在对应环境中验证。

`/search` 返回 `success` 和 `error`，用于区分无结果与检索降级。非法参数返回 HTTP 400，超出上传限制返回 HTTP 413；文件仅允许 `.txt`、`.md`、`.json`（扩展名大小写不敏感），最大 10MB。

镜像已包含 `skills/` 及健康检查所需的 curl。Compose 加载 `.env`；直接运行 Java/Maven 时会可选读取当前工作目录的 `.env`，也支持导出环境变量。
