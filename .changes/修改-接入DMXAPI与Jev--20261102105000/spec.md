# DMXAPI 与 Jev 技术方案（后端修改类）

## 人工确认
用户已授权配置服务地址与两个模型；未发生单独文档审批。

## 需求概述与目标业务行为
- Requirement：默认 DeepSeek 使用用户指定的 DMXAPI 地址、模型及本地密钥；Jev 用于意图识别。
- Scenario：从项目目录启动 Java/Maven 时自动读取 .env；Compose 继续通过 env_file 注入。
- Scenario：Jev 返回合法高置信度意图时直接使用结果，不再调用聊天模型做同一次分类。
- Scenario：Jev 关闭、缺少密钥、超时、HTTP 错误、响应格式异常、未知分类或低置信度时，回退现有 LLM/规则识别；不记录鉴权头或原始错误响应。
- Requirement：保留请求、响应、紧急度、实体抽取和路由兼容性；主模型继续生成回复。
- Requirement：密钥不进入源码、文档、构建产物或 Git 改动。

## 现状与调用链
ChatService/AgentOrchestrator/EndToEndEvaluator -> IntentRecognizer.recognize -> llmRecognize -> LlmGateway。现有 SpringAiLlmGateway 通过 Spring AI ChatModel 调用主模型。新增 JevIntentClient 在识别器前置参与分类。

## 图谱辅助分析
使用 codegraph_index_status 与 codegraph_explore 查询 IntentRecognizer、vote、SpringAiLlmGateway 及调用方；当前索引 ready/complete。回归范围覆盖 HTTP/CLI 聊天、路由、评测和模型网关。

## 改动点与技术方案
application.yml、.env.example、Compose 默认配置与 README 同步；EchoMindProperties 增加 Jev 配置，JevIntentClient 使用有超时的 JDK HTTP 客户端与 Jackson，IntentRecognizer 接入独立决策结果。测试用本地 HTTP 服务和模型替身，隔离真实密钥。
主模型配置 completions-path=/v1/chat/completions，使用真实 Spring AI 网关连接本地 HTTP 服务验证路径与模型。Jev 使用异步 HTTP 请求加完整响应截止时间，超时取消。测试设置 ECHOMIND_ENV_FILE 关闭或替换导入，并用合成资源验证零次访问工作目录 .env。

## 验收与风险
校验协议路径、鉴权、模型名、历史上下文、分类输出及降级；测试 .env 导入和环境覆盖；执行 Maven verify。使用合成消息对两个真实服务做有限冒烟，不等同于中文业务质量验收。

## 待决议清单
无阻塞项。
