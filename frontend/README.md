# Frontend

Vue 3 前端，包含登录/注册、收藏列表、保存链接、任务轮询、资产打开/下载、全文搜索和管理员运营页。

实现约定：

- Vue 3 + Vite + Pinia + Vue Router + Element Plus。
- 页面范围为登录、链接列表、保存链接、链接详情、标签、搜索和仅 ADMIN 可见的运营管理。
- 前端由 Codex 生成，但必须解释数据流和组件边界，确保用户能独立维护。

```powershell
npm ci
npm run dev
npm run build
```

开发服务器将 `/api` 代理到 `http://localhost:8080`。生产构建由 `Dockerfile` 和 `nginx.conf` 提供。

## 浏览器与视觉检查

先启动真实 MySQL 8.4 与 Redis 7.4：

```powershell
docker compose -f deploy/local/docker-compose.yml up -d
```

安装依赖和 Playwright Chromium：

```powershell
Push-Location frontend
npm ci
npm run test:e2e:install
Pop-Location
```

运行完整浏览器闭环：

```powershell
Push-Location frontend
npm run test:e2e
Pop-Location
```

`npm run test:e2e` 先构建生产资源，再由 Playwright 自动启动 Spring Boot 和 Vite Preview。当前 18 项测试覆盖生产 Preview、真实双账号越权、非法 id、详情/正文/证据/标签/任务失败、401、`example.com` 保存与抓取、正文、三种证据、搜索、重新阅读和管理员运营页。只有 MySQL `13307`、Redis `6380` 和外部网页访问可用时才能通过。

管理员页面另有 7 项 Playwright 覆盖：普通用户不可见且不能直达、管理员概览与隐私字段、重试确认、游标加载更多、接口失败恢复、空状态和 1440/768/390 三档宽度。管理员身份由本地角色提升脚本准备，不使用固定管理员密码。

需要单独运行时：

```powershell
Push-Location frontend
npm run test:e2e:failure
npm run test:e2e:capture
Pop-Location
```

运行真实登录态的五页三档视觉检查：

```powershell
Push-Location frontend
npm run check:visual
Pop-Location
```

视觉检查会自动启动后端和 Preview，创建一个唯一视觉账号，保存真实网页并等待抓取成功，再用临时同源 bootstrap 注入 token。它检查登录、收藏、详情、搜索、标签五页在 1440×900、768×1024、390×844 下的布局，并把报告与截图写到项目根目录 `.taste-skill/m7/`。临时 bootstrap 会在结束时删除。

`npm run check:m7` 依次运行完整 E2E 和视觉检查。依赖服务、后端、Worker 或外网不可用时，只能记录“未运行验证”，不能写成通过。

可选环境变量：

- `E2E_BASE_URL`：自定义前端地址；设置后 Playwright 不自动启动后端和 Vite Preview。
- `E2E_API_URL`：后端地址，默认 `http://127.0.0.1:8080`。
- `E2E_REDIS_PASSWORD`：本地 Redis 密码；默认使用 `deploy/local` 的开发密码。
- `E2E_BROWSER_CHANNEL=chrome`：使用本机 Chrome，而不是 Playwright 下载的 Chromium。
- `E2E_VIDEO=off`：关闭失败录像；默认保留 trace、截图和视频。
- `TASTE_SKILL_ROOT`：`design-taste-frontend` 技能目录，默认使用当前用户的 Codex skills 目录。
- `VISUAL_BASE_URL`：视觉检查使用的 Preview 地址，默认 `http://127.0.0.1:4173`。

