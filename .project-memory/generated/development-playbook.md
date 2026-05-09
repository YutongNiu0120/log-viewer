# 开发手册

## 自动维护区

### 仓库工作方式
- 这是单模块 Spring Boot 应用，没有前后端分仓
- 前端资源位于 `src/main/resources/static/`，修改后通常不需要单独构建前端
- 后端通过 `application.yml` 和环境变量控制端口、配置文件与搜索限制

### 进入仓库后的推荐阅读顺序
1. `AGENTS.md`
2. `.project-memory/generated/project-summary.md`
3. `.project-memory/generated/feature-index.md`
4. 根据任务进入 `logs/`、`serverconfig/` 或 `ws/`

### 常用验证方式
- 后端单测：`mvn test`
- 本地运行：`mvn spring-boot:run`
- Docker 验证：`docker compose up -d --build`
- 打包验证：`mvn -DskipTests package`

### 修改时的高价值约束
- 保持产品边界：只读日志查看，不扩展为远程命令执行器
- 涉及远程路径、命令拼接、SSH 调用时，优先审查 `PathGuard` 和 `ShellQuoter`
- 涉及搜索功能时，关注 `application.yml` 中的文件数、扫描字节数、命中数、超时上限
- 涉及实时日志时，通常要同时验证 WebSocket 配置和轮转检查行为

### 代码导航启发
- 业务接口通常落在 `interfaces/`，从 controller 逆向进入 `application/` 和 `infrastructure/`
- 纯逻辑与排序、解析规则在 `domain/`，这里最适合优先补单测
- `shared/` 放公共异常处理，新增 API 失败语义时应检查这里是否已经有统一模式

## 手工积累区

- 暂无。后续可在这里追加高价值实现经验、排障路径或常见修改入口；刷新自动维护区时应保留本节内容。
