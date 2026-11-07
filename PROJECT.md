# 项目卡：Web 收藏与内容保存系统

## 一句话

个人自托管的网页保存系统：输入 URL 后保存元数据，异步采集网页快照，支持整理、检索和回看。

## 当前阶段

- 阶段：M7 工程与自动化验收完成；M8.3.1 管理员角色与运营后台已完成；当前按工程优先模式推进 M8.1 全栈容器验收。
- 上次完成：默认黑夜主题与浅色切换、顶部导航、编辑式档案卡网格/列表、六页视觉统一，以及既有 M7/M8 UI。
- 最新验证：后端 `mvn -pl backend test` 364 项通过，0 失败、0 错误、4 跳过；前端生产构建通过；完整 Playwright 31 项通过；视觉源码 lint 0 FAIL；黑夜主题五页三档视觉门禁 0 FAIL。
- 下一步：按 [M8 阶段卡](docs/需求文档/2026-11-07-028-M8-部署运维与安全阶段卡.md) 和 [M8 工程执行台账](docs/工程化文档/08-M8部署运维执行台账.md) 推进 M8.1 全栈容器，完成后进入 M8.2 备份恢复。
- 学习验收：全部历史学习题已由[统一说明](docs/需求文档/2026-11-07-030-学习验收取消与工程优先说明.md)取消，不再作为待办或阻塞项。
- 未验证：Docker 镜像实际构建（当前机器解析 Docker Hub 元数据超时）、全栈容器启动、备份恢复、限流与真实网页成功率。

## 技术栈与版本

| 层 | 选型 | 版本/状态 |
|---|---|---|
| 语言 | Java | 17 |
| 后端框架 | Spring Boot | 3.5.3 |
| 数据访问 | MyBatis-Plus | 3.5.17 |
| 数据库 | MySQL | 8.4（Testcontainers 验收） |
| 缓存/会话 | Redis + Sa-Token | Redis 7.4（Testcontainers 验收）/ Sa-Token 1.46.0 |
| 前端 | Vue 3 + Vite + Pinia + Element Plus | 基础工作区与生产构建通过 |
| 构建 | Maven | 聚合工程，后端模块 `backend` |
| 部署 | Docker Compose + Nginx | 配置已加入；容器构建受当前网络阻断 |

## 目录约定

```text
backend/src/main/java/com/webcollector/  Spring Boot 业务代码
backend/src/main/resources/db/migration/ Flyway 迁移
backend/src/test/java/                  单元、集成与 HTTP 测试
docs/需求文档/                          需求、里程碑、验收记录
docs/工程化文档/                        架构、数据库、接口与测试设计
docs/源码研读/                          Linkwarden v2.16.3 研究笔记
new_stantard/                           当前协作模式的来源文件
reference/linkwarden/                   本地上游参考，只读且不提交
```

## 关键约定

- 分层：Controller → Service → Mapper → MySQL；请求 DTO 和响应 DTO 与实体分离。
- 异常：`BusinessException` + `ErrorCode` + `GlobalExceptionHandler`。
- 返回：统一 `ApiResponse<T>`；错误包含稳定错误码和 requestId。
- 时间：通过 `Clock` 注入，数据库 `DATETIME(3)` 按 UTC 语义处理。
- 鉴权：Sa-Token + Redis，不使用 JWT。
- 测试：JUnit 5、Mockito、MockMvc、Testcontainers；主要命令 `mvn -pl backend test`。
- 生产代码由用户实现；Codex 默认负责测试、讲解、审查和文档。

## 核心数据与接口

- 项目阶段总览：[docs/项目阶段说明.md](docs/项目阶段说明.md)。
- 已落库表：`app_user`、`collection`、`tag`、`link`、`link_tag`、`capture_job`、`asset`、`asset_cleanup_job`、`admin_action_log`；V1-V17 已在 MySQL 8.4 成功执行。
- 核心接口：注册/登录；集合、标签、链接 CRUD；链接标签关联；资产授权读取/删除；`GET /api/v1/search?q=...`。
- `POST /api/v1/links` 已完成 M2.6：在同一事务中创建链接、标签关联与 `PENDING capture_job`，响应包含 `{link, job}`。
- 鉴权：`Authorization: Bearer <token>`；`app_user.role` 支持 `USER/ADMIN`，管理员角色每次请求从数据库读取。
- 管理：`GET /api/v1/admin/overview`、`GET /api/v1/admin/capture-jobs/failed`、`POST /api/v1/admin/capture-jobs/{id}/retry`；不返回其他用户正文、完整 URL、错误原文或资产内容。
- 抓取：`CaptureWorkerScheduler` 默认每 5 秒领取最多 10 条任务；Worker 在独立 work root 中一次生成三格式快照，登记成功格式并写入正文。
- 资产：`GET/DELETE /api/v1/assets/{id}`；链接或资产删除返回 `202`，文件清理由租约队列异步重试。
- 搜索：标题、描述、正文使用 MySQL ngram FULLTEXT；结果强制按当前登录用户隔离。历史链接没有正文资产时需重新采集后才有正文。
- 查询：`GET /api/v1/jobs/{id}` 返回任务状态、错误和资产摘要；`GET /api/v1/assets?linkId=...` 返回当前用户链接的资产清单。
- 部署：`deploy/docker-compose.yml` 编排 MySQL、Redis、后端和 Nginx 前端；CI、备份恢复和真实 URL 验收脚本已加入，容器运行尚未验证。

## 完成定义

- 里程碑验收用例通过，包含关键失败路径。
- 对应需求、接口或数据库文档已更新。
- 用户能解释本轮核心设计；通过验收后形成独立 Git commit。
