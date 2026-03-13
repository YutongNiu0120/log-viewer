# 功能索引

## 启动与全局配置
- 关注原因：决定端口、配置文件路径、搜索上限和实时跟随参数
- 先看路径：`src/main/java/com/example/logviewer/LogViewerApplication.java`
- 再看路径：`src/main/java/com/example/logviewer/config/AppProperties.java`
- 配置文件：`src/main/resources/application.yml`

## 日志浏览与搜索
- 关注原因：这是仓库最核心的业务能力，包含项目树扫描、分块读取、搜索范围控制与日志轮转识别
- 先看路径：`src/main/java/com/example/logviewer/logs/interfaces/LogController.java`
- 再看路径：`src/main/java/com/example/logviewer/logs/interfaces/ProjectController.java`
- 服务实现：`src/main/java/com/example/logviewer/logs/application/RemoteCommandLogService.java`
- 领域对象：`src/main/java/com/example/logviewer/logs/domain/`
- 基础设施：`src/main/java/com/example/logviewer/logs/infrastructure/`

## 远程访问与安全边界
- 关注原因：仓库通过 SSH 访问远端日志，但必须保持只读，路径和命令构造属于高风险区域
- 先看路径：`src/main/java/com/example/logviewer/logs/infrastructure/RemoteLogClient.java`
- 实现路径：`src/main/java/com/example/logviewer/logs/infrastructure/SshRemoteLogClient.java`
- 安全辅助：`src/main/java/com/example/logviewer/logs/infrastructure/PathGuard.java`
- 命令转义：`src/main/java/com/example/logviewer/logs/infrastructure/ShellQuoter.java`

## 服务器配置管理
- 关注原因：服务器模板、凭据加密和配置文件存储都在这一层
- 先看路径：`src/main/java/com/example/logviewer/serverconfig/application/ServerConfigService.java`
- 仓储实现：`src/main/java/com/example/logviewer/serverconfig/infrastructure/ServerConfigFileRepository.java`
- 加密逻辑：`src/main/java/com/example/logviewer/serverconfig/infrastructure/CredentialCipher.java`
- 接口入口：`src/main/java/com/example/logviewer/serverconfig/interfaces/BootstrapController.java`

## 实时跟随
- 关注原因：实时日志使用 WebSocket，下钻时通常需要同时看 handler、service 与配置
- 先看路径：`src/main/java/com/example/logviewer/ws/WebSocketConfig.java`
- 处理器：`src/main/java/com/example/logviewer/ws/LogWebSocketHandler.java`
- 服务：`src/main/java/com/example/logviewer/ws/LogFollowService.java`

## 前端静态页面
- 关注原因：前端不是独立工程，页面交互直接落在静态资源中
- 页面入口：`src/main/resources/static/index.html`
- 行为脚本：`src/main/resources/static/app.js`
- 样式：`src/main/resources/static/styles.css`

## 测试入口
- 关注原因：当前测试主要覆盖日志域纯逻辑，改动解析、排序、搜索限制时应优先补这里
- 路径：`src/test/java/com/example/logviewer/logs/`
