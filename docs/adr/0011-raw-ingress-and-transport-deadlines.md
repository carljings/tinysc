# ADR-0011：原始入站预算与传输时限

## 状态

Accepted

## 背景

1.0 alpha 已经把连接数、在途请求数和已聚合请求体字节分开，但这仍不足以表达原始入站 payload 的实际占用、
请求体总时限和响应写侧的传输护栏。把这些语义继续混在一个 30 秒读超时或一个聚合字节预算里，会让文档和实现都变得含糊。

这个决策只覆盖原始入站预算和传输时限，不把响应写侧 guardrail 伪装成真正的非阻塞写，也不把 raw ingress
budget 解释成整个容器的内存上限。

## 决策

- 新增 `--max-raw-ingress-bytes`，默认 `64 MiB`。它统计的是请求体 raw ingress 预算，不是 JVM 总堆、
  Netty allocator 元数据或响应内存的硬上限。
- 对带 `Content-Length` 的请求，只做单请求上限的早期 `413` 检查，不会一次性预占全量 raw ingress
  容量；对 `Transfer-Encoding: chunked` 的请求，按实际到达的分片累计。
- raw ingress 额度随实际到达的 `HttpContent` 增量占用，完成、断开、超时或失败时都精确释放。
- 新增 `--request-body-timeout`，默认 `300000` 毫秒。它是请求体总时限，不是空闲计时；没有更早响应
  在途时，超时返回 `408` 并关闭连接。
- `--request-read-timeout` 继续默认 `30000` 毫秒，只覆盖读取/解析和 keep-alive 空闲阶段。
- 新增 `--response-write-timeout`，默认 `30000` 毫秒。它只在写不完成时关闭连接，是 transport guardrail，
  不是响应堆内存上限，也不是完整背压。
- 当前响应仍然全量堆缓冲；`ServletOutputStream.isReady()` 和 `WriteListener` 还不是真实的非阻塞写实现。
- 同一 HTTP/1.1 channel 上后续 pipelined 请求的 raw、解码、聚合或 Expect 失败按顺序关闭，不会抢占
  当前正在执行的 exchange；当前响应先完成，再释放租约并清理。为避免响应乱序，后续失败不另行插入
  `408`、`413`、`417` 或 `503`。

## 验收门禁

- `--max-raw-ingress-bytes` 超限时应拒绝继续接纳 raw payload。
- `Content-Length` 应只在单请求上限上触发早期 `413`，不应一次性预占全量；chunked 应按实际分片累计。
- 断开、`408`、`413` 和其他失败都应精确释放已占用的 raw ingress 额度。
- 没有更早响应在途时，`--request-body-timeout` 超时应返回 `408` 并关闭连接。
- `--request-read-timeout` 只应在读取阶段触发，不能中断 Servlet/Async 执行。
- `--response-write-timeout` 只应在写不完成时关闭连接。
- 当前 response 仍应保持全量堆缓冲，直到后续响应流式化 ADR 落地。
- 同一 HTTP/1.1 channel 上后续 pipelined 请求的 raw、解码、聚合或 Expect 失败应按顺序关闭，不得抢占
  当前 exchange 或提前发出 `100 Continue`。

## 后果

### 正面

- 原始入站预算、请求体总时限和响应写侧护栏各自有明确边界。
- `408`、`503` 和 close 的语义可以区分读取、容量和传输故障。
- 文档可以明确说明哪些限制是容量预留，哪些只是 transport guardrail。

### 负面

- 需要同时维护 raw ingress、body deadline 和 write timeout 三条路径。
- `maxRawIngressBytes` 只约束 raw ingress 预算，不解决整个容器内存压力。
- 当前只有全局 raw ingress 预算和单请求体上限，没有独立的每连接公平份额；单连接可以占用全局预算，
  但不能突破全局硬边界。
- 真实的非阻塞响应写仍然留到后续实现，当前 `WriteListener` 只是兼容形状。

## 备选方案

- 继续用单个 30 秒读超时代表所有阶段：拒绝，因为它混淆读取、业务执行和写侧故障。
- 只保留聚合后的请求体字节预算：拒绝，因为它看不到聚合前实际到达的 raw payload，也无法覆盖
  chunked 传输过程中的压力。
- 把响应写超时当作背压机制：拒绝，因为当前实现还不是流式响应写，不能用 guardrail 冒充完整背压。
