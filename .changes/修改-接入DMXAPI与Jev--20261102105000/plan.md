# DMXAPI 与 Jev 实施计划

执行依据：用户已授权本次配置与模型接入；不自动提交或推送。

| 工作 | 状态 | 验收与证据 |
| --- | --- | --- |
| Jev 客户端协议与降级测试（RED） | completed | src/test/java/com/echomind/intent/JevIntentClientTest.java；./mvnw -o -Dtest=JevIntentClientTest test |
| Jev 客户端与配置（GREEN） | completed | HTTP 协议、输出校验、失败降级测试通过 |
| 意图识别集成测试（RED） | completed | src/test/java/com/echomind/intent/IntentRecognizerTest.java；Jev 优先、原逻辑兜底 |
| 识别集成与启动配置（GREEN） | completed | 保持原输出契约；本地凭据加载与测试隔离 |
| 文档、完整验证及自审 | completed | ./mvnw -o clean verify：42 项通过；最终 Jar 真实主模型/Jev 冒烟通过；verification/ 下四类记录与 review/self-review.md |

客户端验证：首轮缺少 JevIntentClient/getJev 编译失败；实现后 JevIntentClientTest 5 项通过，退出码 0。

集成验证：新增构造依赖前测试编译失败；接入后 Jev、识别器、配置及 HTTP 集成共 14 项测试通过，退出码 0。

补充回归：真实 Spring AI 路径测试先返回 HTTP 404，配置 /v1/chat/completions 后通过。独立审查发现 Jev 正文读取超时和 .env 测试隔离问题，均先复现失败后修复；最终全量构建于 2026-11-02 11:01:32（Asia/Shanghai）通过 42 项测试。最终 Jar 聊天 HTTP 200，Jev 来源分数 1.0，无主模型降级；复审无新增待修复问题。未提交或推送。

提交授权更新（2026-11-02）：用户明确要求“提交这次更改并看一下项目还有哪些功能是可以做的”。本次模型接入修改进入本地提交范围，不含 push、merge 或部署；功能扩展仅作分析。提交前 ./mvnw -o verify 通过 42 项测试，详见 verification/。该记录保留用户实际授权，不代签独立人工审查结论。
