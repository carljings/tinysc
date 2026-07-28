# 路线图

| 阶段 | 交付物 | 退出标准 |
|---|---|---|
| 1.0.0-alpha | Maven 模块、HTTP/1.1、Filter → Servlet、Probe WAR | Java 8 构建与真实 HTTP 契约通过 |
| 1.0.0-beta | WAR、web.xml、SCI、Listener、Session、类加载、部署缓存 | 两个真实 WAR 达到 L1，核心路径达到 L2，重复启动缓存可验证 |
| 1.0.0-rc | Async、非阻塞 I/O、multipart、dispatch、安全和差分 | Servlet 3.1 契约与 Tomcat 8 差分通过 |
| 1.0.0 | 长稳、资源限制、性能差分、运维、SBOM、发布制品 | P1 门禁、Tomcat 对照报告与完整兼容报告通过 |
| 2.0.0 | Java 17、Jakarta 6.1、HTTPS、HTTP/2、ALPN | Servlet 6.1 TCK 通过后才声明兼容 |

Java 21 虚拟线程仅作为 2.x 可选执行器，不提高最低运行版本。

## 2026-07-22 当前进度

- alpha 的 HTTP → Filter → Servlet 与真实 Probe WAR 门禁已通过。
- WAR 安全展开、web.xml、SCI、Listener、Session、类加载、Async 基础和 forward 已提前落地，
  但 beta 兼容面尚未完整。
- multipart 基线已支持 `web.xml` / SCI 动态配置、`Part`、表单字段、限额、磁盘阈值与请求结束清理；
  当前请求体仍在传输层完整聚合，`@MultipartConfig` 注解合并和流式上传尚未完成。
- 两个内部 Legacy WAR 均通过 L0；Legacy WAR B 已达到关键路径 L2，完成登录、列表、申报、
  `page_load` 和静态模板 POST 的 Tomcat 差分，并在目标环境实际演练失败回滚后完成受控切换；
  上传、错误页和完整管理流程仍未覆盖。Legacy WAR A 仍受数据库超时影响。
- 首轮 Tomcat 8.5.100 对照中启动目标通过，RSS 与 p99 目标未通过。
- worker 已改为有界弹性池，默认 `--min-workers` 从 8 下调到 2，以减少首波创建；并验证繁忙扩容、
  容量外 `503`、过载恢复和空闲回落。该 smoke 只证明状态正确，不构成与 Tomcat 的新性能对比。
- 独立 HTTP access log 已落地，CLI 默认开启，`--access-log false` 可关闭；日志写入
  `<base>/logs/access.log`，使用独立有界队列和单线程 writer，并在关闭时 drain / flush。
- Jetty、Undertow、Tomcat 与 Netty 的资源控制设计已完成对照；连接、在途请求、在途字节预算、原始入站预算
  和 `--request-read-timeout` / `--request-body-timeout` / `--response-write-timeout` 已落地，其中
  `maxRawIngressBytes` 现在按实际到达字节增量占用，`Content-Length` 只做单请求上限早期 `413`，断开、
  超时或失败会精确释放；停止时会等待所有已接纳但仍在途的请求，包括 deferred exchange。
- 当前 response 仍全量堆缓冲，真正的流式响应写、非阻塞 `WriteListener` 和每连接 raw ingress 公平份额
  仍在后续阶段。
- 下一退出条件优先是 Legacy WAR A 在可达业务依赖下的 L1/L2、Legacy WAR B 上传与剩余关键流程、
  `@MultipartConfig` 注解合并、async dispatch/web-fragment、真正流式响应写，以及 1 小时/24 小时
  长稳和独立压测端复测。
