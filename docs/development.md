# 开发指南

## 环境

- JDK 8 为最低运行版本。
- Maven 3.8.6 或更高版本。
- 可使用较新 JDK 构建，但编译必须使用 `--release 8`。
- 发布前必须在真实 JDK 8 上再次运行测试。

## 目录规则

```text
tinysc/
├── README.md              入口索引
├── pom.xml                Maven reactor
├── docs/                  所有详细文档与 ADR
└── src/                   所有实现与测试模块
    ├── tinysc-kernel/
    ├── tinysc-deployment/
    ├── tinysc-http-netty/
    ├── tinysc-servlet-javax/
    ├── tinysc-launcher/
    ├── tinysc-testapp-javax/
    ├── tinysc-integration-tests/
    └── tools/benchmark/       可复现的 Tomcat 对照工具
```

每个 Java 模块内部使用 `src/main/java` 和 `src/test/java`。生成物只允许进入 `target/`、
运行期 `work/` 或 `logs/`，不得与源码混放。

## 构建

```bash
mvn clean verify
```

仓库的 `.mvn/maven.config` 只对 tinysc 生效，并使用不含凭据的项目级 settings 从 Maven
Central 获取开源依赖；不会修改或复用业务项目的私有 Nexus 凭据。

公开仓库的 `.github/workflows/ci.yml` 会在 pull request 以及 `main` / `1.x` 推送时，使用
Temurin JDK 8 执行同一条 `mvn -B -ntp clean verify` 门禁。workflow 只授予源码读取权限，
同一分支的新运行会取消旧运行；失败时保留 7 天 Surefire 报告用于定位。

只构建某模块及其依赖：

```bash
mvn -pl src/tinysc-launcher -am verify
```

## 代码边界

- `tinysc-kernel` 不能依赖或引用 `javax.servlet`、`jakarta.servlet`。
- 1.x 不能出现 `jakarta.servlet` 生产依赖。
- 网络层不能调用应用类；Servlet 回调只能在 worker/async executor 执行。
- 不为单个业务应用写项目名判断、硬编码路径或专属兼容分支。
- 修复共享内核问题时先修最老受影响分支，再向前合并。

## 变更流程

1. 写出可观察的失败测试或兼容案例。
2. 做最小实现。
3. 运行模块测试和完整 reactor。
4. 若涉及容器语义，更新契约/差分测试。
5. 若改变公开行为、依赖或运维方式，同步文档和 ADR。
