# ADR-0014：Tomcat 兼容的 multipart 注解回退

## 状态

Accepted

## 背景

TinySC 已支持 `web.xml` 和 SCI 的显式 multipart 配置，但旧 WAR 也可能只在已注册 Servlet 类上声明
`@MultipartConfig`。为此扫描整个 WAR 会增加启动成本，而且 `@MultipartConfig` 本身不负责注册或
映射 Servlet。

Servlet 3.1 的 `metadata-complete=true` 要求容器忽略部署注解；Tomcat 8.5.100 的实际运行时行为存在
一个兼容差异：`StandardWrapper` 在显式 multipart 配置为空时仍从已实例化 Servlet 类读取
`@MultipartConfig`，该回退没有检查 metadata-complete。

## 决策

- 只对已经由 `web.xml` 或 SCI 注册的 Servlet 读取 `@MultipartConfig`，不实现全 WAR 注解扫描。
- Servlet holder 在首次映射请求时懒解析实际 Servlet 类并缓存结果；没有注解也缓存“无配置”。
- `web.xml` 或 SCI 设置的 `MultipartConfigElement` 属于完整显式配置，整体优先于注解，不逐字段合并；
  空 `<multipart-config/>` 也使用其默认值，不回退到注解。
- 为优先兼容目标旧 WAR 和 Tomcat 8.5.100，`metadata-complete=true` 不关闭这一运行时回退。
- 文档必须把上一条描述为 Tomcat 兼容选择，不能据此宣称严格满足 Servlet 3.1
  metadata-complete 语义。

## 验收门禁

- annotation-only Servlet 的成功与限额失败均通过 runtime 测试。
- SCI、非空 XML 和空 XML 显式配置均覆盖冲突注解。
- 同一 Probe WAR 同时包含 XML 显式配置和 annotation-only 映射；TinySC 与 Tomcat 8.5.100
  的成功响应体和超限状态码一致。
- Java 8 全量构建继续通过，不向共享内核引入 `javax.servlet`。

## 后果

### 正面

- 常见旧 WAR 无需补写 `<multipart-config>` 即可使用 `getPart(s)`。
- 没有新增启动期类扫描，保持单 WAR 快速启动边界。
- 显式配置优先级与 Tomcat 8.5.100 的 wrapper 模型一致。

### 负面

- TinySC 在 metadata-complete 这一点选择 Tomcat 实态，而非严格规范语义。
- Request 当前保存入口 Servlet 的 multipart 配置快照；forward/async/error 派发到另一 Servlet
  后的配置切换仍需随 dispatch 生命周期单独解决。
- `@WebServlet`、`@WebFilter` 和 `@WebListener` 发现仍未实现。
