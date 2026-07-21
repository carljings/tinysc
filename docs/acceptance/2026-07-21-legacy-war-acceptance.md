# 2026-07-21 两个 Legacy WAR 真实验收

状态：L0 通过；L1 在业务数据库初始化阶段受阻；L2 访问未执行。

> 公开版隐私说明：业务名称、本机路径、业务类名、WAR SHA-256 和本地日志指纹已删除。原始证据
> 只保留在授权的本地验收环境中。

## 验收环境

```text
JDK: Azul Zulu OpenJDK 1.8.0_322-b06
tinysc: 1.0.0-alpha-SNAPSHOT
tinysc jar SHA-256: c1e1b4cef14f4ddd4313b50f850581164f957924b387025a1f47370573d1a19a
bind: 127.0.0.1
context path: /legacy-app
business repositories: read-only
```

| 项目 | WAR 规模 | `web.xml` | 静态检查 |
|---|---:|---:|---|
| Legacy WAR A | 约 400 MiB | 3.0 | 混合依赖；描述符判定 1.x，需核验 |
| Legacy WAR B | 约 550 MiB | 3.0 | `javax.servlet`，推荐 1.x |

Legacy WAR A 的 mixed 结果来自依赖中同时存在的可选 javax/jakarta 适配类；应用的部署描述符和
实际 Spring 启动链均为 javax。两个 WAR 都包含高于 Java 8 的捆绑类，当前 Java 8 启动证明这些
类没有在已到达路径上加载，但后续仍需覆盖所有业务功能。

## 分阶段结果

| 阶段 | Legacy WAR A | Legacy WAR B |
|---|---|---|
| WAR 指纹与安全展开 | 通过，复用 SHA 缓存 | 通过，复用 SHA 缓存 |
| WebAppClassLoader | 通过已到达路径 | 通过已到达路径 |
| SCI | 发现 1 个 Spring WebApplicationInitializer | 发现 1 个 Spring WebApplicationInitializer |
| Spring 入口 | Spring Boot 2.7，根上下文进入初始化 | Spring Boot 1.5，根上下文进入初始化 |
| 第一个阻塞点 | 业务 Bean 构造期间连接数据库超时 | 业务 Bean 构造期间连接数据库超时 |
| HTTP connector | 未绑定 | 未绑定 |
| 页面/API 访问 | 未执行 | 未执行 |
| 当前等级 | L0；L1 未完成 | L0；L1 未完成 |

两个根因链最终都落到 JDBC 网络通信异常，其底层原因为：

```text
java.net.SocketTimeoutException: Read timed out
```

这属于应用外部依赖失败。tinysc 的启动事务按预期没有在 Listener 初始化完成前绑定端口，
所以不能把本次结果表述为“应用启动成功”，也不能伪造访问结果。

## 容器问题与修复

真实 WAR 迭代同时发现并修复了这些通用问题：

1. `web.xml` 的空 `<param-value/>` 应解析为空字符串，而不是部署失败。
2. 父优先 XML API 在父加载器缺少实现类时必须回退 WebAppClassLoader。
3. Listener 部分初始化失败时，只逆序销毁已经成功初始化的 ContextListener。
4. Log4j2 异步事件必须在类加载器关闭前排空并关闭对应 LoggerContext，避免清理阶段出现
   `NoClassDefFoundError`。
5. 未能停止的应用线程会被中断、解除 TCCL 引用并记录名称，不允许静默泄漏。

最终 Legacy WAR A 日志只保留数据库根因和一条 Spring shutdown hook 的 Log4j 配置警告；
Legacy WAR B 日志只保留数据库根因，并报告两个第三方后台清理线程未在清理期限内停止。该线程
清理行为仍需在 beta 前继续验证。

两次最新进程均以退出码 1 结束；指定的两个回环端口在失败后均为 `NOT_LISTENING`，日志中没有
`tinysc ready`。因此本报告没有执行或声称任何 HTTP 访问成功。

## 完成 L1/L2 的前置条件

- 使用与 Tomcat 8 验收相同且可达的数据库、配置中心、Redis/SSO 等环境。
- 不修改业务 WAR，重跑本地记录的相同 SHA-256 制品。
- Listener、Filter 和 load-on-startup Servlet 全部完成后确认 connector 才开放。
- 访问静态页、登录前首页、健康接口、Session、上传和错误页，并保存状态码/Header/Body。
- 对同一请求与 Tomcat 8 做差分；差异必须修复或通过 ADR 接受。
