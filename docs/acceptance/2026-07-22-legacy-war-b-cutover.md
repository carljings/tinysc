# 2026-07-22 Legacy WAR B 受控切换验收

状态：Legacy WAR B 的授权目标环境切换通过，最终环境保留 tinysc。10 分钟 soak 的请求正确性、
access log 和优雅停机证据通过，但严格的线程/FD 资源漂移证据不完整；这是单应用、单环境、已列
关键路径的受控运行结论，不是 tinysc 1.0 发布或完整 Servlet 3.1 兼容声明。

本文只保留匿名应用标识和可公开的容器证据。主机地址、账号、业务日志原文和私有制品路径不进入
仓库。

## 范围

- 应用：Legacy WAR B，原始业务制品未修改。
- 基线：Java 8、Tomcat 8.5.100、`javax.servlet` / Servlet 3.1。
- 目标：同一应用、同一端口和同一外部依赖切换到 tinysc 1.x。
- 只读业务路径：登录、应用壳、业务列表、申报页面、两个 `page_load` API 和两个工作流模板。
- 日志：`<base>/logs/tinysc.log`、`<base>/logs/access.log` 和进程启动重定向日志。

## 切换过程

1. Tomcat 基线只读验收通过：3 个页面和 2 个关键 API 均为 `200`，浏览器无坏响应、请求失败、
   页面异常或控制台异常。
2. 第一候选制品启动并达到 HTTP 就绪，但两个现有 `.tpl` 静态资源的 `POST` 返回 `405`，页面随即
   出现 JavaScript 异常。该候选没有放行，失败进程被精确停止，目标端口回滚到 Tomcat，并重新
   验证 HTTP 就绪。
3. 对目标环境中的 Tomcat 8.5.100 `catalina.jar` 做字节码核验，确认其
   `DefaultServlet#doPost` 直接委托 `doGet`。tinysc 当时只允许静态 `GET` / `HEAD`，差异由此确定。
4. 修复限定为未映射静态资源：现有资源的 `POST` 与 Tomcat 8.5.100 一样读取资源；缺失资源仍为
   `404`，已映射 Servlet 仍优先处理自己的 `POST`，`PUT` / `DELETE` 仍为 `405`。
5. 最终制品完成 Java 8 全测、五轮交替对照和 10 分钟 soak 后，在授权窗口重新切换。真实应用
   达到 HTTP 就绪，认证后关键路径连续两轮通过，目标环境最终保留 tinysc。

回滚不是文档推演。第一次业务门禁失败后已经真实恢复 Tomcat 并复验；最终切换仍保留同一条、
已经走通过的回滚路径。

## 最终制品与自动化证据

最终 shaded JAR SHA-256：

```text
f1fc3e42b11c44c2c9bc7a21d307153d58d2eef5c8a6299e9765684e71760b14
```

| 门禁 | 结果 |
|---|---|
| Java 8 `mvn -B -ntp clean verify` | 157 tests，0 failure / error / skip，`BUILD SUCCESS` |
| 文件系统静态资源 | `GET` / `HEAD` 为 `200`；带 `If-Modified-Since` 的条件 `GET` 为 `304` 且无响应体 |
| Resource JAR 静态资源 | `GET` / `HEAD` 为 `200`；带 `If-Modified-Since` 的条件 `GET` 为 `304` 且无响应体 |
| 静态 `POST` 兼容 | 文件系统和 Resource JAR 的 `.tpl` 均为 `200`；另有文件系统 `.tpl` 验证携带 `If-Modified-Since` 时仍为 `200` |
| 边界语义 | 缺失 `.tpl` 的 `POST` 为 `404`；已映射 Servlet 的 `POST` 仍由 Servlet 处理 |

## Probe WAR 五轮交替对照

下表是同机、同 JDK、同 Probe WAR、每个并发等级各 5 个新进程并交替容器顺序的中位数。`ready`
是新部署目录下从启动器调用到预期 HTTP 响应的 `cold_application_ready_ms`；RSS 是预热后空载快照，
不是负载峰值，也不是 Legacy WAR B 的业务启动时间。

| 并发 | 容器 | ready | RSS | 线程 | FD | requests/s | p99 | 失败 / 非 2xx |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 32 | tinysc | 317 ms | 199,280 KB | 60 | 34 | 177,679.16 | 1 ms | 0 / 0 |
| 32 | Tomcat | 408 ms | 206,160 KB | 65 | 65 | 189,482.01 | 0 ms | 0 / 0 |
| 128 | tinysc | 329 ms | 196,528 KB | 89 | 34 | 179,289.93 | 2 ms | 0 / 0 |
| 128 | Tomcat | 428 ms | 216,368 KB | 96 | 65 | 196,996.05 | 2 ms | 0 / 0 |

tinysc 吞吐分别为 Tomcat 的 `93.77%` 和 `91.01%`，达到本次受控切换使用的 `90%` 安全下限；
它们都低于 Tomcat，不能据此写成“吞吐更高”。五轮所有正式请求的失败数和非 2xx 数均为 `0`。

## 10 分钟 soak 与证据限制

- 并发 128，正式 soak `600 s`；先完成 `300,000` 次预热，再完成 `581,632` 次 soak 请求。
- 请求失败和非 2xx 均为 `0`；`1` 次就绪探测 + `300,000` 次预热 + `581,632` 次 soak 对应
  `881,633` 行 access log，预期与实际一致，无相关告警。
- 收到终止信号后优雅停机耗时 `9 ms`。
- 运行中的 RSS 采样没有显示持续增长。

wrapper 在预期 `SIGTERM` 后以 `143` 提前退出，没有写出原计划的汇总文件。保留下来的 ApacheBench
结果、access log、启动器日志和 RSS 采样足以证明上述请求、日志和停机结论，但没有持久化线程/FD
起止漂移汇总，也没有形成完整的严格漂移阈值证明。因此本次 soak 只能对请求正确性、日志完整性和
优雅停机判定通过；资源漂移项为有条件通过、证据不完整，不能写成完整长稳通过。

## 真实 Legacy WAR B 证据

最终制品的真实应用冷启动在 `183,945 ms` 达到外部 HTTP 就绪；其中 Spring MVC 初始化记录为
`141,923 ms`，同一启动链还出现了业务数据库序列错误。该时间由大 WAR 的业务初始化和外部数据库
主导，不能当成 tinysc 容器自身开销，也不能与上面的 Probe WAR `ready` 中位数直接比较。

认证后的关键路径连续两轮通过：每轮 3 个页面、2 个 `page_load` 和 2 个 `.tpl` 均为 `200`；
bad response、request failure、page error 和 console error 均为 `0`。热态样本中，两个业务
`page_load` 约 `1.36–3.03 s`，两个 `.tpl` 约 `6 ms`。

同一静态对象首次 `GET` 返回 `200`、`51,104` 字节，耗时 `23.751 ms`；随后携带
`If-Modified-Since` 的请求返回 `304`、`0` 字节，耗时 `19.239 ms`。这验证了目标环境中的条件
请求路径；它不改变业务依赖的静态 `.tpl` `POST` 仍返回 `200` 的兼容语义。

## 已知限制

- 一次较早的冷态人工访问中，另一个首页业务 API 返回过一次 `500`，应用日志根因是业务代码
  `NullPointerException`；最终制品的两轮验收均返回 `200`。若再次出现，需要继续做应用状态与
  Tomcat 差分，不能直接归因于容器。
- Legacy WAR B 只达到本报告列出的关键路径 L2；上传、错误页、全部管理动作和长事务尚未覆盖，
  不能表述为完整 L3 差分。
- Legacy WAR A 仍受外部数据库阻塞，不属于本次 B 切换通过范围，也没有因 B 的结果而转为通过。
- 10 分钟 soak 未完整证明线程/FD 与严格 RSS 漂移阈值；1 小时和 24 小时长稳仍未执行。
- 1.0 仍缺少 TCK、multipart、完整 async dispatch、真正的非阻塞读写和更严格的发布性能门禁；
  当前状态见[最终验证矩阵](../benchmarks/2026-07-22-final-validation-matrix.md)。

## 结论

本次允许表述为：最终哈希对应的 tinysc 制品已经在授权目标环境为 Legacy WAR B 的已列关键路径
提供服务，静态模板 `POST` 与 Tomcat 8.5.100 的目标行为一致，失败回滚已经真实验证，最终环境
保留 tinysc。资源漂移证据保留项必须同时披露；不能据此宣称 tinysc 1.0 已通过、所有 Legacy WAR
均兼容或全部 Servlet 3.1 能力均已兼容。
