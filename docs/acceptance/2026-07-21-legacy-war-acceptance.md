# 2026-07-21 两个 Legacy WAR 真实验收

状态：两者 L0 通过；Legacy WAR A 的 L1 受业务数据库阻塞；Legacy WAR B 的 L1 通过，真实
登录入口已完成浏览器验收，完整 L2 仍需认证后的业务链路覆盖。

> 公开版隐私说明：业务名称、本机路径、业务类名、WAR SHA-256 和本地日志指纹已删除。原始证据
> 只保留在授权的本地验收环境中。

## 验收环境

```text
JDK: Azul Zulu OpenJDK 1.8.0_322-b06
tinysc: 1.0.0-alpha-SNAPSHOT
tinysc artifact: current local verified build; exact SHA-256 retained privately
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
| 当前阻塞点 | 业务 Bean 构造期间连接数据库超时 | 初步访问无阻塞；完整 L2 仍需测试账号和认证后用例 |
| HTTP connector | 未绑定 | 已绑定回环地址，应用完成 ready |
| 页面/API 访问 | 未执行 | 根入口 200 并进入真实登录页；HTML、CSS、JS 均为 200 |
| 当前等级 | L0；L1 未完成 | L1；L2 登录入口切片通过 |

Legacy WAR A 的根因链最终落到 JDBC 网络通信异常，其底层原因为：

```text
java.net.SocketTimeoutException: Read timed out
```

这属于应用外部依赖失败。tinysc 的启动事务按预期没有在 Listener 初始化完成前绑定端口。

Legacy WAR B 后续在授权的可达数据库通道上完成 Spring、Shiro、Quartz、Listener、Filter 和
load-on-startup Servlet 初始化，并输出 ready。首次运行时，部署态授权解析器只扫描
`WEB-INF/lib`，而项目已有的本机授权位于 classpath 资源目录，因此旧机器授权先触发了注册页。
验收环境随后通过私密部署流程，把合法签发的本机授权按应用官方安装位置部署到运行时；没有修改
业务仓库、没有生成授权、没有隐藏开关或校验绕过，也没有把授权文件写入 tinysc 仓库。

重新启动后根入口返回应用自己的 meta refresh，并进入真实登录页。无后缀登录路由返回
`200 text/html`，关键 Resource JAR 脚本返回 `200 application/javascript`。浏览器实测登录表单
核心控件正常渲染，控制台无 warning/error。该证据证明“本地部署、完整启动、访问登录入口”
已经成立；没有测试账号时，不能进一步声称认证成功或全部业务 API 已通过。

## 容器问题与修复

真实 WAR 迭代同时发现并修复了这些通用问题：

1. `web.xml` 的空 `<param-value/>` 应解析为空字符串，而不是部署失败。
2. 父优先 XML API 在父加载器缺少实现类时必须回退 WebAppClassLoader。
3. Listener 部分初始化失败时，只逆序销毁已经成功初始化的 ContextListener。
4. Log4j2 异步事件必须在类加载器关闭前排空并关闭对应 LoggerContext，避免清理阶段出现
   `NoClassDefFoundError`。
5. 未能停止的应用线程会被中断、解除 TCCL 引用并记录名称，不允许静默泄漏。
6. 未映射和静态请求仍必须进入适用的 Filter 链，内部 forward 必须按 FORWARD dispatcher 再次
   匹配 Filter。
7. `WEB-INF/lib/*.jar!/META-INF/resources` 必须挂载为 Web 资源，且 Web 根目录优先；静态服务、
   `ServletContext.getResource`、stream、paths 和 JAR 欢迎页共用同一解析结果。

Legacy WAR A 的失败日志只保留数据库根因和一条 Spring shutdown hook 的 Log4j 配置警告。
Legacy WAR B 当前保持本机运行；数据库初始化期间仍报告业务脏数据和一个数据库序列错误，但
框架完成 ready，登录入口可访问，不能再沿用早期“数据库超时或 License 导致启动失败”的结论。

## 完成 L1/L2 的前置条件

- Legacy WAR A 使用与 Tomcat 8 验收相同且可达的数据库、配置中心、Redis/SSO 等环境。
- Legacy WAR B 的本机授权已经按应用官方部署位置安装在私有运行时；重建运行目录时必须通过私密
  部署流程重新应用，禁止提交到源码、文档、日志附件或公开制品。
- 不修改业务 WAR，重跑本地记录的相同 SHA-256 制品。
- Listener、Filter 和 load-on-startup Servlet 全部完成后确认 connector 才开放。
- 使用专用测试账号继续覆盖认证成功、关键 JSON API、Session、上传和错误页，并保存脱敏的
  状态码/Header/Body。
- 对同一请求与 Tomcat 8 做差分；差异必须修复或通过 ADR 接受。
