# Deploy

## 全栈运行

1. 把 `.env.example` 复制为 `.env`，替换所有密码。
2. 在仓库根目录执行：

```powershell
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
```

3. 打开 `http://localhost:8080`。前端 Nginx 会把 `/api/` 转发给后端。

首次创建账号后，把 `.env` 中的 `REGISTRATION_ENABLED` 改为 `false` 并重启 backend，避免公开部署时继续开放注册。

数据卷：

- `mysql_data`：MySQL 数据。
- `redis_data`：Sa-Token 会话与 Redis AOF。
- `capture_data`：受管理资产与抓取临时文件。

停止服务但保留数据：

```powershell
docker compose --env-file deploy/.env -f deploy/docker-compose.yml down
```

不要添加 `-v`，除非明确要删除全部持久化数据。

## 备份与恢复

```powershell
$env:MYSQL_PASSWORD = "从 .env 读取"
.\deploy\backup.ps1
.\deploy\restore.ps1 -BackupDirectory .\backups\20261102-230000
```

备份包含 MySQL dump、资产目录和抓取工作目录。恢复前先停止写入并保留当前数据卷快照。

## 真实 URL 验收

准备每行一个 URL 的文本文件，然后执行：

```powershell
.\scripts\accept-real-urls.ps1 `
  -BaseUrl http://localhost:8080 `
  -Username demo `
  -Password "本地测试密码" `
  -UrlsFile .\urls.txt
```

脚本输出每个 URL 的最终任务状态、耗时、错误和资产数量，并汇总成功率与中位耗时。

`local/` 仅用于开发机启动 MySQL/Redis；`reference/` 只用于 Linkwarden 上游研究，不进入自有项目部署。

