# 项目摘要

## 项目定位
这是一个单模块的 Spring Boot 2.7 / Java 8 应用，提供静态 Web UI，用于远程只读查看日志。仓库明确强调产品边界为“日志查看器”，不提供远程 Shell 或高危命令执行能力。

## 技术与运行
- 构建工具：`Maven`
- Java 版本：`1.8`
- 主要依赖：`spring-boot-starter-web`、`spring-boot-starter-websocket`、`spring-boot-starter-validation`、`spring-boot-starter-actuator`、`sshj`
- 默认端口：`38180`，来自 `src/main/resources/application.yml`
- 配置入口：`app.config-file` 默认指向 `app-data/servers.yaml`
- 运行命令：`mvn spring-boot:run`
- 打包命令：`mvn -DskipTests package`
- 测试命令：`mvn test`

## 顶层结构
- `src/main/java/com/example/logviewer/`
  后端主代码
- `src/main/resources/application.yml`
  端口、搜索限制、实时跟随参数、配置文件位置
- `src/main/resources/static/`
  前端页面与静态资源
- `src/test/java/`
  JUnit 测试，当前以日志域逻辑为主
- `app-data/`
  本地运行配置，默认不入库

## 核心代码入口
- `src/main/java/com/example/logviewer/LogViewerApplication.java`
  Spring Boot 启动入口
- `src/main/java/com/example/logviewer/config/AppProperties.java`
  绑定 `application.yml` 中的应用配置
- `src/main/java/com/example/logviewer/logs/interfaces/`
  日志读取、搜索、项目树、连通性探测接口
- `src/main/java/com/example/logviewer/serverconfig/interfaces/BootstrapController.java`
  启动或初始化相关的服务端配置入口
- `src/main/java/com/example/logviewer/ws/`
  WebSocket 实时跟随

## 读代码建议
如果任务和日志读取或搜索有关，优先读 `logs/`；如果任务和服务器配置持久化有关，优先读 `serverconfig/`；如果任务是实时日志或推送链路，优先读 `ws/`。
