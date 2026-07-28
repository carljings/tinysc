# 兼容性与版本选择

选择版本首先看 Servlet namespace，而不是只看应用使用的 JDK。

| tinysc | Java | Servlet | Namespace | 状态 |
|---|---:|---:|---|---|
| 1.x Classic | 8+ | 3.1 | `javax.servlet` | 当前开发线 |
| 2.x Modern | 17+ | 6.1 | `jakarta.servlet` | 计划 |
| Java 21 | 2.x 可选运行环境 | — | — | 计划中的虚拟线程执行器 |

```text
javax.servlet.*   → tinysc 1.x
jakarta.servlet.* → tinysc 2.x
```

Java 17 编译但仍使用 `javax.servlet` 的应用依然选择 1.x。1.x 到 2.x 不是容器文件的
直接替换；应用必须先完成 `javax → jakarta` 迁移。

### mixed namespace 的处理

通用依赖可能同时包含 javax/jakarta 的可选适配类，静态扫描因此会报告 mixed。处理顺序是：

1. 应用自身类和实际 Servlet/Filter/Listener 类型；
2. `web.xml` 代际（2.x/3.x/4.0 属于 javax，5.x/6.x 属于 jakarta）；
3. 依赖 JAR 中的引用。

`tinysc inspect` 对 mixed WAR 保留告警；只有部署描述符能明确代际时才给出“需核验”的建议，
否则要求人工检查，绝不静默选版本。

## 1.x 承诺

- 最低 Java 版本保持 Java 8。
- namespace 永远保持 `javax.servlet`。
- Servlet 基线锁定 3.1。
- minor/patch 不得暗中升级到 Java 17 或 Jakarta。
- 2.0 发布后只接受安全、容器 Bug 和旧项目兼容修复。

## 真实验收对象

| 项目 | WAR | Context path | 预期基线 |
|---|---|---|---|
| Legacy WAR A | 私有原始制品 | `/legacy-app` | L0；外部数据库超时阻塞 L1 |
| Legacy WAR B | 私有原始制品 | `/legacy-app` | L2 切片；登录、列表、申报和相关模板/API 通过 |

完整证据见 [真实 WAR 验收报告](acceptance/2026-07-21-legacy-war-acceptance.md)。Legacy WAR B 已
完成启动和一组认证后关键路径的只读验收，切换、失败回滚和静态模板兼容修复见
[受控切换验收](acceptance/2026-07-22-legacy-war-b-cutover.md)。上传、错误页和完整管理流程仍未覆盖，
不能据此声明完整业务兼容。

Probe WAR 的当前差分已经扩展到同步 error-page slice：`sendError(404)`、`RuntimeException`、
`ServletException(IOException root cause)` 的 status、`ERROR` filter、正文和 `Content-Length` 与
Tomcat 8.5.100 一致；`setStatus(404)` 不会触发自定义 error-page。失败 custom error-page 时，TinySC
回退到单次安全 `500` 并丢弃 partial body，而 Tomcat 保留了 partial body。这个结果只证明同步、
未提交前的 error-page 语义，不代表已完成 async error dispatch、已提交响应恢复、JSP error page 或
TCK。
