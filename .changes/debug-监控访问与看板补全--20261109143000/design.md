# 监控访问修复与模块补全

## 授权与范围

用户于 2026-11-09 要求修复 localhost:9091/graph，并完善缺失模块。结合前文对监控看板的讨论，本次实现监控的启动、采集、可视化和排障闭环；不迁移客服业务场景。

## 调查与选择

应用 8080 的 /health、/metrics 均返回 200，9091 无监听进程。本机为 macOS arm64，有 Homebrew，没有 Docker、Prometheus 或 Grafana。仓库只有容器网络使用的 Prometheus 配置，尚无 Grafana 或本地启动脚本。

方案一：安装 Docker 并启动整套 Compose，会重复启动正在运行的 Java 应用及依赖。方案二：只手工启动 Prometheus，可恢复页面，但下次启动仍需手工配置。采用方案三：本地原生服务管理脚本、容器部署配置和共享 Grafana provisioning，并完成细分指标；满足当前开发环境并可复用到容器部署。

## 设计

- 本地脚本支持 start/stop/status/check，生成独立本地配置，监听回环地址，保存 PID、日志和数据。校验进程身份和端口占用，重复启动不产生第二份进程；依赖缺失时提示安装方式。
- Grafana 自动加载 Prometheus 数据源和 FluxAgent 看板，覆盖请求、耗时、Agent、工具、缓存、熔断、告警和 JVM。
- Micrometer 增加按 Agent/工具分组的快照指标。保留已有指标和 /monitor 格式。只以注册的 Agent/工具名作为标签，不把用户或会话 ID 作为指标标签。
- 补充 Prometheus 告警规则、故障诊断与运行说明。质量评测、Chroma 主检索和远程 MCP 的后续范围记录到模块清单，不声称已实现。

## 验证

Java 测试验证细分指标更新、移除和缓存计数；Python 测试验证进程保护和配置选择。真实检查 /graph、Prometheus target、Grafana 健康、数据源、看板查询；使用无远程模型调用的隔离应用验证新埋点。

## 待决议清单

无当前监控交付阻塞项。
