# 2026-07-21 Probe WAR 与 Tomcat 8.5.100 预备基准

状态：预备数据，不是发布级性能声明。

## 结论

| 指标（5 次中位数） | tinysc | Tomcat 8.5.100 | 结果 |
|---|---:|---:|---|
| 首次成功访问时间 | 303ms | 517ms | tinysc 快 41.4%，达到当前启动目标 |
| 预热后 RSS | 108,368KB | 124,048KB | tinysc 低 12.6%，未达到低 30% 目标 |
| 线程数 | 59 | 64 | tinysc 少 7.8% |
| 文件描述符 | 50 | 121 | tinysc 少 58.7% |
| requests/s | 52,215.64 | 50,863.02 | tinysc 高 2.7%，但短跑波动较大 |
| p99 | 4ms | 3ms | tinysc 慢 33.3%，未达到不劣化 5% 目标 |
| 失败请求 | 0 | 0 | 两者均无失败 |

因此，当前只有“开发/测试启动更快”和“常驻对象更少”的方向得到初步支持。RSS 门槛和尾延迟
门槛没有通过，不得对外写成已实现优势。

## 环境与公平条件

```text
host: Mac mini, Apple M4, 10 logical CPUs, 16 GiB
OS: macOS 26.5.2 (arm64)
JDK: Azul Zulu OpenJDK 1.8.0_322-b06
JVM: -Xms64m -Xmx256m
workers: 32 / container
tinysc I/O threads: 2
client concurrency: 32
warm-up: 2,000 requests
sample: 20,000 requests
repetitions: 5, container order alternated
client: macOS ApacheBench, keep-alive enabled
```

两个容器使用同一个 Probe WAR 和同一个 `/probe/benchmark` URL。该 URL 返回固定响应体，不创建
Session，但仍经过同一个 Filter。脚本在采样前核对响应体 SHA-256 和 Filter Header，避免把不同
代码路径放在一起比较。

制品指纹：

```text
tinysc jar: ec95f6c290e7cf95519acf1d7c48500a8c89b4df9eaf9f7bb0e6119924386c83
Tomcat catalina.jar: c8b1f25c209461049a5e1a4e74e78ad538a7d3cb12b09f980acdb7066002be39
Probe WAR: 3cecceb22ca886fc4d40a33aa46a127838a876d2dbe8c6f09d19e62359d0a582
results.csv: b401f6ae0147fb818a7d4a9ecb32fd889403c802cd33a9eec9507c9a117dc22d
environment.txt: b8abb281ee8e1f5303662e1d3037eea4a6aa2d6d7f256a3cb4bce586e610bac2
```

## 原始汇总行

```csv
container,run,application_ready_ms,rss_kb,threads,fds,requests_per_second,failed_requests,p50_ms,p95_ms,p99_ms
tinysc,1,464,109792,59,50,31821.54,0,1,3,6
tomcat,1,662,122400,64,121,37212.35,0,1,2,4
tomcat,2,493,132640,64,121,49811.46,0,0,1,3
tinysc,2,303,108368,59,50,52215.64,0,1,1,4
tinysc,3,301,64736,59,50,39431.24,0,1,2,5
tomcat,3,538,129216,64,121,60019.87,0,0,1,2
tomcat,4,517,124048,64,121,50863.02,0,0,1,3
tinysc,4,279,107824,59,50,58131.42,0,0,1,2
tinysc,5,324,109504,59,50,58929.60,0,0,1,2
tomcat,5,504,123616,65,121,64779.85,0,0,1,2
```

## 复现

先在真实 JDK 8 上构建项目，并准备已校验的 Tomcat 8.5.100：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn clean package

JAVA_HOME=$(/usr/libexec/java_home -v 1.8) \
TOMCAT_HOME=/absolute/path/to/apache-tomcat-8.5.100 \
REPETITIONS=5 REQUESTS=20000 WARMUP_REQUESTS=2000 \
CONCURRENCY=32 WORKERS=32 IO_THREADS=2 \
bash src/tools/benchmark/compare-tomcat.sh
```

脚本源文件位于 `src/`，结果默认写入被 Git 忽略的 `work/benchmark/results/`，不会污染源码。

## 已知限制

- 每轮只有 20,000 请求，在本机只持续约 0.3 至 0.6 秒；吞吐和尾延迟数据不足以作正式声明。
- 压测客户端和服务端在同一台机器，会发生 CPU 竞争。
- ApacheBench 的毫秒分位数精度不足以解释 1ms 级差异。
- 5 次中有一轮 tinysc RSS 为 64,736KB，离散度较大；报告使用中位数但仍需长稳复测。
- Tomcat 8.5.100 在该新版 macOS + 旧 JDK 8 组合下偶发 acceptor shutdown 警告；请求阶段
  均成功，但发布级报告应换到受控 Linux 主机复测。
- Tomcat 8.5.x 已结束官方支持；这里使用最终版 8.5.100，是因为真实验收项目原本以
  Tomcat 8 为基线。

发布级下一轮要求：独立压测端、每轮至少 30 秒、至少 5 个新进程、记录 CPU/GC/direct memory，
并增加饱和、过载和 1 小时长稳测试。
