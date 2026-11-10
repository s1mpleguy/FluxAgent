# 实施计划

授权：用户请求修复并补全，按设计中的监控范围连续实现。

1. [已完成] 补充细分指标和缓存统计的回归测试，记录预期失败后实现并通过。45 项 Java 测试通过，见 verification/build-results.txt。
2. [已完成] 实现本地监控服务脚本、生命周期保护测试及本地 Prometheus 配置。9 项 Python 测试通过，含真实中断、TIME_WAIT 和数据源断链回归。
3. [已完成] 实现 Grafana provisioning、22 个面板、四条告警规则及 Compose 配置。PromQL、配置、告警触发/恢复及低流量保护通过；本机无 Docker，容器运行边界如实记录。
4. [已完成] 启动原生 Prometheus/Grafana，验证页面、抓取和看板查询，检查故障/重复启动行为。9091 与 3000 浏览器可访问，应用 target 为 up，Grafana 数据源查询成功；重复启动与快速 stop/start 通过，见 verification/runtime-results.txt。
5. [已完成] 补充运行文档、模块现状、修改日志；完成构建、测试、检查和代码审查。文档及验证记录已补充，独立复审提出的三项 P2 已复现修复；本次实现和验证已完成，未记录人工终审或执行 Git 提交。

2026-11-10 收尾：用户明确要求“推送到远程仓库吧”，授权交付本次本地修改。复验 45 Java、9 Python、配置及告警通过；实际 8080 已停止的运行状态记录在 verification/runtime-results.txt。按当前分支提交并普通 push 到 origin/feat/complete-backend-capabilities。
