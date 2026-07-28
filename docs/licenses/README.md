# 第三方许可证清单

本清单对应 `tinysc-launcher` 的 `1.0.0-alpha-SNAPSHOT` shaded JAR。TinySC 自身采用
[Apache License 2.0](../../LICENSE)；源码见仓库根目录 `LICENSE`，二进制见 `META-INF/LICENSE`。
项目与 Apache 软件基金会不存在隶属或背书关系。

| 组件 | 版本 | 许可证 | 随制品保留方式 |
|---|---:|---|---|
| Netty（common、buffer、codec-base、codec-compression、codec-http、handler、resolver、transport、transport-native-unix-common） | 4.2.16.Final | Apache-2.0 | `META-INF/LICENSE` |
| JCTools Core（由 Netty Common 内嵌并重定位） | 4.0.6 | Apache-2.0 | `META-INF/LICENSE` |
| ASM | 9.8 | BSD-3-Clause | `META-INF/licenses/ASM-9.8-LICENSE.txt` |
| Apache Commons Compress | 1.28.0 | Apache-2.0 | `META-INF/LICENSE`、`META-INF/NOTICE` |
| Apache Commons Codec | 1.19.0 | Apache-2.0 | `META-INF/LICENSE`、`META-INF/NOTICE` |
| Apache Commons IO | 2.20.0 | Apache-2.0 | `META-INF/LICENSE`、`META-INF/NOTICE` |
| Apache Commons Lang | 3.18.0 | Apache-2.0 | `META-INF/LICENSE`、`META-INF/NOTICE` |
| Apache Commons FileUpload | 1.6.0 | Apache-2.0 | `META-INF/LICENSE`、`META-INF/NOTICE` |
| Java Servlet API (`javax.servlet-api`) | 3.1.0 | CDDL 1.0 或 GPL v2 with Classpath Exception | `META-INF/licenses/javax.servlet-api-3.1.0-LICENSE.txt` |

其中 Java Servlet API 的许可证文本原样取自
`javax.servlet-api-3.1.0.jar!/META-INF/LICENSE.txt`；ASM 的许可证文本取自 ASM 9.8
上游发行信息。Apache Commons 的原始 NOTICE 已合并到仓库根目录 [NOTICE](../../NOTICE)，并随
二进制写入 `META-INF/NOTICE`。

依赖升级必须同步更新本清单、法律文本、NOTICE 和
`ReleaseArtifactTest`。本清单说明当前二进制所含依赖，不替代发布检查表要求的正式 SBOM。
