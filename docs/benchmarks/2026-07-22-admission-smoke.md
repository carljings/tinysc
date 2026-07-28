# 2026-07-22 接纳冒烟记录

状态：实现级 smoke，不是性能声明，也不是 Tomcat 对比结论。

## 范围

这轮只记录本地实际启动器对匿名 probe WAR 的接纳、启动和基础请求链路结果。

## 环境

```text
host: macOS 26.5.2 arm64
JDK: Azul Zulu OpenJDK 1.8.0_322-b06
launcher: 本地实际启动器
server config: workers=4, min=1, queue=8, maxConnections=64, maxInflightRequests=12, maxInflightBytes=33554432, requestReadTimeout=30000
startup: readyMs=172, prepareMs=68
```

制品指纹：

```text
jar sha256: 53915c67ff81b1284f838191f916857c374ddac745c24f2940b8ce54078596f0
war sha256: 136ba9dbf8452e844bbc6f3d6d169736262ef21eceecc449af2ad491401c257b
```

## 结果

| 检查项 | 结果 |
|---|---:|
| 启动就绪 | 172ms |
| 预部署准备 | 68ms |
| 功能 URL | `/hello/world?name=LocalSmoke` 返回 `200` |
| 请求链路 | Filter / Servlet / Listener / Session / resource JAR 正常 |
| ApacheBench | `-k -n 5000 -c 8 /benchmark`，`5000/5000` 完成，`0` 失败，`15754.33 req/s` |
| 客户端耗时 | 同机 `0.317s` |
| 停止 | 约 `4ms` |

## 说明

- 连接、请求和字节的超限与恢复链路已经由自动化测试覆盖。
- `maxInflightBytes` 是聚合后的已接纳请求体预算，不是流式原始入口总量。
- ApacheBench 这轮自己给出了数据不可靠的警告；再加上同机 `0.317s` 的短跑结果，不能把这组数字写成性能结论，也不能用于 Tomcat 对比。
