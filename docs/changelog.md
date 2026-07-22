# 变更记录

本文件记录用户可见行为。正式发布后按版本分节，并区分 Added、Changed、Fixed、Security 和
Known limitations；提交历史不能替代发布说明。

## 1.0.0-alpha-SNAPSHOT（开发中）

### Added

- Java 8 / Servlet 3.1 / `javax.servlet` 的 1.x Maven 多模块基线。
- Netty HTTP/1.1 connector、有界 worker、严格请求校验和事务式端口开放。
- WAR 检查、安全展开、SHA-256 缓存、`web.xml` 解析和 WebApp 类加载器。
- Servlet、Filter、Listener、SCI、Session、基础 Async 与 RequestDispatcher 链路。
- `tinysc inspect` namespace/bytecode 建议与 `tinysc start` 启动命令。
- Probe WAR 端到端测试、Tomcat 同机基准脚本及两个真实 WAR 的分阶段验收报告。
- shaded launcher 的 Java 8 class-major 与 1.x namespace 自动发布门禁。
- Servlet 3.1 Resource JAR 挂载：支持 `WEB-INF/lib/*.jar!/META-INF/resources` 的静态访问、
  ServletContext 资源 API、欢迎页和 Web 根目录优先级。
- 启动期即生效的终端/文件双写日志：默认保存到 `--base/logs/tinysc.log`，按 64 MiB
  轮转并保留 5 份备份。

### Changed

- 未显式指定 `--base` 时使用当前工作目录，使本地开发日志与缓存分别进入工程
  `logs/` 和 `work/`。
- worker 从固定线程池改为有界弹性池：`--workers` 作为上限，新增 `--min-workers` 与
  `--worker-idle-timeout`，默认队列容量由 1024 收紧为 100；线程与队列同时饱和时仍返回 503。

### Fixed

- 未映射静态请求现在进入匹配的 Filter 链，静态 forward 按 FORWARD dispatcher 重新匹配。
- 真实 Legacy WAR 的无后缀资源路由可 forward 到 Resource JAR 内 HTML，不再落入容器 404。
- JAR 静态资源的 GET/HEAD 和 ServletContext stream 在关闭后同步释放 JarFile，避免请求累计耗尽
  文件描述符。

### Known limitations

- 尚未完成 multipart、异步 dispatch、非阻塞 I/O、error-page、安全约束、web-fragment、注解声明和 TCK。
- HTTPS、HTTP/2、访问日志、健康端点、配置文件和生产长稳门禁尚未完成。
- Legacy WAR A 仍被外部数据库连接超时阻断；Legacy WAR B 的登录和 API 验收受本机 License
  门禁阻塞。
- 预备基准尚不支持“内存低 30%”或“尾延迟优于 Tomcat”的发布声明。
