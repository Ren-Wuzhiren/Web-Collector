# Backend

Spring Boot 后端已实现收藏、任务、采集、资产和搜索的主要开发链路。

实现约定：

- Java 17 + Spring Boot 3.x + MyBatis-Plus。
- API 与 Worker 使用同一代码库的不同 profile。
- 后端业务代码由用户亲手实现，Codex 先讲解再审查。
- 开始编码前先阅读 `docs/工程化文档/04-技术选型与架构.md`、`05-数据库设计.md` 和 `06-接口设计.md`。

当前状态（2026-11-03）：`mvn -pl backend test` 共 327 项通过，0 失败、0 错误、4 跳过。真实 MySQL 8.4 执行 V1-V14，Redis 7.4 用于会话集成测试。

## 已锁定依赖

- Java 17、Spring Boot 3.5.3
- MyBatis-Plus 3.5.17
- Sa-Token 1.46.0
- MySQL、Flyway、Redis
- Spring Boot Actuator、springdoc OpenAPI
- Spring Boot Test、Testcontainers

已实现能力：

- 注册、登录、会话、集合、标签、链接与所有权隔离。
- `capture_job` 状态机、并发领取、重试、租约恢复。
- Playwright HTML/PDF/截图、SSRF 防护、正文抽取与持久化。
- 手工 `runOnce()` 和默认调度入口，三格式资产登记。
- 资产授权读取、删除和持久化清理重试。
- MySQL ngram 中英文全文搜索。

Vue 基础工作区、全栈 Compose/Nginx、CI、备份恢复与真实 URL 验收脚本已加入。容器运行和真实网页 MVP 数据集验收尚未完成。

