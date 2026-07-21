# Tomcat 源码分类与 tinysc 映射

本页基于本地检出的 Apache Tomcat `main` 源码，只用于理解职责分界，
不表示 tinysc 复制 Tomcat 的类层级。

| Tomcat 区域 | 主要职责 | tinysc 对应 |
|---|---|---|
| `org.apache.coyote` | Endpoint、HTTP/AJP 协议解析、协议到容器的 Adapter | `tinysc-http-netty` + `ContainerExchange` |
| `org.apache.catalina.core` | Server/Service/Engine/Host/Context/Wrapper 与生命周期 | 单个 `TinyScServer` + `WebAppRuntime` |
| `org.apache.catalina.startup` | 配置解析、Host/Context 部署 | `tinysc-launcher` + `tinysc-deployment` |
| `org.apache.catalina.loader` | WebApp 类加载 | `WebAppClassLoader` |
| `org.apache.catalina.mapper` | Host、Context、Wrapper 映射 | 单 context 的 `ServletMapper` |
| `org.apache.catalina.session` | Session 生命周期与存储 | `TinySessionManager` |
| `org.apache.catalina.connector` | Servlet Request/Response facade | `tinysc-servlet-javax` facade |
| `org.apache.jasper` | JSP 编译和运行 | 1.0 基础包不包含；后续可选模块 |
| `org.apache.naming` | JNDI/Naming | 1.0 不提供完整 JNDI |
| `org.apache.juli` | 容器日志 | 先使用 JDK logging，避免额外日志绑定 |
| `test/webapp-*` | 多 Servlet 版本与部署契约夹具 | `tinysc-testapp-javax` 与集成测试 |

## 保留的经验

- 协议接入与 Servlet 语义之间存在明确适配边界。
- 容器组件使用显式生命周期，启动失败必须可回滚。
- WebApp 使用独立类加载器，所有回调正确设置 TCCL。
- Servlet 映射、Filter 链和部署描述符合并分别测试。
- 测试应用作为真实 WAR 运行，而不是只测 mock 对象。

## 主动舍弃的复杂度

- 不建立 Server → Service → Engine → Host → Context → Wrapper 多级容器树。
- 不支持一个进程内的虚拟主机和多应用部署。
- 不实现 AJP、集群、Realm、远程 Manager 和热部署监听。
- 不直接复用 Tomcat 私有 API，业务只依赖 Servlet 标准。
