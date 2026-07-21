# ADR-0002：中立内核与类型化 Servlet 适配器

## 状态

Accepted

## 背景

1.x 核心能力需要平滑前向迁移到 2.x，同时两套 Servlet API 不能在一个运行对象中混用。

## 决策

`tinysc-kernel` 不依赖任何 Servlet namespace；1.x 使用编译期类型安全的 `javax` 适配器，
2.x 将使用独立 `jakarta` 适配器。禁止用反射伪装 Servlet 接口。

## 后果

正面：共享内核可迁移、API 错误在编译期暴露。负面：两套适配器需要分别维护和测试。

## 备选方案

共享反射适配器：拒绝，因为语义脆弱且难以通过 TCK。内核直接依赖 `javax`：拒绝，因为污染 2.x。
