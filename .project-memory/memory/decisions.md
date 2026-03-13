# 关键决策

## 已确认约束

- 产品边界是只读日志查看器，不提供远程 Shell
  影响：涉及 SSH、命令拼接、前端交互时，都要避免引入任意命令执行能力。
  相关位置：`README.md`、`AGENTS.md`、`logs/infrastructure/`

- 默认运行配置通过环境变量覆盖
  影响：端口、配置文件路径和配置密钥优先通过 `APP_PORT`、`CONFIG_FILE`、`CONFIG_SECRET` 控制。
  相关位置：`src/main/resources/application.yml`
