# 运行手册

> 当前为 alpha，以下启动和检查命令已经可用；生产健康端点、访问日志和完整配置文件仍在开发。

## 启动命令

```bash
java -jar tinysc-1.0.0-alpha-SNAPSHOT.jar start \
  --war /path/to/app.war \
  --context-path /example \
  --port 8080 \
  --base /opt/tinysc \
  --io-threads 2 \
  --workers 32 \
  --min-workers 8 \
  --worker-idle-timeout 60 \
  --worker-queue 100
```

检查 WAR：

```bash
java -jar tinysc-1.0.0-alpha-SNAPSHOT.jar inspect /path/to/app.war
```

未知、重复或缺少值的参数会以退出码 2 拒绝；不会静默忽略拼写错误。`--war` 当前也接受一个
exploded WebApp 目录，可用于开发测试跳过 WAR 打包与展开。
未指定 `--base` 时，当前工作目录就是实例根目录，日志默认写入工程的 `logs/tinysc.log`。
生产环境应显式传入每个实例独立的 `--base`。

## 运行目录

```text
$TINYSC_BASE/
├── logs/
│   ├── tinysc.log
│   └── tinysc.log.1 ... tinysc.log.5
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
业务应用负责。当前记录的是进程和应用控制台日志；独立 HTTP 访问日志尚未实现。

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

收到 SIGTERM 后先关闭 connector，等待在途同步/异步请求，再逆序销毁 Servlet、Filter 和已成功
初始化的 Listener。应用自建线程先获得排空时间；Log4j2 使用对应 LoggerContext 优雅关闭，仍未
停止的线程会被中断并解除 WebApp TCCL 引用。

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
