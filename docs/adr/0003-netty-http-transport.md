# ADR-0003：Netty 作为 HTTP 网络底座

## 状态

Accepted

## 背景

生产 HTTP parser、TLS、HTTP/2 与背压的正确实现成本高，JDK `HttpServer` 也不足以支撑目标语义。

## 决策

使用模块化 Netty 4.2，不引入 `netty-all`；通过 BOM 锁定同一版本。1.x 先实现 HTTP/1.1，
2.x 在同一协议边界加入 HTTPS、h2/h2c 和 ALPN。

## 后果

正面：复用成熟协议栈并保留清晰适配边界。负面：需要持续跟进 Netty 安全更新，且必须避免
与 WAR 自带 Netty 发生类加载冲突。

## 备选方案

自行解析 HTTP：拒绝，安全风险不可接受。JDK `HttpServer`：拒绝，协议控制与异步能力不足。
