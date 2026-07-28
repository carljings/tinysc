# ADR-0013：采用 Apache License 2.0

状态：Accepted

## 背景

tinysc 是面向长期维护和第三方扩展的基础软件。首次公开发布需要明确授予使用、修改和再分发
权限，同时为贡献者和使用者提供清晰的专利授权条款。仓库此前没有许可证，不能把公开可见等同
于已获开源授权。

## 决策

- TinySC 自身代码和文档采用 Apache License 2.0。
- 版权主体使用匿名且可持续的 `TinySC contributors`，不写入个人姓名或内部组织名称。
- 根目录保留标准 `LICENSE` 和 `NOTICE`；发布二进制必须同时携带二者。
- 第三方组件继续受各自许可证约束；非 Apache 许可证以独立文件随 shaded JAR 保留。
- 贡献按 Apache License 2.0 第 5 节处理，贡献者明确标注为 `Not a Contribution` 的内容除外。
- 采用 Apache License 2.0 不表示项目与 Apache 软件基金会存在隶属、认证或背书关系。

## 取舍

选择 Apache License 2.0，是因为它在宽松授权基础上提供明确的贡献与专利授权条款，更适合
容器这类会长期接收兼容性和性能贡献的基础软件。MIT License 更短，但不提供同等明确的专利
条款；专有许可证不符合公开协作目标。

## 影响

- 源码使用者必须遵守 Apache License 2.0 的许可证和 NOTICE 保留要求。
- 发布流程必须验证 shaded JAR 的 `META-INF/LICENSE`、`META-INF/NOTICE` 和第三方许可证清单。
- 依赖变更必须重新审计许可证；本决策不替代 SBOM、安全报告渠道、签名和正式发布门禁。
