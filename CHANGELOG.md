# 修改日志

## 2026-11-02 — DMXAPI 与 Jev 模型接入

- 默认模型 profile 改为 DeepSeek，服务地址为 `https://www.dmxapi.cn`，主模型为 `deepseek-v4.1-flash-1017`。
- 显式配置主模型请求路径 `/v1/chat/completions`，修复 Spring AI 默认路径与 DMXAPI 不兼容导致的模型降级；新增真实 Spring AI 客户端对本地 HTTP 服务的回归测试。
- 新增 Jev 意图分类客户端，模型为 `jev-latest`，使用独立 `/typesafe/v1/systemone` 接口；返回合法且达到置信度门槛时使用分类结果，超时、异常或低置信度时回退原有识别逻辑。
- 支持从工作目录自动加载本地 `.env`，两个模型默认共用 `DMX_API_KEY`；真实密钥不写入版本库或镜像。同步配置示例、Compose 和使用说明。
- 新增 Jev 协议及降级、识别器集成、模型配置加载测试，现有 HTTP 集成测试隔离本地凭据与真实 Jev 请求。
- Jev 的超时覆盖完整响应体读取，超时后取消请求并回退；新增响应头已到达但正文停滞的回归测试。
- 通过 `ECHOMIND_ENV_FILE` 控制本地配置导入，修复仅清空 `spring.config.import` 仍会加载 `.env` 的测试隔离问题。
- 验证：`./mvnw -o clean verify` 于 2026-11-02 11:01:32（Asia/Shanghai）成功，42 项测试全部通过；最终 Jar 的真实 `/chat` 请求同时使用主模型和 Jev，HTTP 200、`intent_source_scores.jev=1.0`，无主模型降级。独立复审未发现新的待修复问题。
- 提交前复验：`./mvnw -o verify` 于 2026-11-02 20:13:16（Asia/Shanghai）成功，42 项测试全部通过。

## 2026-11-01 — 后端能力补全与稳定性修复

### 新增

- CLI 对话入口：通过 `--cli` 启动，支持指定用户、续聊及 `/help`、`/new`、`/exit`、`/quit` 命令，与 HTTP 接口共用 `ChatService`。
- 通用工具定义、注册与执行机制：支持 JSON Schema 2020-12 参数校验、有界线程池、超时取消、TTL 缓存、熔断恢复、降级和调用统计；知识检索已接入。
- 监控告警恢复记录、基于滑动窗口的 Z-score 延迟异常检测、完整聊天流程耗时直方图，以及按调用量加权的工具成功率指标。
- 30 项自动化测试，覆盖 HTTP、聊天、CLI、工具、知识检索、监控及记忆压缩；新增 `rules/工程结构.md` 和 `.changes/` 下的设计、实施、验证及审查记录。

### 修复

- 知识检索失败不再伪装成功；修正重排状态和候选补全，导入知识后通过数据版本更新缓存，避免继续读取旧结果。
- 降级结果不计为工具执行成功且不进入缓存；工具重新注册后不复用旧状态。
- 熔断结果绑定请求许可及代次，防止旧请求完成时干扰新的恢复探测。
- 告警恢复后可以再次触发；监控快照保持不可变，路由惩罚使用配置阈值，异常检测使用原始累计耗时，避免舍入误报。
- Redis 记忆压缩使用列表裁剪，保留消息时间顺序。
- 非法参数和知识文件返回 HTTP 400，超限上传返回 HTTP 413；统一 10MB 文件限制并为 multipart 请求预留开销。

### 调整

- 保留 `conv_id` 响应字段，请求兼容 `conv_id` 和 `conversation_id`；检索响应提供 `success` 和 `error`。
- Docker 镜像包含 Skills 和健康检查所需的 curl，正确转发启动参数；ChromaDB 改为可选 Compose profile。
- Maven 显式指定 UTF-8 编码和 Spring Boot 入口；README 补充 CLI、工具、监控及测试说明，修正 Redis 宿主机端口示例。

### 验证与边界

- `./mvnw -o clean verify`：30 项测试通过，无失败、错误或跳过，生成可执行 Jar。
- 提交前再次执行 `./mvnw -o verify`：2026-11-01 17:47:40（Asia/Shanghai）构建成功，30 项测试全部通过。
- 已完成 Anthropic / DeepSeek profile 的 CLI 冒烟、HTTP 接口及指标冒烟；独立代码审查发现的两项问题已修复并通过回归验证。
- 模型验证使用占位密钥和本地降级；真实模型质量、真实 Redis 服务及 Docker 容器运行尚未实测。
- ChromaDB 主检索、远程 MCP 协议和前端界面仍属后续扩展，本次未实现。
