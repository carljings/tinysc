# 2026-07-28 multipart Probe WAR 差分验收

## 结论

通过。TinySC 与 Tomcat 8.5.100 使用同一份 Probe WAR、同一 JDK 和同一组原始 multipart 请求时：

- 7-byte 文件上传均返回 `200`，核心响应体完全一致；
- 65-byte 文件超过 WAR 声明的 64-byte 上限时均返回 `500`。

这只证明本轮 Probe 场景的基础语义和限额结果一致，不代表真实 Legacy WAR 上传、流式上传、完整
Servlet 3.1 或性能门禁已经通过。

## 环境与制品

| 项目 | 值 |
|---|---|
| 日期 | 2026-07-28，Asia/Shanghai |
| Java | OpenJDK `1.8.0_322` |
| 对照容器 | Apache Tomcat `8.5.100` |
| Probe WAR SHA-256 | `e10ac87c384050fc3f6fb6a7feaaeb49ead47fb7fd172281749a4801c04f3e5f` |
| TinySC shaded JAR SHA-256 | `92b6293e4290e3582cf9cd1635354f36791b11514d38c2655128b82c6fc54ed8` |
| 原始证据 | `work/benchmark/results/multipart-diff-20260728-143551/`（Git 忽略） |

TinySC 和 Tomcat 分别使用全新的隔离 base 目录与独立回环端口。两者均等待同一 WAR 的
`/probe/benchmark` 可访问后再发送请求，测试结束后停止对应进程。

## 请求与结果

两个容器接收字节相同、带 quoted boundary 的 `multipart/form-data` 请求，字段为
`title=monthly`，文件字段名为 `document`。

| 用例 | TinySC | Tomcat 8.5.100 | 判定 |
|---|---:|---:|---|
| `plan.txt`，`text/plain`，内容 `content`（7 bytes） | `200` | `200` | 通过 |
| `large.txt`，`text/plain`，内容 65 bytes | `500` | `500` | 通过 |

成功响应体在两个容器上均为：

```text
title=monthly;file=plan.txt;size=7;type=text/plain;payload=content
```

原始证据目录保存 `summary.txt`、两个容器的启动日志，以及成功/超限响应体。公开文档不记录本机
绝对路径、网络地址、内部应用名称或授权信息。

## 边界

- 本次没有测量吞吐、延迟、RSS、线程或 FD，不能形成新的性能声明。
- Probe WAR 使用 `web.xml` 的 `<multipart-config>`；`@MultipartConfig` 注解合并仍未实现。
- TinySC 传输层仍会完整聚合请求体，因此该结果不能表述为流式上传。
- 真实 Legacy WAR 上传必须另行执行 L2/L3 业务验收，不能由 Probe 结果替代。
- 超限场景当前只比较容器最终状态码；自定义 error-page 尚未实现。
