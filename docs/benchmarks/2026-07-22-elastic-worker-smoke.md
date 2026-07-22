# 2026-07-22 弹性 Worker 本地工程验证

状态：实现级 smoke，不是 Tomcat 对比或发布级性能声明。

## 目的

验证第一批弹性 Worker 改动的状态转换：忙时扩容、容量外返回 `503`、撤载后恢复，以及空闲
线程回落。该轮故意使用很小的队列制造过载，不能用其吞吐或失败比例宣传产品性能。

## 环境

```text
host: Apple Silicon macOS
JDK: Azul Zulu OpenJDK 1.8.0_322-b06
JVM: -Xms64m -Xmx256m
worker min/max: 2 / 32
worker queue: 4
worker idle timeout: 1s
client: ApacheBench, keep-alive, concurrency 64
sample: 300,000 requests
```

制品指纹：

```text
tinysc jar: 2c5407a691a792c04ff6d35d59ad79339de624c0aaa03673649857dbfa296978
Probe WAR: 136ba9dbf8452e844bbc6f3d6d169736262ef21eceecc449af2ad491401c257b
```

## 结果

| 检查项 | 观察结果 |
|---|---:|
| 启动 ready | 191ms |
| 压测请求 | 300,000 |
| 并发 | 64 |
| 完成时间 | 2.531s |
| 峰值 worker | 32 |
| 受控非 2xx | 19,959，均由故意设置的 `max=32 + queue=4` 触发 |
| 撤载后 HTTP | `200` |
| 空闲回收后 worker | 2 |
| 优雅停止 | shutdown 到 stopped 约 6ms |

日志中的拒绝计数按 `1、2、4、8...` 次幂采样，避免过载期间日志风暴；峰值记录为
`pool=32`，随后线程 dump 只剩 2 个 `tinysc-worker-*`。

## 结论与限制

- grow-before-queue、队列边界、恢复和空闲回收链路均得到本地证据支持。
- 该轮队列容量只有 4，出现 `503` 是预期行为，不代表生产默认配置的错误率。
- 客户端和服务端同机且仅运行 2.5 秒，吞吐、RSS 和延迟不得进入产品对外结论。
- 发布判断仍需按 `docs/performance.md` 在独立压测端和受控 Linux 主机执行长稳 A/B。
