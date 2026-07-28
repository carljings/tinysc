# 2026-07-28 synchronous error-page Probe WAR 差分验收

## 结论

通过，且保留一个刻意的安全差异。TinySC 与 Tomcat 8.5.100 使用同一份 Probe WAR、同一 JDK、同一请求序列时：

- `sendError(404, ...)`、`RuntimeException`、`ServletException(IOException root cause)` 的最终状态码、`ERROR` filter、响应正文和 `Content-Length` 一致；
- `setStatus(404)` 只返回普通 `404`，不会进入自定义 error-page；
- 失败的自定义 error-page 会落回单次安全 `500`，不再递归 error selection。

最后一项与 Tomcat 的结果不同：TinySC 会丢弃 partial body，并返回 generic 的 `Internal Server Error`；Tomcat 保留了 `partial-error-page`。这是 deliberate nonrecursive fallback，不是完整 parity 或性能结论。

## 环境与制品

| 项目 | 值 |
|---|---|
| 日期 | 2026-07-28，Asia/Shanghai |
| Java | OpenJDK `1.8.0_322` |
| 对照容器 | Apache Tomcat `8.5.100` |
| Probe WAR SHA-256 | `60f80478aad7530696fbe6c2a53b1f65276e01a6f9da213ea14402e912307c8f` |
| TinySC shaded JAR SHA-256 | `e2dafafa3bf768348c75419620f9c32dcd0311416ee4421ac49256c683af4eb9` |
| 原始证据 | `work/benchmark/results/error-page-diff-20260728-final-v2/`（Git 忽略） |

TinySC 和 Tomcat 都使用隔离的 base 目录和本地回环端口。两者都等待同一 Probe WAR 的就绪路径后再发起请求，测试结束后停止进程并释放端口。

## 请求与结果

两个容器接收同一份 Probe WAR 的同步 error-page 请求。`ERROR` filter 都会写入稳定标记。

| 用例 | TinySC | Tomcat 8.5.100 | 判定 |
|---|---:|---:|---|
| `sendError(404, "probe-missing")` | `404` / `applied` / `ERROR_VIEW=status-404` | `404` / `applied` / `ERROR_VIEW=status-404` | 通过 |
| `setStatus(404)` | `404` / 无 `ERROR` filter / `plain-status-404` | `404` / 无 `ERROR` filter / `plain-status-404` | 通过 |
| `RuntimeException("probe-runtime")` | `500` / `applied` / `ERROR_VIEW=runtime-exception` | `500` / `applied` / `ERROR_VIEW=runtime-exception` | 通过 |
| `ServletException("probe-servlet", IOException("probe-io"))` | `500` / `applied` / `ERROR_VIEW=io-root-cause` | `500` / `applied` / `ERROR_VIEW=io-root-cause` | 通过 |
| 失败的自定义 error-page | `500` / `applied` / `Internal Server Error` / `21 bytes` | `500` / `applied` / `partial-error-page` / `18 bytes` | 刻意差异 |

前三个 error-page 场景验证的是稳定的同步路径：`sendError` 触发 exact status match，`RuntimeException` 和 `ServletException(IOException root cause)` 触发 exception match，`setStatus(404)` 仍然只是普通状态码。

## 兼容选择

- TinySC 只覆盖同步、未提交前的 `web.xml` error-page 路径。
- `sendError` 会触发 `DispatcherType.ERROR`，并按标准 error attributes 驱动错误页。
- status 匹配先走 exact code，再走默认 error-page。
- exception 匹配按最近声明的 superclass；`ServletException` 先看 outer exception，再看 root cause。
- 进入自定义 error-page 时会清掉旧 body 和 `Content-Length`，并保留普通 header 和 cookie。
- `setStatus(...)` 本身不会触发 error dispatch。
- 错误页失败时只回退一次安全 `500`，不做递归重试。

## 边界

- 本次没有测量吞吐、延迟、RSS、线程或 FD，不能形成新的性能声明。
- Async error dispatch、已提交响应恢复、JSP error pages、嵌套 dispatch 完整 parity 和 TCK 仍未覆盖。
- 真实业务 WAR 的完整错误页链路仍需另行验证，不能由 Probe 结果替代。
