# ADR-0010：有界请求准入

## 状态

Accepted

## 背景

有界 worker 池只能限制应用执行侧的并发，不能单独约束连接数量、在途请求数量或已聚合请求体在内存中的保留字节。
要把 1.x 的过载行为做成可解释、可恢复的门槛，还需要把接入层准入和 worker 调度拆开。

这个决策只覆盖连接准入、请求准入、请求体字节准入和停止时的等待语义；它不解决 streaming/raw ingress 的保护，
也不把 `maxInflightRequestBytes` 伪装成上传流控。

## 决策

- 新增 `--max-connections`，默认 `1024`。超过上限的新连接直接关闭。
- `maxInflightRequests` 不作为独立 CLI 参数暴露，而是由 `--workers + --worker-queue` 推导。
- 新增 `--max-inflight-request-bytes`，默认 `64 MiB`。它限制的是已经聚合且通过准入、正在处理的请求体字节。
- 在途请求数或在途请求字节超限时，对外返回 `503`，然后关闭连接。
- `--request-read-timeout` 默认 `30000` 毫秒，只覆盖读取/解析和 keep-alive 空闲阶段；Servlet/Async 执行不受影响。
- Netty `FlowControlHandler` 只负责让同一 HTTP/1.1 channel 的后续请求等到前一个响应 flush 之后再继续读取；
  它可能只延后已经解码进来的数据，但不承担 raw ingress 保护。
- 停止时先关闭接入，再等待所有已经接纳的在途请求（包括 deferred exchange），直到优雅停止窗口耗尽。

## 验收门禁

- `--max-connections` 超限时，新连接应立即被关闭。
- `maxInflightRequests` 必须保持有界，且等于 `workerThreads + workerQueueCapacity`。
- `--max-inflight-request-bytes` 超限时，应返回 `503` 并关闭连接。
- `--request-read-timeout` 只应在读取阶段触发，不能中断 Servlet/Async 执行。
- 同一 HTTP/1.1 channel 上的请求应在前一个响应 flush 后再继续读取。
- 停机时，所有已接纳但未完成的请求（包括 deferred exchange）应获得优雅等待时间。

## 后果

### 正面

- 连接数、已准入请求数和已准入请求体字节数都有清晰边界。
- 请求数或请求体字节超限统一返回 `503`；连接超限直接关闭，行为可预测。
- 停机语义更明确，避免把已经接纳的请求强行丢弃。

### 负面

- 需要分别维护连接、请求和字节三条准入路径。
- `maxInflightRequestBytes` 不是流式上传保护，上传类场景仍然受限。
- 同一 channel 的串行化会限制 keep-alive/pipelined 复用下的并发读取。

## 备选方案

- 只依赖 worker 队列：拒绝，因为连接数和聚合后的字节占用仍然可能失控。
- 仅对连接做限流：拒绝，因为小连接数下仍可能被大请求体拖垮内存。
- 把字节预算做成 raw ingress 流控：拒绝，因为它会把实现复杂度和协议边界一起抬高，超出当前 alpha 目标。
