# Frontend

Vue 3 前端，包含登录/注册、收藏列表、保存链接、任务轮询、资产打开/下载和全文搜索。

实现约定：

- Vue 3 + Vite + Pinia + Vue Router + Element Plus。
- 页面范围为登录、链接列表、保存链接、链接详情、标签和搜索。
- 前端由 Codex 生成，但必须解释数据流和组件边界，确保用户能独立维护。

```powershell
npm install
npm run dev
npm run build
```

开发服务器将 `/api` 代理到 `http://localhost:8080`。生产构建由 `Dockerfile` 和 `nginx.conf` 提供。

