# 统一 FluxAgent 命名与功能测试指南

## 执行依据
用户要求把 EchoMind 相关类名改为 FluxAgent 风格，并说明如何测试系统。直接按本次授权实施，没有单独的文档审批，也不代签人工审查。

## 方案与选择
- 仅改三个类名：改动小，但 Java 包仍体现旧项目名称。
- 统一 Java 类名、包名、Maven 入口与用户可见品牌（采用）：代码与项目名一致，现有部署和数据保持兼容。
- 同时迁移配置、Redis、监控和容器命名：需要外部配置及存量数据迁移，本轮不包含。

## 设计
类使用 Java 大驼峰：FluxAgentApplication、FluxAgentController、FluxAgentProperties；包和 Maven groupId 使用 com.fluxagent；Bean 方法使用 fluxAgentOpenAPI。生产与测试源码一起迁移目录，更新当前 README 和工程结构中的引用。历史修改日志与 Change 证据保留原始路径。
echomind.* 配置键、ECHOMIND_* 环境变量、Redis 键、指标、容器名称及存量元数据是兼容标识，不做全局文本替换。
新增 docs/功能测试指南.md，给出不使用真实密钥的自动化测试，以及用户可执行的 HTTP、CLI、Swagger、知识库、多轮记忆和评测操作及预期结果。

## 待决议清单
无阻塞项。本轮是命名重构与文档，不新增业务行为。
