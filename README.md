# Log Viewer

Docker 部署的只读日志查看器（Spring Boot 2.7 + Java 8 + Web UI）。

功能（V1）：

- 两台模板服务器配置与切换（配置文件保存）
- 服务器鉴权仅支持用户名 + 密码
- 两级项目树扫描：`/home/devops/deploy/backend/**/**`
- 查看 `logs` 下业务日志 / 异常日志
- 实时日志（`tail -F` 风格）
- 历史回看（分块读取）
- 搜索范围：当前文件 / 当天文件 / 全部日志文件
- 日志轮转识别（`.0 -> .1`、跨天）
- 只读访问、禁止高危操作

低影响保护（默认开启）：

- 实时日志使用低优先级 `tail -F`（若服务器支持 `nice/ionice` 会自动降优先级）
- 日志轮转检查降低频率，且仅在日志短时空闲时才扫描目录
- 搜索默认启用严格上限（文件数/扫描字节数/命中数/超时）
- 历史读取使用分块读取，避免全文件读取

## 本地运行

1. 修改 `app-data/servers.yaml`（每台服务器仅需：`id/name/host/port/username/passwordEncrypted/rootPath`）
2. 运行：

```bash
mvn spring-boot:run
```

打开 `http://localhost:8080`

## Docker 运行

```bash
docker compose up --build
```

## 说明

- 工具仅支持只读日志查看，不提供任意命令执行。
- 页面是终端风格日志视图，但不是 Web Shell。
