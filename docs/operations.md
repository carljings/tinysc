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
  --worker-queue 1024
```

检查 WAR：

```bash
java -jar tinysc-1.0.0-alpha-SNAPSHOT.jar inspect /path/to/app.war
```

未知、重复或缺少值的参数会以退出码 2 拒绝；不会静默忽略拼写错误。`--war` 当前也接受一个
exploded WebApp 目录，可用于开发测试跳过 WAR 打包与展开。

## 运行目录

```text
$TINYSC_BASE/
└── work/<context>/<war-sha256>/

$JAVA_IO_TMPDIR/
└── tinysc-webapp-*/
```

- 源 WAR 只读，不在原位置展开或修改。
- `$TINYSC_BASE/work/` 是按 Context 和 WAR SHA-256 隔离的可重建展开缓存。
- Servlet 临时目录由 `java.io.tmpdir` 提供，正常停止时删除；异常退出后的残留可在确认进程停止后清理。
- alpha 尚未创建 `conf/`、`logs/` 或独立 `temp/`；配置来自命令行，日志写入标准输出/标准错误，
  由 systemd、Docker 或调用脚本负责收集。
- 日志、配置和运行数据不得写入源 WAR。
- 生产使用精确版本，不使用 `latest`。

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
