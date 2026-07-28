# ADR-0015：同步 error-page 分发

## 状态

Accepted

## 背景

TinySC 1.x 已实现标准 Servlet 链路、`web.xml`、Filter、Listener、Async 和基础 multipart。
旧 WAR 还依赖同步 `<error-page>`：`sendError(...)`、未捕获异常、`DispatcherType.ERROR`、标准 error
attributes，以及一个不递归的失败兜底。

该能力只应覆盖同步、未把响应 bytes 写到网络的请求。`setStatus(...)` 不能单独触发 error dispatch；
已提交响应恢复、Async error dispatch、JSP error page 和完整 Servlet 3.1 / TCK 语义都不在本轮边界内。

## 决策

- 只支持同步、pre-wire-commit 的 `web.xml` error-page。
- status 匹配先走 exact code，再走默认 error-page。
- exception 匹配按最近声明的 superclass；对 `ServletException` 先看 outer exception，再看 root cause。
- 没有命中 exception mapping 时，再尝试 status `500`，最后尝试 default error-page。
- 进入自定义 error-page 时，容器设置标准 `RequestDispatcher.ERROR_*` 属性，按 `DispatcherType.ERROR` 重新匹配 Filter，并保留普通 header 和 cookie。
- 进入自定义 error-page 时，清掉旧 body、旧 `Content-Length`，并恢复原始 `Content-Type`。
- `sendError(...)` 之后的普通应用写入不再计入原 body。
- 错误页自身失败时，不允许递归 error selection；容器只输出一次安全的 plain-text `500` fallback。

## 验收门禁

- `sendError(404, ...)` 命中 status error-page，`ERROR` filter 运行。
- `setStatus(404)` 不触发 error-page。
- `RuntimeException`、`ServletException(IOException root cause)` 和 `status 500` 命中各自的 error-page。
- `RequestDispatcher.ERROR_STATUS_CODE`、`ERROR_MESSAGE`、`ERROR_REQUEST_URI`、`ERROR_SERVLET_NAME`、
  `ERROR_EXCEPTION` 和 `ERROR_EXCEPTION_TYPE` 都能在错误页中读取到。
- 自定义 error-page 失败时只返回一次安全 `500`，不会递归。
- 同一 Probe WAR 在 TinySC 与 Tomcat 8.5.100 上完成差分；四个稳定场景与 Tomcat 对齐，失败
  error-page 保留 TinySC 的安全 fallback 差异。
- Java 8 全量构建继续通过，且当前总计 206 项测试通过。

## 后果

### 正面

- 旧 WAR 的同步 error-page 现在有可验证的行为边界。
- `ERROR` filter 和标准 error attributes 可用于匿名化、日志和用户提示。
- 失败 error-page 不会把部分正文继续泄漏给客户端。

### 负面

- TinySC 在失败 error-page 上选择了安全 fallback，而不是完全复刻 Tomcat 的 partial-body 保留行为。
- Async error dispatch、已提交响应恢复、JSP error page 和完整 TCK 仍要后续补齐。

## 备选方案

- 直接把失败 error-page 的 partial body 原样保留：拒绝，因为这会放大递归和正文泄漏风险。
- 把 error-page 做成通用重写引擎：拒绝，因为当前只需要同步、未提交前的最小实现。
