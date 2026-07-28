# ADR-0012：有界 multipart 解析

## 状态

Accepted

## 背景

Servlet 3.1 的 `Part` API 需要处理二进制 boundary、字段与文件、磁盘阈值、大小限制和请求结束清理。
自制 MIME parser 的安全与兼容成本高，字符串切分也不能正确处理任意二进制内容。TinySC 当前又会在
Servlet 调用前完整聚合请求体，因此本轮不能把基础 multipart 支持描述成流式上传。

## 决策

- 只在 Servlet 适配层使用 Apache Commons FileUpload `1.6.0`；共享内核仍不依赖 Servlet namespace
  或 multipart 实现。
- 选择 1.6.0 是因为它保持 Java 8 基线，并包含可配置单 Part Header 上限的安全修复；Commons IO
  统一使用工程现有的 `2.20.0`。
- 支持 `web.xml` 的 `<multipart-config>` 和
  `ServletRegistration.Dynamic#setMultipartConfig`。本轮不单独特判 `@MultipartConfig`；
  它随完整注解声明与 `metadata-complete` 合并规则一并实现。
- `maxRequestSize`、`maxFileSize` 和 `fileSizeThreshold` 必须生效；超过前两项时抛出
  `IllegalStateException`。
- 每个请求最多解析 50 个 Part，每个 Part Header 最多 512 bytes。解析器直接读取
  `ContainerRequest.bodyStream()`，不再通过 `bodyBytes()`复制一份完整请求体。
- 阈值以上的 Part 写入 ServletContext 临时目录；同步请求结束或 Async 真正完成时立即清理。
- `<location>` 相对路径以 ServletContext 临时目录为基准且不得通过 `..` 逃逸；绝对路径保持
  Servlet/Tomcat 的既有语义。
- `Part.write` 的相对路径不得逃逸配置目录；绝对路径保持 Servlet/Tomcat 的既有语义。
- 全局聚合请求体上限仍优先于 Servlet multipart 配置。当前默认上限为 16 MiB，不能通过更大的
  `<max-request-size>` 绕过。

## 验收门禁

- 文本字段、二进制文件、重复字段名、quoted boundary 和 Part Header 解析通过。
- `getParts()` / `getPart()` 可重复调用，并将普通表单字段合入 `getParameter*`。
- 请求、单文件、Part 数量和 Part Header 超限均有确定失败类型。
- `fileSizeThreshold`、`write`、`delete`、路径逃逸拒绝和请求结束清理通过。
- Probe WAR 经真实 HTTP 上传通过；同一 Probe WAR 与 Tomcat 8 的状态码、核心响应和限额结果完成差分。
- Java 8 完整 reactor、class major 与 namespace 门禁继续通过。

## 后果

### 正面

- 复用经过长期验证且具备安全修复的 MIME parser，避免在容器核心重复实现复杂边界扫描。
- multipart 只在明确配置的 Servlet 上按需解析，普通请求不承担解析开销。
- Part 数量、Header、文件、请求和临时文件生命周期均有明确边界。

### 负面

- shaded 制品新增 Commons FileUpload 依赖，已纳入第三方许可证清单；正式发布前仍必须生成 SBOM。
- 请求体在传输层仍会先完整聚合；阈值落盘只能减少解析后的额外常驻内存，不能消除入站聚合内存。
- `@MultipartConfig`、真正流式上传、非阻塞读取和真实 Legacy WAR 上传验收仍未完成。

## 备选方案

- 自行实现 MIME boundary parser：拒绝，因为安全、编码和异常输入成本远高于本轮收益。
- 直接使用 Netty multipart decoder：拒绝，因为会让 Servlet 适配层反向依赖具体 HTTP 传输实现。
- 使用 Commons FileUpload 2.x：拒绝，当前安全修复版本最低需要 Java 11，不符合 1.x 的 Java 8 承诺。
- 只提高全局请求体上限：拒绝，因为它既不提供 `Part` 语义，也会放大内存风险。
