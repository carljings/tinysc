# tinysc

[![Java 8 CI](https://github.com/carljings/tinysc/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/carljings/tinysc/actions/workflows/ci.yml)

tinysc 是一个面向单 WAR 部署的轻量 Servlet 容器。

当前开发线是 `1.x Classic`：Java 8、Servlet 3.1、`javax.servlet`。`2.x Modern`
将在 `1.0.0` 发布后从中立内核演进到 Java 17 和 `jakarta.servlet`。

项目仍处于 `1.0.0-alpha-SNAPSHOT`，不能用于生产环境。

## 快速开始

要求 JDK 8 与 Maven 3.8.6+：

```bash
mvn clean verify

java -jar src/tinysc-launcher/target/tinysc-1.0.0-alpha-SNAPSHOT.jar \
  inspect /path/to/app.war

java -jar src/tinysc-launcher/target/tinysc-1.0.0-alpha-SNAPSHOT.jar \
  start --war /path/to/app.war --context-path /app --port 8080
```

默认仅监听 `127.0.0.1`。当前命令、默认值与限制见
[配置参考](docs/configuration.md) 和 [运行手册](docs/operations.md)。

## 文档

- [文档索引](docs/README.md)
- [总体架构](docs/architecture.md)
- [相对 Tomcat 的产品定位与证据](docs/product-positioning.md)
- [本地开发](docs/development.md)
- [配置参考](docs/configuration.md)
- [测试策略](docs/testing.md)
- [运行手册](docs/operations.md)
- [兼容性矩阵](docs/compatibility.md)
- [安全边界](docs/security.md)
- [贡献指南](docs/contributing.md)
- [治理与开源发布门禁](docs/governance.md)
- [变更记录](docs/changelog.md)
- [路线图](docs/roadmap.md)

## 当前证据

tinysc 用单进程单 WAR、事务式就绪、SHA-256 展开缓存和精简线程模型换取更短、更可预测的
启动链。当前分支已在 Java 8 下通过 `mvn -B -ntp clean verify`，共 175 项测试。下方 Probe WAR
性能数据仍来自 2026-07-22 最终候选（当时为 157 项测试），尚未包含 multipart 基线的重新测量。
该候选在
同机同参、每个并发等级 5 个独立进程且交替容器顺序的中位数如下；成对数据均为
“tinysc / Tomcat 8.5.100”：

| 并发 | 吞吐比 | ready (ms) | RSS (KB) | 线程 | FD | p99 (ms) | 失败 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 32 | 93.77% | 317 / 408 | 199,280 / 206,160 | 60 / 65 | 34 / 65 | 1 / 0 | 0 / 0 |
| 128 | 91.01% | 329 / 428 | 196,528 / 216,368 | 89 / 96 | 34 / 65 | 2 / 2 | 0 / 0 |

这里的 `ready` 是 `cold_application_ready_ms`，不是单独拆出的 deployment ready。
这组结果只满足当前可回滚切换的吞吐不低于 Tomcat 90% 安全门槛；吞吐仍未与 Tomcat 持平，
RSS 也未达到 1.0 所要求的低 30%，因此不是“全面快于 Tomcat”的发布声明。完整口径与限制见
[最终验证矩阵](docs/benchmarks/2026-07-22-final-validation-matrix.md)。

两个内部大型遗留 WAR 均已完成 L0。Legacy WAR B 已在可达业务依赖下完成 L1，并在认证后完成
两轮关键路径 L2 验收；这不等于完整 L3 兼容。其业务 `page_load` 仍约 1.36–3.03s，冷启动
183,945ms，主要由 Spring 与数据库初始化主导，不能据此宣称真实业务广义更快。Legacy WAR A
仍受外部数据库读取超时影响。Legacy WAR B 的授权切换结论带有资源漂移证据保留项，不等于完整
长稳通过。公开报告已匿名化业务名称、制品指纹、本机路径、网络地址和授权标识。

## 许可证状态

项目所有者尚未选定开源许可证。当前代码不能据此推定获得对外复制或再发布授权；首次公开发布
前的许可证、SBOM、安全渠道和维护者门禁见 [项目治理](docs/governance.md)。
