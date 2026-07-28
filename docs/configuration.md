# 配置参考

tinysc 1.x alpha 只接受命令行配置。未知参数、重复参数、缺少参数值或非法整数会以退出码 2
拒绝，避免拼写错误被静默忽略。

## `start` 参数

| 参数 | 必需 | 默认值 | 说明 |
|---|---|---|---|
| `--war` | 是 | — | WAR 文件或 exploded WebApp 目录 |
| `--bind` | 否 | `127.0.0.1` | 监听地址；显式设为 `0.0.0.0` 才对外网卡开放 |
| `--port` | 否 | `8080` | HTTP 端口；测试代码可用 `0` 请求随机端口 |
| `--context-path` | 否 | 根 Context | 空值或 `/` 表示根；非根值必须以 `/` 开头且不能以 `/` 结尾 |
| `--base` | 否 | `.` | 当前工作目录；展开缓存和 `logs/tinysc.log` 的实例根目录 |
| `--io-threads` | 否 | `min(4, CPU)`，至少 1 | Netty I/O 线程数；多核环境默认 4 以降低 HTTP 解码、响应写回与 access-log producer 的竞争 |
| `--workers` | 否 | `max(4, CPU × 2)` | 工作线程上限，不是固定线程数 |
| `--min-workers` | 否 | `min(2, --workers)` | 核心保留线；较低默认值会减少首波创建，把更多容量交给突发时的弹性扩容 |
| `--worker-idle-timeout` | 否 | `60` | worker 空闲回收超时时间，单位秒 |
| `--worker-queue` | 否 | `100` | 有界工作队列容量；现有 worker 都繁忙且未触顶时优先扩容，饱和时新请求返回 503 |
| `--max-connections` | 否 | `1024` | 同时保持的 TCP 连接上限；超限的新连接直接关闭 |
| `--max-inflight-request-bytes` | 否 | `64 MiB` | 已聚合且仍被保留的请求体字节上限；不是 raw ingress 预留，也不是流式 ingress 保护 |
| `--max-raw-ingress-bytes` | 否 | `64 MiB` | 请求体 raw ingress 预算；`Content-Length` 只做单请求上限早期 `413`，不会一次性预占全量，chunked 按实际分片累计，断开/超时/失败会精确释放 |
| `--request-read-timeout` | 否 | `30000` | 读取/解析和 keep-alive 空闲超时，单位毫秒；只在读取阶段生效，Servlet/Async 执行不受影响 |
| `--request-body-timeout` | 否 | `300000` | 请求体总时限，单位毫秒；没有更早响应在途时，超时返回 `408` 并关闭连接 |
| `--response-write-timeout` | 否 | `30000` | 响应写超时，单位毫秒；只在写不完成时关闭连接，是 transport guardrail |
| `--access-log` | 否 | `true` | 独立 HTTP access log；CLI 默认开启，嵌入式 `ServerConfig` 默认关闭；写入 `<base>/logs/access.log`，可用 `--access-log false` 关闭 |

示例：

```bash
java -jar tinysc-1.0.0-alpha-SNAPSHOT.jar start \
  --war /srv/apps/example.war \
  --bind 127.0.0.1 \
  --port 8080 \
  --context-path /example \
  --base /var/lib/tinysc/example \
  --io-threads 4 \
  --workers 32 \
  --min-workers 2 \
  --worker-idle-timeout 60 \
  --worker-queue 100 \
  --max-connections 1024 \
  --max-inflight-request-bytes 67108864 \
  --max-raw-ingress-bytes 67108864 \
  --request-read-timeout 30000 \
  --request-body-timeout 300000 \
  --response-write-timeout 30000 \
  --access-log true
```

worker 调度规则如下：

- `--workers` 是上限；`--min-workers` 是已创建线程的核心保留线，不是启动时必须立即创建的数量。
- 现有 worker 都繁忙且尚未触顶时，池会先扩容；有空闲 worker 时，请求可短暂入队并立即被消费。
- Netty I/O 线程不做 `CallerRuns` 兜底，避免接入线程被业务阻塞。
- 线程和队列都饱和时，对外返回 `503`。
- `maxInflightRequests` 是派生值，等于 `--workers + --worker-queue`，不是独立 CLI 参数。
- `--max-inflight-request-bytes` 限制的是已经聚合且仍被保留的请求体字节；`--max-raw-ingress-bytes`
  是 raw ingress 预算；`Content-Length` 只做单请求上限早期 `413`，不会一次性预占全量，chunked 按实际分片累计，断开/超时/失败会精确释放。
- `--request-read-timeout` 只覆盖读取/解析和 keep-alive 空闲阶段，不会中断 Servlet/Async 执行。
- `--request-body-timeout` 是请求体总时限；没有更早响应在途时，超时会返回 `408` 并关闭连接。
- `--response-write-timeout` 只在写不完成时关闭连接，不是响应堆内存上限或完整背压。
- `--access-log` 默认开启；显式传 `false` 可关闭。
- 同一 HTTP/1.1 channel 上的后续 pipelined 请求会等前一个响应 flush 完成后再继续读取，失败不会抢占当前 exchange。

## alpha 默认值与硬约束

以下值中，HTTP 请求行、Header、聚合请求体、Listen backlog 和优雅停止等待仍是硬约束；
`maxRawIngressBytes` 与三类时限已通过 CLI 暴露，默认值列在上表。

| 限制 | 当前值 |
|---|---:|
| HTTP 请求行 | 8 KiB |
| HTTP Header | 16 KiB |
| 聚合请求体 | 16 MiB |
| multipart Part 数 | 50 / 请求 |
| multipart 单 Part Header | 512 bytes |
| Listen backlog | 256 |
| 优雅停止等待 | 30 秒 |

请求体当前在进入 Servlet 前仍会完整聚合，因此 16 MiB 不是上传能力承诺。`maxRawIngressBytes`
现在按实际到达的 raw bytes 增量占用；`Content-Length` 只做单请求上限早期 `413`，不会一次性预占全量，
chunked 按实际分片累计，断开、超时或失败都会精确释放已占用额度。`maxInflightRequestBytes`
只约束已经聚合且仍被保留的请求体字节，不是 raw ingress 预留。`--request-read-timeout`
只覆盖读取/解析和 keep-alive 空闲阶段，不会中断 Servlet/Async 执行；`--request-body-timeout` 只管请求体总时限，
没有更早响应在途时超时返回 `408` 并关闭连接；`--response-write-timeout` 只在写不完成时关闭连接，是 transport guardrail，
不是响应堆内存上限或完整背压。当前 response 仍全量堆缓冲，Servlet `WriteListener` / `isReady`
还不是 true non-blocking write。通过 `<multipart-config>`、SCI 动态配置或 `@MultipartConfig`
配置的 Servlet 可以使用 `getPart(s)`；注解只作为已注册 Servlet 的缺省配置，不负责发现或映射
Servlet。XML/SCI 显式配置存在时会整体覆盖注解，不逐字段合并。`max-request-size`、
`max-file-size` 和 `file-size-threshold` 会生效；空 `<multipart-config/>` 也属于显式配置，
使用 Servlet 默认值而不是回退到注解。但全局
16 MiB 聚合上限仍优先，且当前最多 50 个 Part、每个 Part Header 最多 512 bytes。该能力仍不是流式
上传。`<location>` 相对路径以 ServletContext 临时目录为基准且不得逃逸，绝对路径由部署者负责；
为兼容 Tomcat 8.5.100，`metadata-complete=true` 不关闭运行时 `@MultipartConfig` 回退；这一点不作为
严格 Servlet 3.1 metadata-complete 合规声明。`@WebServlet` 等全 WAR 注解扫描和 Servlet 非阻塞 I/O
尚未完成；大文件场景不能以调大上限代替流式实现。
同一 HTTP/1.1 channel 上后续 pipelined 请求会按顺序等待当前响应 flush 完成，raw、解码、聚合或 Expect 失败不会抢占正在执行的
exchange，也不会提前发出 `100 Continue`；为保持响应顺序，后续失败不另行插入错误响应。当前只有全局 raw ingress 预算和单请求体上限，
没有独立的每连接公平份额；单连接可以占用全局预算，但不能突破全局硬边界。

同步 error-page 不是 CLI 配置项，而是 `web.xml` 行为：只有在同步请求尚未把响应 bytes 写到网络前，
`sendError(...)` 或未捕获异常才会进入 error dispatch；`setStatus(...)` 不单独触发。进入自定义 error-page
时，容器保留普通 header 和 cookie，清掉旧 body 与 `Content-Length`，并只执行 `DispatcherType.ERROR`
的 Filter 链。已提交响应、Async error dispatch、JSP error page 仍不在配置层面提供。

## 生产建议

- 一个进程只运行一个 WAR，并为每个实例使用独立 `--base`。
- 确保 `--base/logs` 可写并纳入磁盘容量监控；进程日志默认每份 64 MiB，保留 5 份轮转备份，
  访问日志默认写到 `<base>/logs/access.log`，可用 `--access-log false` 关闭。
- 默认只绑定回环地址；由反向代理负责公网 TLS、访问日志和流量治理。
- `--workers` 是上限，`--min-workers` 和 `--worker-idle-timeout` 控制回落与恢复；线程数应通过
 真实业务压测调整。增加 worker 只会提高可并行阻塞调用数，不保证降低延迟。
- alpha 没有配置文件、环境变量映射和在线热更新；参数变更需要重启进程。
- 密码、Token 和数据库凭据属于应用或外部密钥系统，不应作为 tinysc 参数保存。
