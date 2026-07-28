# 变更记录

本文件记录用户可见行为。正式发布后按版本分节，并区分 Added、Changed、Fixed、Security 和
Known limitations；提交历史不能替代发布说明。

## 1.0.0-alpha-SNAPSHOT（开发中）

### Added

- Java 8 / Servlet 3.1 / `javax.servlet` 的 1.x Maven 多模块基线。
- 项目采用 Apache License 2.0，版权主体使用匿名的 `TinySC contributors`。
- shaded launcher 固定携带项目 `LICENSE`、`NOTICE`、第三方许可证清单以及 ASM 和
  Java Servlet API 的完整许可证文本，避免同名 `META-INF` 资源随机覆盖。
- Netty HTTP/1.1 connector、有界 worker、严格请求校验、事务式端口开放，以及连接/请求/字节/原始入站容量和传输时限控制。
- WAR 检查、安全展开、SHA-256 缓存、`web.xml` 解析和 WebApp 类加载器。
- Servlet、Filter、Listener、SCI、Session、基础 Async 与 RequestDispatcher 链路。
- 有界 multipart 基线：支持 `web.xml` 与 SCI 动态配置、`Part`、普通字段参数合并、文件/请求/Part
  Header 限额、磁盘阈值和请求结束清理；解析器直接读取现有请求流，避免额外复制完整 body。
- 已注册 Servlet 可用 `@MultipartConfig` 提供缺省配置；`web.xml` / SCI 显式配置整体优先，
  不进行字段级拼接，也不引入全 WAR 注解扫描或启动期扫描成本。
- `tinysc inspect` namespace/bytecode 建议与 `tinysc start` 启动命令。
- Probe WAR 端到端测试、Tomcat 同机基准脚本及两个真实 WAR 的分阶段验收报告。
- shaded launcher 的 Java 8 class-major 与 1.x namespace 自动发布门禁。
- Servlet 3.1 Resource JAR 挂载：支持 `WEB-INF/lib/*.jar!/META-INF/resources` 的静态访问、
  ServletContext 资源 API、欢迎页和 Web 根目录优先级。
- 启动期即生效的终端/文件双写日志：默认保存到 `--base/logs/tinysc.log`，按 64 MiB
  轮转并保留 5 份备份。
- 独立 HTTP access log：CLI 默认启用，可用 `--access-log false` 关闭；嵌入式 `ServerConfig`
  默认关闭，日志写入 `<base>/logs/access.log`，按 64 MiB / 5 份备份轮转，使用独立单线程、
  有界队列 8192，满队列时非阻塞丢弃并按 2 次幂告警，停止时会 drain 并 flush；每行字段为
  `ts remote method path proto status bytes durUs ka outcome`，其中 `ts` 是 epoch millis，`ka`
  是 `0/1`。

### Changed

- 未显式指定 `--base` 时使用当前工作目录，使本地开发日志与缓存分别进入工程
  `logs/` 和 `work/`。
- 多核环境默认 I/O 线程数由 2 调整为 `min(4, CPU)`，以两条额外 event-loop 线程换取更少的
  HTTP 解码、响应写回和 access-log producer 竞争；仍可通过 `--io-threads` 明确覆盖。
- worker 从固定线程池改为有界弹性池：`--workers` 作为上限，`--min-workers` 默认
  `min(2, --workers)`，新增 `--worker-idle-timeout`，默认队列容量由 1024 收紧为 100；同时新增
  `--max-connections` 默认 `1024`、`--max-inflight-request-bytes` 默认 `64 MiB`、
  `--max-raw-ingress-bytes` 默认 `64 MiB`、`--request-read-timeout` 默认 `30000`、
  `--request-body-timeout` 默认 `300000` 和 `--response-write-timeout` 默认 `30000`，
  `maxInflightRequests` 由 `workerThreads + workerQueueCapacity` 推导。连接超限直接关闭；没有更早
  响应在途时，请求数、请求体字节或 raw ingress 预算超限返回 `503` 并关闭连接，请求体总时限
  超时返回 `408`。若失败属于已有响应在途时的后续 pipelined 请求，为保持响应顺序，不插入新的
  错误响应，而是在当前响应完成后关闭连接。
- 请求体的 raw ingress 语义已改为按实际到达字节占用并精确释放：`Content-Length` 只做单请求
  上限的早期 `413`，chunked 按实际分片累计，断开/超时/失败都会释放已占用额度。
- 读取/正文总时限/响应写超时已经拆开，且同一 HTTP/1.1 channel 上的 pipelined 请求会按顺序
  继续，不再抢占当前响应。
- zero-byte request 路径跳过 RequestAdmission 的 0 字节 byte-budget CAS 和 RawIngressAdmission 的
  `tryReserve(0)` 锁；请求计数与 raw reservation lease 生命周期仍保留。
- access log 对常见安全字段整体追加，减少逐字符判断和追加；清洗、日志字段、轮转、drain / flush
  与满队列处理语义不变。

### Fixed

- 有界 worker 队列在瞬时满载的线程交接窗口会额外等待最多 `1 ms` 再判定饱和，避免已有
  worker 正在接手任务时误发 `503`；真正持续饱和仍保持有界并快速拒绝，中断状态会保留。
- 未映射静态请求现在进入匹配的 Filter 链，静态 forward 按 FORWARD dispatcher 重新匹配。
- 真实 Legacy WAR 的无后缀资源路由可 forward 到 Resource JAR 内 HTML，不再落入容器 404。
- JAR 静态资源的 GET/HEAD 和 ServletContext stream 在关闭后同步释放 JarFile，避免请求累计耗尽
  文件描述符。
- 同一 HTTP/1.1 keep-alive channel 在前一个响应 flush 完成后再继续读取后续请求。
- 未映射静态资源的 `POST` 现在按 Tomcat 8.5.100 `DefaultServlet` 语义读取现有文件或 Resource JAR；
  缺失资源仍返回 `404`，已有 Servlet 映射仍优先，修复 Legacy 页面加载 `.tpl` 时的 `405`。
- 文件系统和 Resource JAR 静态资源的 `GET` / `HEAD` 现在支持 `If-Modified-Since`，未修改时返回
  `304`；静态 `POST` 仍按 Tomcat 8.5.100 语义返回资源体，不套用该条件请求捷径。

### Verification

- 当前分支在 Java 8 下执行 `mvn -B -ntp clean verify`，共 177 项测试通过。
- 同一 Probe WAR 在 TinySC 与 Tomcat 8.5.100 上的 XML 9-byte 覆盖与注解-only 7-byte 上传均返回
  相同 `200` 响应体；注解 9-byte 和 XML 65-byte 超限时两边均返回 `500`。证据见
  [multipart Probe WAR 差分验收](acceptance/2026-07-28-multipart-probe-differential.md)。
- 2026-07-22 最终候选的性能对照仍对应当时的 157 项测试，尚未将 multipart 基线纳入重测。
- 最终候选在并发 32/128 下各完成 5 个独立进程的同机同参交替对照，两组均 0 失败；吞吐分别为
  Tomcat 的 93.77% 和 91.01%。它只满足当前可回滚切换的 90% 安全门槛，仍未满足 1.0 的吞吐
  持平和 RSS 低 30% 门槛；完整数据见
  [最终验证矩阵](benchmarks/2026-07-22-final-validation-matrix.md)。
- Legacy WAR B 的认证后关键路径连续两轮通过 L2；业务 `page_load` 仍约 1.36–3.03s，冷启动
  183,945ms 且主要由 Spring/数据库初始化主导，不构成广义性能声明。其授权切换已通过，但带有
  资源漂移证据保留项。

### Known limitations

- 尚未完成 `@WebServlet` 等全 WAR 注解发现、流式上传、异步 dispatch、Servlet 非阻塞
  ReadListener/WriteListener、真正流式响应写、error-page、安全约束、web-fragment 和 TCK。
- HTTPS、HTTP/2、健康端点、配置文件和生产长稳门禁尚未完成。
- 10 分钟 soak 未完整保存线程/FD 漂移汇总，1 小时和 24 小时长稳仍未执行。
- Legacy WAR A 仍被外部数据库连接超时阻断；Legacy WAR B 只完成报告所列关键路径 L2，上传、
  错误页、全部管理动作、长事务和完整 L3 差分仍未覆盖。
- 最终候选仍不支持“内存低 30%”“吞吐不低于或高于 Tomcat”或“广义更快”的发布声明。
- raw ingress 仍只有全局预算和单请求体上限，没有独立的每连接公平份额；单连接可以占用全局
  预算，但不能突破全局硬边界。
- `maxInflightRequestBytes` 只约束已经聚合且仍被保留的请求体字节，不保护 streaming/raw ingress。
- `requestBodyTimeoutMillis` 只在没有更早响应在途时返回 `408` 并关闭连接；`responseWriteTimeoutMillis`
  只是在写不完成时关闭连接。
- 当前 response 仍全量堆缓冲，Servlet `WriteListener` / `isReady` 还不是 true non-blocking write。
- 现有 smoke 和 benchmark 结果只用于状态验证或预备对照，不构成正式性能声明。
