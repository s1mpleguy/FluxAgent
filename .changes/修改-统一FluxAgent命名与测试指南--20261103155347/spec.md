# FluxAgent 命名与功能测试技术方案（后端修改类）

## 人工确认
用户已明确授权更名与测试指导；未发生单独文档审批。实现限于本轮范围。

## 需求概述与目标行为
- Requirement：Java 业务代码、测试引用和 Maven 启动入口统一使用 FluxAgent 命名。
- Scenario：从项目根目录构建 Jar 并启动 HTTP/CLI，Spring 扫描新包后，原接口继续可用。
- Requirement：保留现有接口字段、配置、数据和监控兼容性。
- Scenario：现有 echomind.* 配置、Redis 键与指标仍有效；本地密钥不被读取到测试配置或写入源码。
- Requirement：提供可复制的分层功能测试说明，说明预期结果和真实模型/Redis依赖。
- Scenario：用户先执行 Maven 自动化测试，再通过 Swagger 或 curl 验证聊天与知识库；HTTP 200 不被误认为真实模型已成功调用。

## 现状、图谱与影响范围
codegraph_index_status 返回 ready/complete；codegraph_explore 查询 EchoMindJavaApplication、EchoMindController、EchoMindProperties、echoMindOpenAPI。配置类关联 29 处引用，覆盖意图、检索、记忆、评测、监控和测试。HTTP 与 CLI 共用 ChatService。
现有包为 src/main/java/com/echomind 与 src/test/java/com/echomind；应用入口还由 pom.xml 的 mainClass 指定。

## 改动点与技术方案
- 迁移为 src/main/java/com/fluxagent 和 src/test/java/com/fluxagent，并同步 package/import。
- 将 EchoMindJavaApplication、EchoMindController、EchoMindProperties 及构造引用分别更名为 FluxAgentApplication、FluxAgentController、FluxAgentProperties。
- 更新 OpenAPI Bean 方法及当前展示文案，Maven groupId/mainClass、README 和 rules/工程结构.md。
- 新增 docs/功能测试指南.md；在 CHANGELOG.md 追加本轮记录。

## 测试与验收
先迁移现有测试引用，确认旧生产包无法满足新命名，再迁移生产源码。沿用既有 42 项行为测试，执行 ./mvnw -o clean verify。检查最终 Jar 的 Start-Class 与类目录；以临时存储做 HTTP/CLI 冒烟，检查 Swagger、健康、参数校验、知识导入/检索、聊天和指标。清点剩余旧名并明确兼容用途。
本机未安装 Docker、Redis 服务，真实 Redis 多轮存储验证不宣称已执行。真实模型测试由用户按指南启动，自动化回归不依赖付费模型。

## 待决议清单
无阻塞项。
