# 项目卡：Web 收藏与内容保存系统

## 一句话

个人自托管的网页保存系统：输入 URL 后保存元数据，异步采集网页快照，支持整理、检索和回看。

## 当前阶段

- 阶段：M3.4 用假 Worker 验证并发与恢复；M3.3 已完成工程、自动化与学习验收。
- 上次完成：失败按 30/120 秒退避，第三次失败终结；过期租约恢复为 `PENDING` 或终态；全量测试 236 项通过。
- 本轮目标：用可控假 Worker 串起领取、成功、失败、退避和租约恢复；手动重新采集另切独立增量。
- 本轮不做：真实抓取、Playwright、资产存储、搜索、前端、部署和 HTTP 任务查询。

## 技术栈与版本

| 层 | 选型 | 版本/状态 |
|---|---|---|
| 语言 | Java | 17 |
| 后端框架 | Spring Boot | 3.5.3 |
| 数据访问 | MyBatis-Plus | 3.5.17 |
| 数据库 | MySQL | 8.4（Testcontainers 验收） |
| 缓存/会话 | Redis + Sa-Token | Redis 7.4（Testcontainers 验收）/ Sa-Token 1.46.0 |
| 前端 | Vue 3 | 尚未开始 |
| 构建 | Maven | 聚合工程，后端模块 `backend` |
| 部署 | Docker Compose + Nginx | 后续阶段 |

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
- 已落地表：`app_user`、`collection`、`tag`、`link`、`link_tag`、`capture_job`。
- 核心接口：注册/登录；集合、标签、链接 CRUD；链接标签关联。
- `POST /api/v1/links` 已完成 M2.6：在同一事务中创建链接、标签关联与 `PENDING capture_job`，响应包含 `{link, job}`。
- 鉴权：`Authorization: Bearer <token>`。

## 完成定义

- 里程碑验收用例通过，包含关键失败路径。
- 对应需求、接口或数据库文档已更新。
- 用户能解释本轮核心设计；通过验收后形成独立 Git commit。
