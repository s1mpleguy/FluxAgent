# 监控访问与看板补全需求

## 授权

依据用户“帮我修复 http://localhost:9091/graph 无法正常访问的问题，另外帮我完善一下系统缺失的模块”，在现有工作区实现并验证。本文件记录执行范围，不冒充用户对文档的额外确认。

## 问题、复现与证据

期望：本地浏览器可访问监控图表并看到 FluxAgent 指标。
实际：9091 连接被拒绝；8080 /health 和 /metrics 为 HTTP 200。lsof 无 9091 监听，command -v 未找到 Docker/Prometheus/Grafana。docker-compose.yml 仅声明映射，不会随 Java 进程启动。

## 图谱辅助分析

codegraph_index_status：ready，77 文件、1454 节点、2408 边，无待同步变更。
codegraph_explore：PerformanceMonitor.collect → collectMetrics → AgentOrchestrator.stats / ToolExecutor.stats → Micrometer；FluxAgentController.metrics → PrometheusMeterRegistry.scrape。ToolExecutor 使用注册项 ToolStats；缺少缓存命中计数。影响监控采集和工具统计，需回归 PerformanceMonitorTest、ToolExecutorTest 及 HTTP 集成测试。

## 修复后的行为

Requirement：本地监控可重复启动、停止和检查，并明确区分页面健康与应用采集成功。
Scenario：Given Java 运行于 8080 且监控依赖已安装，When 执行 start，Then /graph 可访问，Prometheus target 为 up，Grafana 自动加载看板；重复 start 不新增进程。
Scenario：Given 端口被无关进程占用或 PID 文件指向其他进程，When start/stop，Then 返回明确错误且不终止无关进程。

Requirement：提供自动配置的监控看板和容器部署支持。
Scenario：Given 本地或 Compose 启动，When 打开 Grafana，Then 不需手工添加数据源/导入 JSON，能查看真实指标；无业务请求时不伪造质量或耗时数据。

Requirement：导出每 Agent/工具的调用、成功率、平均耗时、缓存命中和熔断状态及告警数量。
Scenario：Given 注册项统计发生变化或工具注销，When 采集，Then 数值更新，注销后的指标移除；缓存命中不触发真实处理器；旧字段保持兼容。

## 验收与兼容

执行 Java 回归、Python 测试、promtool 规则/配置检查、Grafana provisioning 和真实 HTTP/PromQL 验证。新数据不写到线上模型评测基线，不调用付费模型构造监控数据。不自动提交或推送本次代码。

## 待决议清单

无当前实现阻塞项；不明确的新业务模块列入 docs/模块现状与后续规划.md。
