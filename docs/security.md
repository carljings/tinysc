# 安全边界

## 威胁模型

- 网络请求不可信。
- WAR、启动配置和本机管理员可信。
- WebAppClassLoader 不承担恶意应用沙箱职责。
- 一个进程一个 WAR 是隔离边界的一部分。

## 必须防御

- `Content-Length`/`Transfer-Encoding` 歧义与请求走私。
- 超长请求行、Header、Cookie、参数、multipart 与请求体。
- Slow Header、Slow Body、连接与工作队列耗尽。
- `%2f`、反斜杠、NUL、重复解码和路径穿越。
- Zip Slip、符号链接逃逸、重复 entry、压缩炸弹。
- XML 外部实体和外部 schema 访问。
- `/WEB-INF`、`/META-INF` 静态资源泄漏。
- 响应头 CR/LF 注入。
- 未配置可信代理时伪造 `X-Forwarded-*`。
- 错误页失败重入和 partial body 泄漏：自定义 error-page 失败时必须回退到一次安全 `500`，不能递归
  选择错误页或把半截正文继续返回给客户端。

## 发布安全门禁

- 依赖漏洞扫描和 SBOM。
- HTTP parser/URI/Cookie/multipart 模糊测试。
- 默认最小权限、管理端口仅 loopback。
- 关闭远程部署与远程停止能力。
- 安全问题不等待 minor 版本，按受影响维护线发布 patch。
