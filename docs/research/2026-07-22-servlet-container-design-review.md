# Jetty、Undertow、Tomcat 与 Netty 设计对照

状态：研究结论。本文不是 ADR；只有经过 TinySC 约束复核和测试门禁的条目才会进入实现。

## 1. 研究边界

TinySC 1.x 的约束是 Java 8、`javax.servlet`、Servlet 3.1、一个进程一个 WAR。比较对象需要分清
“兼容基线”和“设计参考”：

| 项目 | 直接兼容参考 | 设计参考 |
|---|---|---|
| Jetty | 9.4：Java 8、Servlet 3.1，但已 EOL | 12.x 的线程、QoS 和生命周期思想；不能搬 Java 17 API |
| Undertow | 1.4.28 是 Servlet 3.1；2.2 仍是 Java 8/`javax`，但基线已到 Servlet 4.0 | XNIO 分工、Handler、流控和优雅停机 |
| Tomcat | 8.5.100：真实旧 WAR 的直接对照 | TaskQueue、连接限制、NIO sendfile、运维指标 |
| Netty | TinySC 当前传输层 4.2 | 水位线、分块消息和 Channel 生命周期 |

Jetty 的官方版本表明确 9.4 对应 Java 8/Servlet 3.1 且已经结束支持；因此只把它当实现历史样本，
安全和协议行为还要参考当前维护线。[Jetty version history](https://jetty.org/download.html)

Undertow 1.4.28 的构建文件明确依赖 Servlet 3.1 API；2.2.39 则依赖 Servlet 4.0 API。前者是
Servlet 语义的直接样本，后者主要用于研究更成熟的资源控制实现。
[Undertow 1.4.28 POM](https://github.com/undertow-io/undertow/blob/1.4.28.Final/pom.xml)、
[Undertow 2.2.39 POM](https://github.com/undertow-io/undertow/blob/2.2.39.Final/pom.xml)

## 2. 核心对照

### 2.1 I/O 与应用线程

- Jetty 会根据任务是否可能阻塞，在 producer/consumer 执行模式之间选择，并用保留线程保证协议
  内部任务继续前进。[Jetty threading architecture](https://jetty.org/docs/jetty/12.1/programming-guide/arch/threads.html)
- Undertow 明确要求 I/O 线程只做非阻塞工作，Servlet 或其他阻塞逻辑必须 dispatch 到 worker。
  [Undertow architecture and request lifecycle](https://undertow.io/undertow-docs/undertow-docs-2.0.0/)
- Tomcat 8.5 使用 `submittedCount + TaskQueue` 判断已有线程是否繁忙，繁忙且未到最大线程时通过
  `offer(false)` 促使线程池扩容；并用 `force()` 处理并发竞态。
  [Tomcat 8.5 TaskQueue](https://github.com/apache/tomcat/blob/8.5.100/java/org/apache/tomcat/util/threads/TaskQueue.java)

TinySC 结论：保留独立 Netty I/O 线程和 Servlet worker。本轮实现的弹性 Worker 只借鉴 Tomcat
的“繁忙时先扩容”语义，不引入 Jetty 的任务类型系统和保留线程，因为 TinySC 1.x 只处理
HTTP/1.1 阻塞 Servlet，协议内部任务也不共用应用池。

### 2.2 排队与过载

Jetty 当前文档要求共享服务器线程池使用无界队列，原因是该池还执行 selector、协议推进和失败
处理等内部任务；拒绝这些任务可能让服务器无法继续前进。同一文档同时建议用 `QoSHandler` 限制
活跃请求，而不是把线程队列当请求限流器。
[Jetty thread-pool queue and QoS](https://jetty.org/docs/jetty/12/programming-guide/server/http.html)

Undertow 提供请求并发限制 Handler，超过活跃上限后只在有限范围内等待；Tomcat 则把
`maxThreads`、`maxConnections` 和操作系统 `acceptCount` 分开。
[Undertow built-in handlers](https://undertow.io/undertow-docs/undertow-docs-2.0.0/)、
[Tomcat HTTP connector](https://tomcat.apache.org/tomcat-8.5-doc/config/http)

TinySC 结论：不复制 Jetty 的无界队列。TinySC 的应用池不承担 Netty selector/acceptor 任务，
因此继续使用有界 worker 队列；下一步在队列之前增加独立的连接数、在途请求数和在途请求字节
预算。只保留一个等待队列，避免“准入队列 + worker 队列”叠加形成不可见长尾。

### 2.3 请求和响应内存

Undertow 使用共享 NIO buffer pool，并强调 buffer 必须显式归还；它还区分全局实体大小和单请求
实体大小，并提供读侧高低水位控制。
[Undertow listener options](https://undertow.io/undertow-docs/undertow-docs-2.1.0/listeners.html)、
[Undertow 2.2 options](https://github.com/undertow-io/undertow/blob/2.2.39.Final/core/src/main/java/io/undertow/UndertowOptions.java)

Netty 的 `HttpObjectAggregator` 明确把分段 `HttpContent` 合并成一个 `FullHttpRequest`；这适合简化
处理，但不是流式上传方案。Netty 的 `WriteBufferWaterMark` 会在待写字节超过高水位时把 Channel
标记为不可写，低于低水位后再恢复。
[HttpObjectAggregator](https://netty.io/4.2/api/io/netty/handler/codec/http/HttpObjectAggregator.html)、
[WriteBufferWaterMark](https://netty.io/4.2/api/io/netty/channel/WriteBufferWaterMark.html)

Tomcat NIO endpoint 提供 sendfile 状态机，把静态文件发送从 Servlet 堆缓冲路径中分离。
[Tomcat 8.5 NioEndpoint](https://github.com/apache/tomcat/blob/8.5.100/java/org/apache/tomcat/util/net/NioEndpoint.java)

TinySC 结论：分两步实施，避免一次重写 Servlet I/O：

1. 先消除请求体重复 `byte[]` 复制，增加全局在途字节预算，并配置 Netty 写水位。
2. 再实现 Servlet 响应 commit buffer 和分块写；完成所有权、Async 和断连测试后，才移除通用
   `HttpObjectAggregator`。静态文件 sendfile 只在剖析证明值得后单独立项。

### 2.4 超时与连接状态

Undertow 把 `REQUEST_PARSE_TIMEOUT`、`NO_REQUEST_TIMEOUT`、连接 `IDLE_TIMEOUT` 和停机超时分开，
并提示过小的连接 idle 值会伤害长请求。
[Undertow listener timeout options](https://undertow.io/undertow-docs/undertow-docs-2.1.0/listeners.html)

TinySC 结论：当前单个 `ReadTimeoutHandler(30s)` 不能继续同时代表请求头、请求体、业务执行和
Async 超时。下一批拆成：

- header 总解析时限；
- body 进度与总时限；
- keep-alive 无请求时限；
- response write 时限；
- Servlet/Async 自己的业务时限。

### 2.5 优雅停机与恢复

Undertow 的 `GracefulShutdownHandler` 只负责拒绝新请求并等待在途请求完成，不直接关闭 server；
这把“停止接流量”和“释放执行器/连接”分成了两个阶段。
[Undertow GracefulShutdownHandler](https://github.com/undertow-io/undertow/blob/2.2.39.Final/core/src/main/java/io/undertow/server/handlers/GracefulShutdownHandler.java)

Jetty 同样按 connector/handler 生命周期排空，并提供低资源监控；Tomcat endpoint 将连接上限作为
可查询指标暴露。
[Jetty LowResourceMonitor](https://raw.githubusercontent.com/eclipse/jetty.project/jetty-9.4.x/jetty-server/src/main/java/org/eclipse/jetty/server/LowResourceMonitor.java)、
[Tomcat AbstractEndpoint](https://github.com/apache/tomcat/blob/8.5.100/java/org/apache/tomcat/util/net/AbstractEndpoint.java)

TinySC 结论：保留当前“先关闭监听、再等待 worker”的基本顺序，并补成显式的
`RUNNING → QUIESCING → STOPPING → STOPPED`。先关闭 readiness 和新请求准入，保留
worker/EventLoop 完成同步与 Async，达到统一 deadline 后再取消连接并销毁 WebApp。资源压力监控
先做指标和拒绝，不做自动改变线程数的“智能调参”。

### 2.6 启动扫描

Jetty 提供预扫描 quickstart 思路，但这不是跳过 Servlet 语义：存在 `@HandlesTypes` 时仍要得到
正确匹配集合。[Jetty quickstart](https://jetty.org/docs/jetty/12.1/operations-guide/quickstart/index.html)

TinySC 结论：缓存的是 JAR/class header/资源索引，不缓存 Servlet、SCI 或 Spring 实例；每次启动
仍串行执行 SCI、Listener、Filter 和 load-on-startup Servlet。`metadata-complete=true` 不能被当作
无条件跳过 `@HandlesTypes` 的开关。

## 3. 取其精华

| 来源 | 借鉴内容 | TinySC 实施方式 |
|---|---|---|
| Tomcat | `submittedCount + TaskQueue` | 已用于有界弹性 Worker，并保留快速 503 |
| Tomcat | `maxConnections / acceptCount` | 增加独立于 worker 的连接计数与准入，不照搬 `LimitLatch` |
| Tomcat | sendfile | 暂不实现；只在静态资源剖析证明值得后采用 Netty 零拷贝或分块路径 |
| Jetty | QoS 与低资源模式 | 在 worker 前做独立请求/字节预算；暴露低资源状态 |
| Jetty | 线程、队列、leased/idle 指标 | 只实现 TinySC 必需的 active/pool/queue/reject 指标 |
| Undertow | I/O 与阻塞 dispatch 边界 | Netty EventLoop 永不执行 Servlet 业务 |
| Undertow | Handler 组合和完成监听 | 准入、超时、访问日志、优雅停机做成小型有序阶段 |
| Undertow | 拆分超时 | header/body/keep-alive/write/application 各自计时 |
| Netty | write watermark、分段消息 | 慢客户端触发背压；后续实现流式 Servlet 输出 |

## 4. 弃其糟粕或不适合 TinySC 的部分

- 不移植 Jetty `AdaptiveExecutionStrategy`/reserved-thread 整套机制到 1.x；收益必须先证明，复杂度
  会扩大线程竞态和运维面。
- 不使用 Jetty 共享线程池的无界队列；TinySC 已把协议 I/O 与应用执行分开，可以安全做应用准入。
- 不为借鉴 Undertow 而把 Netty 换成 XNIO；重写传输层没有直接产品收益。
- 不照搬“每 CPU 若干线程”“固定 16KiB direct buffer”等经验值；以真实 WAR、堆、direct memory
  和尾延迟测试决定。
- 不引入 Tomcat 的多 Host、多 WAR、热部署、Manager、AJP、JSP 和后台目录扫描。
- 不让静态资源快路径绕过 Filter、权限和条件请求语义；性能优化必须先通过差分测试。
- 不以提前绑定端口、延迟 Listener/Filter/Servlet 初始化来美化启动数字。

## 5. 转化后的实施顺序

1. **P0-2 准入与连接正确性**：连接/请求/字节预算、同连接有序处理、拆分超时、过载恢复。
2. **P0-3 优雅停机与可观测性**：quiesce、统一 deadline、ready/live、worker/queue/reject/connection
   指标和访问日志。
3. **P1 流式 I/O**：去除请求重复复制、response watermark、Servlet commit buffer；静态资源
   sendfile 只在剖析证明是瓶颈后实施。
4. **P1 启动缓存**：SCI/class header/resource index 指纹缓存和有界并行解析。
5. **2.x 再评估高级调度**：只有 HTTP/2、多路复用和 Java 17/21 证据表明需要时，才评估任务类型
   分类或虚拟线程执行器。

每项进入源码前需要单独 ADR；性能与稳定性继续使用 `docs/performance.md` 的同机差分、过载恢复和
24/72 小时门禁。
