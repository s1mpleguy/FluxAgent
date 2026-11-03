# FluxAgent 命名与功能测试实施计划

执行依据：用户授权本次更名与测试指导；不含新的提交、推送或业务功能开发。

| 工作 | 状态 | 验收证据 |
| --- | --- | --- |
| 现有测试迁移到新命名 | completed | 更新 src/test/java 的包/引用；测试编译因旧生产命名失败 |
| 生产代码与 Maven 入口更名 | completed | 新包、类、Bean 与入口正确；./mvnw -o clean verify |
| 功能测试指南与当前文档同步 | completed | docs/功能测试指南.md、README、工程结构和 CHANGELOG |
| 打包冒烟、图谱与最终审查 | completed | Jar HTTP/CLI、完整回归、剩余旧名与凭据检查；verification/ 与 review/ |

本轮沿用既有行为测试，不新增仅断言类名的重复测试。

实际证据：
- 测试迁移：verification/red.txt 记录预期失败；verification/test-results.txt 记录 42 项回归和 20 项 HTTP/CLI 冒烟。
- 生产更名与构建：verification/build-results.txt，Jar 入口及包目录正确。
- 当前文档：docs/功能测试指南.md、README.md、rules/工程结构.md、CHANGELOG.md；命令语法与路径检查见 verification/lint-results.txt。
- 最终检查：verification/codegraph-results.txt 与 review/self-review.md。人工终审未代签，本轮没有提交或推送。

后续 Git 授权（2026-11-03T16:04:06+08:00）：用户明确要求“帮我提交并推送这次修改”，授权将本次更名、指南和相关记录提交到当前分支并推送其远端。此前“未提交/推送”描述为实施交付时的状态，本次据新指令执行。不扩展业务范围。
