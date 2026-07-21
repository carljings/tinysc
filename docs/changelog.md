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

### Known limitations

- 尚未完成 multipart、异步 dispatch、非阻塞 I/O、error-page、安全约束、web-fragment、注解声明和 TCK。
- HTTPS、HTTP/2、访问日志、健康端点、配置文件和生产长稳门禁尚未完成。
- 两个内部 Legacy WAR 均被外部数据库连接超时阻断，尚未完成端口访问验收。
- 预备基准尚不支持“内存低 30%”或“尾延迟优于 Tomcat”的发布声明。
