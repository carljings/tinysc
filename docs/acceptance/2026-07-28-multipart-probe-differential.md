# 2026-07-28 multipart Probe WAR 差分验收

## 结论

通过。TinySC 与 Tomcat 8.5.100 使用同一份 Probe WAR、同一 JDK 和逐字节相同的 multipart 请求时：

- XML 声明的 64-byte 上限整体覆盖类上的 8-byte 注解，9-byte 文件均返回 `200`，核心正文一致；
- 只使用 `@MultipartConfig` 的映射上传 7-byte 文件时均返回 `200`，核心正文一致；
- annotation-only 映射上传 9-byte 文件时均返回 `500`；
- 空 `<multipart-config/>` 使用 Servlet 默认无限额配置，9-byte 文件均返回 `200`，核心正文一致；
- XML 映射上传 65-byte 文件时均返回 `500`。

这只证明本轮 Probe 场景的配置优先级、基础语义和限额结果一致，不代表真实 Legacy WAR 上传、
流式上传、完整 Servlet 3.1 或性能门禁已经通过。

## 环境与制品

| 项目 | 值 |
|---|---|
| 日期 | 2026-07-28，Asia/Shanghai |
| Java | OpenJDK `1.8.0_322` |
| 对照容器 | Apache Tomcat `8.5.100` |
| Probe WAR SHA-256 | `546a0c04f32779f5a4050c7d9f62d8bd756c4d0d16e7f2baa476fa80b8ebc9f8` |
| TinySC shaded JAR SHA-256 | `1c228c93292a319d17a8edfd15589978ec133eca8dcebcd36105687f4dc28780` |
| 原始证据 | `work/benchmark/results/multipart-annotation-diff-20260728-153347/`（Git 忽略） |

Probe WAR 的 `web.xml` 显式设置 `metadata-complete=true`。TinySC 和 Tomcat 分别使用全新的
隔离 base 目录与独立回环端口。两者均等待同一 WAR 的
`/probe/benchmark` 可访问后再发送请求，测试结束后停止对应进程；四个临时端口均已释放。

## 请求与结果

两个容器接收逐字节相同、带 quoted boundary 的 `multipart/form-data` 请求，字段为
`title=monthly`，文件字段名为 `document`。

| 用例 | TinySC | Tomcat 8.5.100 | 判定 |
|---|---:|---:|---|
| XML 映射，9-byte 文件；注解上限为 8、XML 上限为 64 | `200` | `200` | 通过 |
| annotation-only 映射，7-byte 文件 | `200` | `200` | 通过 |
| annotation-only 映射，9-byte 文件 | `500` | `500` | 通过 |
| 空 XML 配置映射，9-byte 文件 | `200` | `200` | 通过 |
| XML 映射，65-byte 文件 | `500` | `500` | 通过 |

XML 覆盖成功响应体在两个容器上均为：

```text
title=monthly;file=override.txt;size=9;type=text/plain;payload=123456789
```

annotation-only 成功响应体在两个容器上均为：

```text
title=monthly;file=annotated.txt;size=7;type=text/plain;payload=content
```

空 XML 配置成功响应体在两个容器上均为：

```text
title=monthly;file=empty-config.txt;size=9;type=text/plain;payload=123456789
```

原始证据目录保存 `summary.txt`、固定请求体、两个容器的启动日志，以及每个用例的响应体。公开文档
不记录本机绝对路径、网络地址、内部应用名称或授权信息。

## 兼容选择

- `@MultipartConfig` 只为已经注册的 Servlet 提供缺省配置，不等于支持 `@WebServlet` 发现。
- XML/SCI 显式配置整体优先，不和注解逐字段拼接。
- TinySC 匹配 Tomcat 8.5.100 的运行时回退：`metadata-complete=true` 不关闭
  `@MultipartConfig` 反射。该点是旧 WAR 兼容选择，不作为严格 Servlet 3.1
  metadata-complete 合规声明；详见 [ADR-0014](../adr/0014-tomcat-compatible-multipart-annotation.md)。

## 边界

- 本次没有测量吞吐、延迟、RSS、线程或 FD，不能形成新的性能声明。
- TinySC 传输层仍会完整聚合请求体，因此该结果不能表述为流式上传。
- Request 当前保存入口 Servlet 的配置快照，forward/async/error 派发后的 multipart 配置切换仍未完成。
- 真实 Legacy WAR 上传必须另行执行 L2/L3 业务验收，不能由 Probe 结果替代。
- 超限场景当前只比较容器最终状态码；自定义 error-page 尚未实现。
