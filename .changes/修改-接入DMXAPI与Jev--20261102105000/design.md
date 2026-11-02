# DMXAPI 与 Jev 接入设计

## 执行依据
用户明确指定 DMXAPI 地址、主模型、Jev 模型与 API Key；延续先前讨论的 Jev 意图识别用途。本轮直接实施，不伪造单独的文档审批。

## 方案比较与选择
- 仅写入环境变量：能切换主模型，但仓库没有 Jev 调用入口，无法实际使用 Jev。
- 在现有 Spring AI DeepSeek 网关之外增加独立 Jev 意图客户端（采用）：保持回复生成流程，用 Choice 判断意图，失败或低置信度回退既有识别器。
- 同时替换重排、校验和评测：涉及更多行为与阈值，本轮不扩大到这些用途。

## 数据与配置
密钥仅写入 Git/Docker 忽略的本地 .env，权限 0600。应用启动从工作目录可选加载 .env，进程环境变量可覆盖。默认 profile=deepseek，主模型 deepseek-v4.1-flash-1017，Jev 模型 jev-latest，服务地址 https://www.dmxapi.cn。
Jev 使用 /typesafe/v1/systemone；questions 为按问题名称组织的对象，Choice 使用 instructions 与 criteria。必须验证 choice 和 confidence，不从缺失字段制造成功。
主模型显式使用 /v1/chat/completions；ECHOMIND_ENV_FILE 可替换或关闭本地文件导入。Jev 的超时覆盖完整 HTTP 响应，截止后取消尚未完成的请求。

## 待决议清单
无阻塞项。Jev 置信度门槛为可配置的初始值，中文业务准确率需后续用标注样本评估。
