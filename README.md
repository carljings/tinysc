# tinysc

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
启动链。Probe WAR 的首轮同机同参中位数为：

| 指标 | tinysc | Tomcat 8.5.100 | 差异 |
|---|---:|---:|---:|
| 首次成功访问 | 303ms | 517ms | 快 41.4% |
| 常驻线程 | 59 | 64 | 少 7.8% |
| 文件描述符 | 50 | 121 | 少 58.7% |

RSS 只低 12.6%，p99 也尚未达标；这些数据是预备证据，不是生产级“全面快于 Tomcat”声明。
完整环境、原始结果与限制见 [性能报告](docs/benchmarks/2026-07-21-probe-vs-tomcat-8.5.100.md)。

两个内部大型遗留 WAR 均已完成 L0。Legacy WAR B 已在可达业务依赖下完成 L1，并通过标准
Filter、forward 和 Resource JAR 链路访问到应用自带的 License 注册页；正常登录与 API 仍受
应用本机授权门禁阻塞。Legacy WAR A 仍受外部数据库读取超时影响。公开报告已匿名化业务名称、
制品指纹、本机路径、网络地址和授权标识。

## 许可证状态

项目所有者尚未选定开源许可证。当前代码不能据此推定获得对外复制或再发布授权；首次公开发布
前的许可证、SBOM、安全渠道和维护者门禁见 [项目治理](docs/governance.md)。
