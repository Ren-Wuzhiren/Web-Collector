# Web Collecting And Content Saving System

一个面向个人使用的 Web 收藏与内容保存系统。第一版目标是把一个 URL 稳定地保存为可检索、可回看的资料快照，包括网页元数据、HTML、PDF 和截图。

## 当前状态

- 当前阶段：后端 M6.1 与基础 M7/M8 骨架已完成；下一步 M6.2/M6.3、浏览器 E2E 和真实 URL 验收。
- 后端已实现鉴权、集合、标签、链接、持久化任务、真实 Capture Worker、HTML/PDF/截图资产、正文抽取与搜索。
- 后端 `mvn -pl backend test` 全量测试 327 项通过，0 失败、0 错误、4 跳过；真实 MySQL 8.4 执行 V1-V14，Redis 7.4 集成正常。
- 自动调度默认启用：Worker 领取任务后生成三格式快照、登记成功资产、写入正文并完成/失败收尾。
- `GET /api/v1/search?q=...` 使用 ngram FULLTEXT，强制按当前用户隔离。
- Vue 前端已有登录、收藏、保存、任务轮询、资产打开/下载与搜索页面；`npm ci && npm run build` 通过。
- 全栈 Compose、Nginx、CI、备份恢复和真实 URL 验收脚本已加入；Docker 镜像构建受当前 Docker Hub 网络超时阻断，尚未完成容器启动验证。
- 上游 web/worker 已在本地跑通，首页检查返回 HTTP 200；上游源码仍只用于研究和对照。
- 参考源码固定在 Linkwarden `v2.16.3`，只用于研究和对照，不直接进入本项目提交。

## 第一版范围

核心闭环：

1. 保存 URL。
2. 异步抓取网页并生成 HTML、PDF、截图。
3. 用集合和标签整理。
4. 通过标题、描述和正文文本搜索。
5. 打开详情页查看快照和阅读视图。

第一版明确不做：协作权限、公开分享、浏览器扩展、移动端、AI 标签、RSS、SSO、嵌套集合、MinIO、Meilisearch。

## 计划技术栈

- 后端：Java 17、Spring Boot 3.x、MyBatis-Plus、Sa-Token + Redis 会话、MySQL 8、Redis。
- 采集：Playwright Java、Jsoup/Readability4J、独立 worker profile。
- 前端：Vue 3、Vite、Pinia、Vue Router、Element Plus。
- 部署：Docker Compose、Nginx。

JWT 不作为第一版必需依赖；如果需要学习 JWT，应在鉴权闭环稳定后作为可替换实现单独评估。

## Maven 结构

根目录 `pom.xml` 是聚合工程，`backend/pom.xml` 是 Spring Boot 模块。IDEA 打开仓库根目录后，可以通过 Maven 工具窗口执行根工程构建。

## 目录

```text
PROJECT.md               # 本项目背景、技术栈与当前阶段卡
AGENTS.md                 # 协作与学习规范
new_stantard/              # 当前采用的 agent-kit 与 prompt-system 标准来源
docs/                     # 需求、研读、工程化、复盘和四大件笔记
reference/linkwarden/     # 上游参考源码，本地克隆且不提交
backend/                  # Java 后端，业务代码由用户亲手实现
frontend/                 # 阶段 6 起由 Codex 生成并逐段讲解
deploy/                   # Docker、Nginx 和运行配置
```

## 参考源码

Linkwarden 使用 AGPL-3.0。参考源码仅用于理解问题、架构和设计取舍。若未来复制、翻译或分发上游代码，必须单独评估 AGPL 义务，并避免复用其品牌和原始资源。

## 文档入口

- [项目卡](PROJECT.md)
- [当前协作规范](AGENTS.md)
- [Agent Kit](new_stantard/agent-kit/README.md)
- [Prompt System](new_stantard/prompt-system/README.md)
- [新协作模式说明与回滚记录](docs/需求文档/2026-10-19-008-协作模式升级与旧模式归档.md)
- [需求与总体方案](docs/需求文档/2026-09-12-001-项目总体方案与第一版范围.md)
- [Linkwarden 源码研读](docs/源码研读/README.md)
- [技术选型与架构](docs/工程化文档/04-技术选型与架构.md)
- [数据库设计](docs/工程化文档/05-数据库设计.md)
- [接口设计](docs/工程化文档/06-接口设计.md)
- [测试与验收交付](docs/工程化文档/07-测试与验收交付.md)
- [本地运行手册](docs/源码研读/06-本地运行手册.md)
