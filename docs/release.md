# 发布与分支

## 分支策略

1. `main` 完成 tinysc 1.0。
2. 发布并标记 `v1.0.0`。
3. 创建长期维护分支 `1.x`。
4. `main` 升级为 `2.0.0-SNAPSHOT`，切换 Java 17/Jakarta。
5. 共享问题先修最老受影响版本，再向前合并。

## 制品命名

```text
tinysc-1.0.0-bin.tar.gz
tinysc-2.0.0-bin.tar.gz
```

Docker 标签：

```text
tinysc:1.0.0-jdk8
tinysc:1-jdk8
tinysc:2.0.0-jdk17
tinysc:2-jdk17
```

禁止生产使用 `latest`。

## 发布检查表

- [ ] 完整 Maven reactor 通过。
- [ ] 真实 JDK 8 测试通过，class major ≤ 52。
- [ ] kernel 不含 Servlet namespace。
- [ ] Probe WAR 契约测试通过。
- [ ] 两个真实 WAR 达到当前里程碑等级。
- [ ] 差分、安全、资源与长稳报告归档。
- [ ] 源码、二进制、SBOM 和校验和可复现。
- [ ] CHANGELOG、升级说明、运维文档同步。
- [ ] 开源许可证由项目所有者明确选定。
