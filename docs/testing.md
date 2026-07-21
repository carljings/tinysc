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
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) \
  "$JAVA_HOME/bin/java" -version
```

发布检查还需要扫描全部发行 class，确认 major version 不高于 52。
完整 reactor 的 `ReleaseArtifactTest` 会直接扫描 shaded launcher，并同时拒绝多版本 class 和
`jakarta.servlet` 类进入 1.x 制品。

性能测试的固定方法和声明门槛见 [performance.md](performance.md)。

## 当前自动化覆盖

当前 Probe WAR 端到端测试在随机端口验证：Filter → Servlet、参数与映射、Listener、Session
续用、SCI 动态注册、异步完成、RequestDispatcher forward、静态 GET/HEAD、Resource JAR 的
直接/forward/HEAD/欢迎页和 ServletContext 资源 API、Web 根优先级、`WEB-INF` 保护以及 context
外 404。HTTP 模块另测工作线程交接及 Content-Length/Transfer-Encoding 歧义拒绝；
部署模块测试安全展开、XML 解析和应用线程清理。启动器另验证终端/文件双写、追加、
大小轮转、标准流恢复，以及真实 shaded JAR 启动和进程优雅终止日志。

尚未覆盖的关键项包括 multipart、异步 dispatch、Servlet 非阻塞 ReadListener/WriteListener、
error-page、安全约束、web-fragment、注解声明、URL 重写 Session、上传限额和 TCK。它们完成前
不得宣称完整 Servlet 3.1 兼容。
