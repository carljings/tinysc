# 路线图

| 阶段 | 交付物 | 退出标准 |
|---|---|---|
| 1.0.0-alpha | Maven 模块、HTTP/1.1、Filter → Servlet、Probe WAR | Java 8 构建与真实 HTTP 契约通过 |
| 1.0.0-beta | WAR、web.xml、SCI、Listener、Session、类加载、部署缓存 | 两个真实 WAR 达到 L1，核心路径达到 L2，重复启动缓存可验证 |
| 1.0.0-rc | Async、非阻塞 I/O、multipart、dispatch、安全和差分 | Servlet 3.1 契约与 Tomcat 8 差分通过 |
| 1.0.0 | 长稳、资源限制、性能差分、运维、SBOM、发布制品 | P1 门禁、Tomcat 对照报告与完整兼容报告通过 |
| 2.0.0 | Java 17、Jakarta 6.1、HTTPS、HTTP/2、ALPN | Servlet 6.1 TCK 通过后才声明兼容 |

Java 21 虚拟线程仅作为 2.x 可选执行器，不提高最低运行版本。

## 2026-07-21 当前进度

- alpha 的 HTTP → Filter → Servlet 与真实 Probe WAR 门禁已通过。
- WAR 安全展开、web.xml、SCI、Listener、Session、类加载、Async 基础和 forward 已提前落地，
  但 beta 兼容面尚未完整。
- 两个内部 Legacy WAR 均通过 L0，进入 Spring 初始化后受外部数据库读超时阻塞，未达到 L1/L2。
- 首轮 Tomcat 8.5.100 对照中启动目标通过，RSS 与 p99 目标未通过。
- 下一退出条件优先是可达业务依赖下的 L1/L2、multipart/async dispatch/web-fragment，以及
  30 秒以上独立压测端复测。
