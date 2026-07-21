# 性能目标与 Tomcat 对照方法

tinysc 的产品亮点必须由可复现数据支撑。本页定义测试口径；结果未达到门槛前，文档只能写
“目标”，不能写“已经比 Tomcat 更快”。

## 1. 对照原则

tinysc 1.x 默认对照 Tomcat 8.5 的当前生产基线。每组测试必须满足：

- 同一台物理机、同一操作系统、同一 JDK 8 发行版。
- 同一个 WAR 或语义完全相同的 Probe WAR。
- 相同 JVM 堆、GC、编码、网络 backlog 和 worker 最大并发。
- 相同 HTTP keep-alive、请求体、响应体和客户端并发。
- 固定 CPU 核心或至少记录系统背景负载。
- 预热轮次与正式采样分离，原始结果和命令一并归档。
- 至少 5 次独立进程重复，报告中位数和离散程度，不只挑最快一次。

## 2. 分开测量的启动时间

| 指标 | 起点 | 终点 |
|---|---|---|
| Container ready | JVM 进程创建 | HTTP connector 可安全接收请求 |
| Deployment ready | JVM 进程创建 | Listener/Filter/load-on-startup Servlet 初始化完成 |
| Application ready | JVM 进程创建 | 业务健康 URL 首次返回预期响应 |

业务启动可能被数据库、Redis、SSO 或配置中心主导，不能把外部依赖时间归因给容器。

## 3. 核心指标

| 维度 | 指标 |
|---|---|
| 制品 | 基础发行包压缩/展开大小、依赖数量 |
| 空载 | RSS、Java heap used、direct memory、线程数、FD 数 |
| 启动 | 三类 ready 时间、重复启动缓存命中时间 |
| 并发 | requests/s、成功率、p50/p95/p99/p99.9 |
| 饱和 | 最大稳定并发、队列深度、拒绝数、GC、CPU |
| 稳定 | 1h/24h 后 RSS、线程、FD、吞吐漂移 |

## 4. 1.0 产品门槛

这些是目标门槛，可在首轮基准后通过 ADR 调整，但不得静默降低：

- Probe WAR 空载 RSS 至少比 Tomcat 8.5 低 30%。
- Probe WAR 的 deployment ready 中位数至少快 40%。
- 同等最大 worker 与成功率下，吞吐不低于 Tomcat，p99 不劣化超过 5%。
- 过载时内存保持有界，返回明确 503，不因无限队列导致长尾持续恶化。
- 同一大 WAR 的第二次启动因 SHA 缓存显著快于首次启动，并报告具体比例。
- 真实 WAR 报告必须同时给出容器阶段与业务外部依赖阶段，不能混淆。

若某项未达标，应公开记录结果和根因；兼容正确性、安全性优先于为了跑分放宽校验。

## 5. 开发/测试快速启动设计

- `--war /path/to/exploded-webapp` 可直接运行构建目录，跳过 WAR 生成和展开。
- 展开缓存以源 WAR SHA-256 和容器元数据格式版本作为键。
- descriptor/字节码扫描结果可缓存，但命中前必须校验输入指纹。
- 不启用生产不需要的多应用目录轮询和热部署线程。
- 后续可提供外部进程级重启器；不在同一 ClassLoader 中做脆弱的类级热替换。

## 6. 报告格式

每份对照报告至少记录：

```text
date / host / OS / CPU / memory
JDK vendor + exact version
tinysc commit + artifact SHA-256
Tomcat exact version + configuration SHA-256
WAR path + SHA-256
JVM flags / container limits / benchmark command
warmup / duration / repetitions
raw result location
summary and known deviations
```

## 7. 可执行基准

仓库提供 `src/tools/benchmark/compare-tomcat.sh`。它会：

- 对 tinysc 与 Tomcat 使用同一 JDK、堆、WAR、worker 上限和请求；
- 交替容器顺序，避免所有样本固定受前后顺序影响；
- 在压测前核对响应体 SHA-256 与 Filter Header；
- 记录每轮启动、RSS、线程、FD、吞吐、失败数和分位数；
- 生成 `results.csv`、`summary.csv`、环境指纹、原始 ApacheBench 输出和容器日志。

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) \
TOMCAT_HOME=/absolute/path/to/apache-tomcat-8.5.100 \
bash src/tools/benchmark/compare-tomcat.sh
```

首轮结果见 [2026-07-21 预备基准](benchmarks/2026-07-21-probe-vs-tomcat-8.5.100.md)。
该轮启动门槛初步通过，但 RSS 与 p99 门槛未通过，因此尚不能形成正式性能声明。
