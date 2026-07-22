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
| `--io-threads` | 否 | `min(2, CPU)`，至少 1 | Netty I/O 线程数 |
| `--workers` | 否 | `max(4, CPU × 2)` | 工作线程上限，不是固定线程数 |
| `--min-workers` | 否 | `min(8, --workers)` | 核心保留线；已创建的 worker 回落到该值后不再因空闲被回收 |
| `--worker-idle-timeout` | 否 | `60` | worker 空闲回收超时时间，单位秒 |
| `--worker-queue` | 否 | `100` | 有界工作队列容量；现有 worker 都繁忙且未触顶时优先扩容，饱和时新请求返回 503 |
| `--max-connections` | 否 | `1024` | 同时保持的 TCP 连接上限；超限的新连接直接关闭 |
| `--max-inflight-request-bytes` | 否 | `64 MiB` | 已聚合且通过准入、正在处理的请求体字节上限；不是流式 ingress 保护 |
| `--request-read-timeout` | 否 | `30000` | 读取/解析和 keep-alive 空闲超时，单位毫秒；只在读取阶段生效，Servlet/Async 执行不受影响 |

示例：

```bash
java -jar tinysc-1.0.0-alpha-SNAPSHOT.jar start \
  --war /srv/apps/example.war \
  --bind 127.0.0.1 \
  --port 8080 \
  --context-path /example \
  --base /var/lib/tinysc/example \
  --io-threads 2 \
  --workers 32 \
  --min-workers 8 \
  --worker-idle-timeout 60 \
  --worker-queue 100 \
  --max-connections 1024 \
  --max-inflight-request-bytes 67108864 \
  --request-read-timeout 30000
```

worker 调度规则如下：

- `--workers` 是上限；`--min-workers` 是已创建线程的核心保留线，不是启动时必须立即创建的数量。
- 现有 worker 都繁忙且尚未触顶时，池会先扩容；有空闲 worker 时，请求可短暂入队并立即被消费。
- Netty I/O 线程不做 `CallerRuns` 兜底，避免接入线程被业务阻塞。
- 线程和队列都饱和时，对外返回 `503`。
- `maxInflightRequests` 是派生值，等于 `--workers + --worker-queue`，不是独立 CLI 参数。
- `--max-inflight-request-bytes` 限制的是已经聚合且通过准入、正在处理的请求体字节，不是 raw ingress 流控。
- `--request-read-timeout` 只覆盖读取/解析和 keep-alive 空闲阶段，不会中断 Servlet/Async 执行。

## alpha 固定限制

这些限制已在内核中生效，仍保持固定或暂未暴露为独立 CLI 参数：

| 限制 | 当前值 |
|---|---:|
| HTTP 请求行 | 8 KiB |
| HTTP Header | 16 KiB |
| 聚合请求体 | 16 MiB |
| Listen backlog | 256 |
| 优雅停止等待 | 30 秒 |

请求体当前在进入 Servlet 前完整聚合，因此 16 MiB 不是上传能力承诺。`maxInflightRequestBytes`
只约束已经聚合且通过准入、正在处理的请求数据，不是 streaming/raw ingress 保护；`FlowControlHandler`
只会让同一 HTTP/1.1 channel 在前一个响应 flush 后再继续读取。`--request-read-timeout` 只覆盖
读取/解析和 keep-alive 空闲阶段，不会中断 Servlet/Async 执行。multipart、流式上传和 Servlet 非阻塞
I/O 尚未完成；大文件场景不能以调大上限代替流式实现。

## 生产建议

- 一个进程只运行一个 WAR，并为每个实例使用独立 `--base`。
- 确保 `--base/logs` 可写并纳入磁盘容量监控；进程日志默认每份 64 MiB，保留 5 份轮转备份。
- 默认只绑定回环地址；由反向代理负责公网 TLS、访问日志和流量治理。
- `--workers` 是上限，`--min-workers` 和 `--worker-idle-timeout` 控制回落与恢复；线程数应通过
 真实业务压测调整。增加 worker 只会提高可并行阻塞调用数，不保证降低延迟。
- alpha 没有配置文件、环境变量映射和在线热更新；参数变更需要重启进程。
- 密码、Token 和数据库凭据属于应用或外部密钥系统，不应作为 tinysc 参数保存。
