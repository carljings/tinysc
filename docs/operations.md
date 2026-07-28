# 运行手册

> 当前为 alpha，以下启动和检查命令已经可用；生产健康端点和完整配置文件仍在开发。

## 启动命令

```bash
java -jar tinysc-1.0.0-alpha-SNAPSHOT.jar start \
  --war /path/to/app.war \
  --context-path /example \
  --port 8080 \
  --base /opt/tinysc \
  --io-threads 4 \
  --workers 32 \
  --min-workers 2 \
  --worker-idle-timeout 60 \
  --worker-queue 100 \
  --max-connections 1024 \
  --max-inflight-request-bytes 67108864 \
  --max-raw-ingress-bytes 67108864 \
  --request-read-timeout 30000 \
  --request-body-timeout 300000 \
  --response-write-timeout 30000 \
  --access-log true
```

检查 WAR：

```bash
java -jar tinysc-1.0.0-alpha-SNAPSHOT.jar inspect /path/to/app.war
```

未知、重复或缺少值的参数会以退出码 2 拒绝；不会静默忽略拼写错误。`--war` 当前也接受一个
exploded WebApp 目录，可用于开发测试跳过 WAR 打包与展开。
未指定 `--base` 时，当前工作目录就是实例根目录，日志默认写入工程的 `logs/tinysc.log`。
生产环境应显式传入每个实例独立的 `--base`。
`--access-log` 默认开启；需要关闭时传 `--access-log false`。

## 准入与超时

- `--max-connections` 限制同时保持的 TCP 连接数；超过上限的新连接会直接关闭。
- `maxInflightRequests` 由 `--workers + --worker-queue` 推导；达到上限后，新请求会返回 `503`，随后关闭连接。
- `--max-inflight-request-bytes` 限制已经聚合且仍被保留的请求体字节；
  `--max-raw-ingress-bytes` 约束实际到达的请求体 payload，`Content-Length` 只做单请求上限早期
  `413`，不会一次性预占全量，chunked 按实际分片累计，断开/超时/失败会精确释放。
- `--request-read-timeout` 只覆盖读取/解析和 keep-alive 空闲阶段，Servlet/Async 执行不会被它中断。
- `--request-body-timeout` 是请求体总时限；没有更早响应在途时，超时返回 `408` 并关闭连接。
- `--response-write-timeout` 只在写不完成时关闭连接，是 transport guardrail，不是响应堆内存上限或完整背压。
- 同一 HTTP/1.1 channel 上的后续请求会等前一个响应 flush 完成后再继续读取。
- 当前响应仍全量堆缓冲；`ServletOutputStream.isReady()` 和 `WriteListener` 只是兼容形状，不是真正的非阻塞写。
- 同一连接上后续 pipelined 请求的 raw、解码、聚合或 Expect 失败会顺序关闭，不会抢占当前正在执行的
  exchange 或提前发出 `100 Continue`；当前响应先完成，再清理租约。为保持响应顺序，后续失败不另行插入错误响应。

## 运行目录

```text
$TINYSC_BASE/
├── logs/
│   ├── tinysc.log
│   ├── tinysc.log.1 ... tinysc.log.5
│   ├── access.log
│   └── access.log.1 ... access.log.5
└── work/<context>/<war-sha256>/

$JAVA_IO_TMPDIR/
└── tinysc-webapp-*/
```

- 源 WAR 只读，不在原位置展开或修改。
- `$TINYSC_BASE/work/` 是按 Context 和 WAR SHA-256 隔离的可重建展开缓存。
- Servlet 临时目录由 `java.io.tmpdir` 提供，正常停止时删除；异常退出后的残留可在确认进程停止后清理。
- alpha 尚未创建 `conf/` 或独立 `temp/`；配置来自命令行。
- 日志、配置和运行数据不得写入源 WAR。
- 生产使用精确版本，不使用 `latest`。

## 日志

TinySC 从启动器初始化阶段开始，将标准输出和标准错误同时写入终端与：

```text
$TINYSC_BASE/logs/tinysc.log
```

因此文件中包含 TinySC 启动、就绪、停止日志，以及业务应用写往控制台的日志。主日志追加
写入，达到 64 MiB 时按大小轮转，保留 `tinysc.log.1` 至 `tinysc.log.5`。轮转不改变终端输出，
systemd 或 Docker 仍可以按原方式采集 stdout/stderr。

启动时无法创建或打开日志文件会直接终止启动，避免应用在没有持久化启动证据的情况下运行。
业务 WAR 自行配置的 Log4j/Logback 文件 appender 不受 TinySC 接管；它们的路径、轮转和保留策略仍由
业务应用负责。启用 `--access-log` 时，独立 HTTP access log 会写入 `<base>/logs/access.log`，
按 64 MiB 轮转并保留 5 份备份；关闭进程时会 drain 队列并 flush 后退出。关闭 access log 时，
目录树中的 `access.log*` 不会由 TinySC 创建。
每行字段使用紧凑格式：`ts remote method path proto status bytes durUs ka outcome`，其中 `ts`
是 epoch millis，`ka` 是 `0/1`。`path` 只记录 URI path，不记录 query string、Header、Cookie 或
请求体；空白和控制字符会替换为 `_`。`remote` 是直接 TCP peer，不采信 `X-Forwarded-*`。

持续查看：

```bash
tail -f "$TINYSC_BASE/logs/tinysc.log"
```

## 应用授权与私密运行时覆盖

tinysc 不生成、替换或绕过业务应用自己的 License。需要机器绑定授权的 WAR，必须通过应用厂商
提供的注册流程或其明确支持的部署目录安装合法授权。授权文件只能进入目标主机的私有运行时，
不得提交到 tinysc 源码、文档、公开制品或镜像层。

如果应用把授权安装在 exploded WebApp 内，重建展开目录可能移除该文件。运维流程必须在启动前
重新应用私密覆盖并校验文件权限；不要为方便而修改原始 WAR，也不要把其他机器的授权复制过来。
日志和验收报告只记录“授权通过/失败”及失败类别，不记录机器码、授权文件名或文件内容。

## 停止

收到 SIGTERM 后先关闭 connector，等待在途同步/异步请求和已经接纳但仍处于 deferred 状态的
exchange，再逆序销毁 Servlet、Filter 和已成功初始化的 Listener。应用自建线程先获得排空时间；
Log4j2 使用对应 LoggerContext 优雅关闭，仍未停止的线程会被中断并解除 WebApp TCCL 引用。

当前尚未提供可查询的 readiness URL；“ready”以启动日志和 connector 绑定为准。这是 alpha
限制，不能写成已有生产健康检查。

## 故障判断

| 现象 | 首要证据 |
|---|---|
| 无法监听 | 端口、绑定地址、进程退出码 |
| 部署失败 | WAR SHA、namespace、descriptor、初始化异常根因 |
| 页面 404 | context path、Servlet mapping、静态资源路径 |
| 页面 500 | Filter/Servlet 堆栈与外部依赖状态 |
| 启动卡住 | 当前生命周期阶段、线程 dump、外部依赖超时 |
