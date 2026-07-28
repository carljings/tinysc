# 测试与验收

## 测试层次

| 层次 | 目标 |
|---|---|
| 单元测试 | 映射、URI、descriptor、Session、生命周期状态机 |
| 模块集成 | Netty、部署与 Servlet 适配器的边界 |
| Probe WAR | 真实 Filter → Servlet、Session、Listener、错误响应 |
| 真实 WAR | 两个内部 Legacy WAR 原始制品启动与访问 |
| 差分测试 | 同一请求对比 tinysc 与 Tomcat 8 的状态码/Header/Body/Cookie/事件顺序 |
| 稳定性 | 并发、超时、断连、泄漏、长稳和资源耗尽 |
| 性能差分 | 与 Tomcat 在同机同参下比较启动、RSS、线程、吞吐和尾延迟 |

## 验收等级

| 等级 | 定义 |
|---|---|
| L0 Deploy | WAR 校验、namespace 检查、安全展开成功，源 SHA-256 不变 |
| L1 Boot | SCI、Listener、Filter、load-on-startup Servlet 完成初始化 |
| L2 App | 静态页、REST、Session、上传、错误页等关键链路可访问 |
| L3 Differential | 关键行为与 Tomcat 8 对照一致或差异已接受 |
| P1 Production | 安全、故障、资源、长稳、性能和运维门禁全部通过 |

## 真实项目规则

- 业务仓库只读；不得为了让 tinysc 通过而修改应用。
- 优先使用已存在的原始 WAR，记录路径、大小和 SHA-256。
- 容器失败与数据库、Redis、SSO、配置中心等外部依赖失败必须分别举证。
- “已解压”或“端口已监听”不能表述为“应用启动成功”。
- 每次测试记录启动命令、JDK、tinysc 提交、请求、响应和日志证据。

## Java 8 门禁

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
"$JAVA_HOME/bin/java" -version
JAVA_HOME="$JAVA_HOME" mvn -B -ntp clean verify
```

发布检查还需要扫描全部发行 class，确认 major version 不高于 52。
完整 reactor 的 `ReleaseArtifactTest` 会直接扫描 shaded launcher，并同时拒绝多版本 class 和
`jakarta.servlet` 类进入 1.x 制品。

性能测试的固定方法和声明门槛见 [performance.md](performance.md)。

## 当前自动化覆盖

当前 Probe WAR 端到端测试在随机端口验证：Filter → Servlet、参数与映射、Listener、Session
续用、SCI 动态注册、异步完成、RequestDispatcher forward、静态 GET/HEAD、Tomcat 8.5 兼容的
静态 POST 读取、Resource JAR 的直接/forward/HEAD/POST/欢迎页和 ServletContext 资源 API、
Web 根优先级、`WEB-INF` 保护以及 context
外 404。HTTP 模块另测工作线程交接、Content-Length/Transfer-Encoding 歧义拒绝、连接上限关闭、
`maxRawIngressBytes` 对实际 `HttpContent` 字节的增量占用与断开/超时/失败后的精确释放、`408`
请求体总时限、响应写超时守护、在途请求/字节准入、同一 HTTP/1.1 channel 的串行化、decoder failure
的 lease 配对，以及 deferred exchange 的停机等待；同一连接上后续 pipelined 请求的 raw timeout、
超长 Header 和 `Expect: 100-continue` 均不会抢占当前响应。部署模块测试安全展开、XML 解析和应用
线程清理。HTTP 模块还验证 access log 的独立路径、敏感 query 剔除、控制字符清理、UTF-8 字节轮转、
关闭时 drain / flush 和写失败结果。启动器验证主日志的终端/文件双写、追加、大小轮转和标准流恢复，
并验证 `--access-log` 默认启用与 `false` 关闭，以及 `--max-connections`、
`--max-inflight-request-bytes`、`--max-raw-ingress-bytes`、`--request-read-timeout`、
`--request-body-timeout`、`--response-write-timeout` 参数解析；集成测试另验证真实 shaded JAR 启动和
进程优雅终止日志。

multipart 基线测试覆盖 `web.xml` / SCI 显式配置、空 XML 配置、`@MultipartConfig` 缺省回退及其
优先级、文本字段与二进制文件、quoted boundary、重复字段、Part Header、`getPart(s)`、表单参数
合并、请求/文件/Part 数/Header 限额、磁盘阈值、`write/delete`、路径逃逸拒绝和同步/Async 请求结束清理；Probe WAR
另走真实 Netty HTTP 上传，并已用同一 WAR 对 Tomcat 8.5.100 完成 XML 覆盖、注解成功和两类超限
状态码差分，证据见
[multipart Probe WAR 差分验收](acceptance/2026-07-28-multipart-probe-differential.md)。

尚未覆盖的关键项包括 `@WebServlet` 等全 WAR 注解发现、流式上传、异步 dispatch、Servlet 非阻塞
ReadListener/WriteListener、error-page、安全约束、web-fragment、URL 重写 Session 和 TCK。
当前响应仍全量堆缓冲，
`WriteListener` / `isReady` 仍是兼容实现，不是真正的非阻塞写；它们完成前不得宣称完整 Servlet 3.1 兼容。

现有 smoke 和 benchmark 结果只用于状态验证或预备对照；未达门槛前，不得把它们写成正式性能声明。
