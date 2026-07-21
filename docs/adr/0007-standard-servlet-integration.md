# ADR-0007：框架只经标准 Servlet 接入

## 状态

Accepted

## 背景

真实应用包含 Spring、Struts、CXF、Shiro、Druid 等框架。为每个框架写容器特例不可维护。

## 决策

应用框架仅通过 Servlet、Filter、Listener、SCI、Session、Async 等标准扩展点运行。
禁止按业务项目名或框架类型走私有分支。

## 后果

正面：兼容改进可泛化，行为可用规范和 Tomcat 差分验证。负面：必须实现足够完整的 Servlet 语义。

## 备选方案

为两个验收项目写适配插件：拒绝，因为会掩盖容器兼容缺陷。
