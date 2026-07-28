# tinysc 文档索引

文档与源码分开维护。根目录 `README.md` 只作为项目入口，规范性文档均位于本目录。

| 文档 | 内容 |
|---|---|
| [architecture.md](architecture.md) | 系统边界、模块、生命周期和请求链路 |
| [tomcat-source-map.md](tomcat-source-map.md) | Tomcat 源码职责分类与 tinysc 取舍 |
| [product-positioning.md](product-positioning.md) | 相对 Tomcat 的可验证亮点、边界和声明规则 |
| [development.md](development.md) | 构建环境、目录规则和开发流程 |
| [contributing.md](contributing.md) | 贡献流程、代码边界与行为准则 |
| [governance.md](governance.md) | 决策、维护、支持与开源发布门禁 |
| [licenses/README.md](licenses/README.md) | shaded 二进制的第三方许可证与 NOTICE 清单 |
| [changelog.md](changelog.md) | 用户可见变更和当前已知限制 |
| [testing.md](testing.md) | 单测、契约、真实 WAR 与差分验收 |
| [performance.md](performance.md) | 与 Tomcat 的公平基准、性能预算和结果格式 |
| [operations.md](operations.md) | 启动、停止、目录和故障排查 |
| [configuration.md](configuration.md) | CLI 参数、默认值、固定限制与生产建议 |
| [compatibility.md](compatibility.md) | 版本选择与兼容承诺 |
| [security.md](security.md) | 威胁模型和安全门禁 |
| [release.md](release.md) | 分支、版本、制品与发布检查表 |
| [roadmap.md](roadmap.md) | alpha 到 2.x 的里程碑 |
| [benchmarks/2026-07-21-probe-vs-tomcat-8.5.100.md](benchmarks/2026-07-21-probe-vs-tomcat-8.5.100.md) | 首轮同机性能正反结果，属于预备证据 |
| [benchmarks/2026-07-22-elastic-worker-smoke.md](benchmarks/2026-07-22-elastic-worker-smoke.md) | 弹性 Worker 扩容、过载恢复和空闲回收证据，非性能声明 |
| [benchmarks/2026-07-22-admission-smoke.md](benchmarks/2026-07-22-admission-smoke.md) | 请求准入、真实启动和本地请求链路证据，非性能声明 |
| [benchmarks/2026-07-22-final-validation-matrix.md](benchmarks/2026-07-22-final-validation-matrix.md) | 当前优化与目标环境切换门禁；与 1.0 发布门禁分开记录 |
| [research/2026-07-22-servlet-container-design-review.md](research/2026-07-22-servlet-container-design-review.md) | Jetty、Undertow、Tomcat 与 Netty 的设计取舍 |
| [acceptance/2026-07-21-legacy-war-acceptance.md](acceptance/2026-07-21-legacy-war-acceptance.md) | 两个匿名化真实 WAR 的分阶段验收证据 |
| [acceptance/2026-07-22-legacy-war-b-cutover.md](acceptance/2026-07-22-legacy-war-b-cutover.md) | Legacy WAR B 失败回滚、兼容修复与最终受控切换证据 |
| [acceptance/2026-07-28-multipart-probe-differential.md](acceptance/2026-07-28-multipart-probe-differential.md) | 同一 Probe WAR 在 TinySC 与 Tomcat 8 上的 XML/注解 multipart 差分 |
| [adr/README.md](adr/README.md) | 架构与项目决策记录索引 |

文档必须与可执行行为同步。尚未实现的能力统一标记为“计划”，不得写成已支持。
基准和 smoke 文档只用于证据归档；未达门槛前，不得把它们写成正式性能声明。
