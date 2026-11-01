# 仓库功能补全需求

## 授权

来源：用户“看一下这个代码仓库，目前他的结构是完整的吗？有没有正在开发的功能，如果有请继续补全”。在此授权下完成可由现有代码与 README 确定的补全工作。

## 功能与场景

### 连续 CLI 对话

Requirement：系统应提供 --cli 入口，与 HTTP 共用完整聊天业务，并支持用户标识、会话切换、帮助和退出。

Scenario：Given 两条连续输入，When CLI 处理，Then 复用同一会话；/new 后更换会话；空行不请求；EOF 或 /exit 后释放上下文；单次处理失败后仍可输入下一条。

### 通用工具管理

Requirement：系统应支持工具注册、注销、JSON Schema 参数校验、执行超时、熔断恢复、TTL 缓存、降级与每工具统计；knowledge_search 使用这些公共能力。

Scenario：Given 无效参数，When 执行工具，Then 参数校验拒绝且不调用执行函数。Given 失败或超时，Then 记录失败并调用降级，降级不能变成成功统计。Given 连续故障，Then 熔断限制请求，恢复期只允许一次探测。Given 已缓存查询后导入知识，Then 新查询可读取新内容。

### 监控补全

Requirement：告警应能从 active 转为 resolved 并再次触发；延迟异常基于此前的有界窗口；工具成功率和完整聊天耗时应有指标；路由惩罚应遵循配置阈值。

Scenario：Given 指标超阈值，Then 产生一次告警；恢复后 active_alerts 移除并保存 resolved 记录；再次超阈值生成新的告警。Given 稳定历史后的延迟突增，Then Z-score 告警；没有新增请求时不重复采样。

### 交付完整性

Requirement：补充自动化测试、构建验证、真实目录结构说明和使用文档；Docker 运行镜像应包含内置 Skills；知识库错误不能被当作成功检索内容。

## 可复用能力与影响范围

沿用 AgentOrchestrator、MemoryManager、AnswerVerifier、KnowledgeBaseService、ToolResult 与 DTO。调整 controller 的业务委托，不更换包名、存储格式或模型供应商。

## 图谱辅助分析

使用 codegraph_index_status、codegraph_index_ensure 和 codegraph_explore 分析当前仓库。图谱包含 58 个文件。KnowledgeBaseService.search 的调用方是 KnowledgeToolManager；KnowledgeToolManager 被 HTTP controller 和 PerformanceMonitor 使用；聊天编排目前位于 controller。提取 ChatService 后增加 CLI 调用方。

## 验收

运行新增测试并先记录预期失败，再实现；最终运行 ./mvnw clean verify、可执行 Jar 的 CLI/HTTP 冒烟，核对 Git diff。无法执行的外部环境验证必须明确记录。

## 待决议清单

无本次实现阻塞项。Chroma 主检索需要后续选择并配置 Embedding 模型。
