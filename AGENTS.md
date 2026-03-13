# Repository Guidelines

## Project Structure & Module Organization
This is a single-module Spring Boot 2.7 / Java 8 application with a static web UI.

- `src/main/java/com/example/logviewer/`: backend code
- `config/`: typed application settings
- `logs/`: log browsing, search, and remote access logic
- `serverconfig/`: persisted server configuration management
- `ws/`: WebSocket follow mode
- `shared/`: common API error handling
- `src/main/resources/static/`: frontend assets (`index.html`, `app.js`, `styles.css`)
- `src/test/java/`: JUnit tests, currently focused on domain logic
- `app-data/`: local runtime configuration, ignored by Git

## Build, Test, and Development Commands
- `mvn spring-boot:run`: run locally with the default port from `application.yml` (`38180` unless overridden)
- `mvn test`: run unit tests
- `mvn -DskipTests package`: build the runnable jar in `target/`
- `docker compose up --build`: build and start the containerized app
- `java -jar target/log-viewer-0.1.0.jar --server.port=38181`: run the packaged jar on an alternate port

After code changes, restart the active runtime before handoff. Use `docker compose up -d --build` for the Docker workflow or restart `mvn spring-boot:run` / the packaged jar, then verify the updated app is reachable.

## Coding Style & Naming Conventions
Follow the existing style in each file type.

- Java: 4-space indentation, `PascalCase` classes, `camelCase` methods/fields
- Frontend and YAML: 2-space indentation
- Keep package structure feature-first (`logs`, `serverconfig`, `ws`) and then layer by `application`, `domain`, `infrastructure`, `interfaces`
- Name controllers and DTOs explicitly, for example `LogController`, `SearchRequest`

No formatter or linter is currently enforced in the build, so keep changes small and consistent with surrounding code.

## Testing Guidelines
Tests use Spring Boot Test and JUnit 5 through `spring-boot-starter-test`.

- Place tests under `src/test/java` matching production packages
- Name test classes `*Test`
- Prefer focused unit tests for parsing, ordering, path safety, and search limits before adding broader integration coverage
- Run `mvn test` before opening a PR

## Commit & Pull Request Guidelines
Current history uses concise conventional-style commits, for example `chore: initialize log-viewer`. Continue with prefixes such as `feat:`, `fix:`, `refactor:`, and `chore:`.

PRs should include:

- a short behavior summary
- any config or operational impact
- test evidence (`mvn test`, manual Docker check, UI verification)
- screenshots for UI changes in `src/main/resources/static/`

When the user explicitly asks to "submit" or "提交代码", treat that as a request to `git commit` the current branch and `git push` it to GitHub after verification succeeds.

## Security & Configuration Tips
Do not commit `app-data/`, generated logs, or real credentials. Prefer environment variables such as `APP_PORT`, `CONFIG_FILE`, and `CONFIG_SECRET`. Preserve the product boundary: this repository is a read-only log viewer, not a remote shell.

## 项目记忆
以下区块由 Codex 维护，用于降低后续会话的仓库扫描成本。默认先读这里，再做大范围检索。

- 入口：`.project-memory/README.md`
- 项目摘要：`.project-memory/generated/project-summary.md`
- 功能索引：`.project-memory/generated/feature-index.md`
- 开发手册：`.project-memory/generated/development-playbook.md`
- 当前关注：`.project-memory/memory/current.md`
- 关键决策：`.project-memory/memory/decisions.md`
- 里程碑：`.project-memory/memory/milestones.md`

规则：

- `.project-memory/generated/` 可由 Codex 初始化或刷新
- `.project-memory/memory/` 为人工/长期记忆，不应在刷新时覆盖
- 仅当仓库存在 `AGENTS.md` 时才维护 `.project-memory`
- 该区块及新增项目记忆文档默认使用中文，代码标识与路径保持原文
