# 项目卡：个人博客

> 示例项目卡。已知信息来自你的描述；未知项保留“待确认”，不要臆造。
> 使用前请补齐版本、鉴权、数据库、部署等细节。

## 一句话
一个基于 Spring Boot + Vue 的个人博客网站，用于发布和管理文章。

## 技术栈与版本
- 语言：Java（版本待确认）
- 后端框架：Spring Boot（版本待确认）
- 前端框架：Vue（版本待确认）
- 数据库：MySQL（版本待确认）
- 缓存/中间件：待确认（是否使用 Redis 待补充）
- 构建工具：Maven（后端）；前端构建工具待确认
- 部署方式：待确认（本地 / 云服务器 / Docker）

## 目录结构
```text
待补充。建议只列：
backend/    Spring Boot 后端
  controller/  service/  mapper/  entity/  config/
frontend/   Vue 前端
  views/  components/  api/  router/
```

## 关键约定
- 分层：待确认（建议 Controller → Service → Mapper）。
- 命名：待确认。
- 异常处理：待确认（是否有 GlobalExceptionHandler）。
- 返回结构：待确认（是否有统一 Result 包装）。
- 日志：待确认。
- 测试：待确认（是否有单元/集成测试）。
- Git 提交规范：待确认。

## 启动与验证
```bash
# 后端
# mvn spring-boot:run

# 前端
# npm install
# npm run dev

# 测试
# mvn test
```

以上命令需按实际项目确认，未确认前标注【未验证】。

## 环境变量
| 名称 | 作用 | 是否必填 | 示例（脱敏） |
|---|---|---|---|
| DB_URL | 数据库连接 | 是 | jdbc:mysql://localhost:3306/blog |
| DB_USER | 数据库用户 | 是 | blog_app |
| DB_PASSWORD | 数据库密码 | 是 | 通过环境变量注入 |
| JWT_SECRET | 若使用 JWT | 待确认 | 不写入仓库 |

## 数据与接口
- 核心表：待确认（至少可能有 article、user、category、comment）。
- 核心接口：文章列表、文章详情、发布/编辑文章、登录。
- 鉴权方式：待确认（Session / JWT）。

## 已知问题
- Redis、Docker 只做过碎片化学习，项目中是否使用待确认。
- 测试覆盖情况待确认。
- 部署与回滚流程待确认。

## 完成定义（DoD）
- [ ] 功能可用
- [ ] 关键路径有测试
- [ ] 无新增安全问题
- [ ] README 与变更记录更新
- [ ] 数据库变更可回滚

## 本次不涉及
- 待确认。每次任务前填写，例如“不做评论审核、不做全文搜索”。

## 可直接使用的精简版

```text
项目：个人博客，Spring Boot + Vue。
技术栈：Java / Spring Boot / Vue / MySQL；版本与是否用 Redis 待确认。
结构：后端 Controller → Service → Mapper；前端 Vue 页面 + API 封装（细节待确认）。
约定：统一返回结构、全局异常处理是否存在待确认；改动前先读现有代码。
限制：不改变现有文章接口契约；不引入新中间件；数据库变更需可回滚。
```

## 如何补全

按优先级补这四项，补完后大多数开发场景卡都能直接用：

1. 后端/前端/数据库的具体版本。
2. 登录鉴权方式与用户表结构。
3. 后端分层、统一返回结构、异常处理方式。
4. 启动、测试、部署命令。
