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

## 本地 demo 演示账号

`demo` 默认是普通本地开发账号，不具备管理员权限或跨用户读取能力。它只用于人工检查登录、收藏、详情、正文、证据、搜索和标签页面，不参与自动化 E2E；Playwright 仍为每个用例创建随机唯一账号。

先启动 MySQL、Redis 和 local profile 后端，然后使用 PowerShell 7 从仓库根目录运行：

```powershell
.\scripts\seed-demo-account.ps1
```

脚本默认连接 `http://127.0.0.1:8080`，创建或复用以下固定资源：

- 账号：`demo` / `demo@example.com`；
- 集合：`工程阅读`、`协议档案`；
- 标签：`Web`、`HTTP`、`安全`、`Spring`、`Java`、`性能`；
- 6 条公开网页抓取记录，覆盖正文与 HTML/PDF/截图证据。

密码没有默认值。脚本会交互式询问；无交互环境需要预先提供 `WEBCOLLECTOR_DEMO_PASSWORD`。脚本不会保存或输出密码及 token。

重复运行只补齐缺失资源，不删除或改写已有账号、集合、标签和链接；已有链接会按原始 URL 复用。任何任务失败都会在结束时列出并以非零状态退出，但不会删除已经成功的数据。

脚本行为测试：

```powershell
node --test .\scripts\tests\seed-demo-account.test.mjs
```

### 提升本地 demo 为运营管理员

仅在需要检查 `/admin` 运营页时运行：

```powershell
.\scripts\promote-demo-admin.ps1
```

脚本只操作 `deploy/local/docker-compose.yml` 对应的本地 MySQL，把 `demo.role` 从 `USER` 提升为 `ADMIN`。重复运行是幂等的，未知账号会失败。数据库凭据由容器环境读取，脚本不会输出密码。角色每次请求都从数据库读取，因此现有登录会话不需要重新登录。

管理员可以查看跨账号聚合指标和失败任务元数据，包括用户名、来源域名、尝试次数和错误码；不能查看其他用户的完整 URL、标题、正文、错误原文或资产内容。管理员重试会创建新任务、清理旧失败任务的部分资产业务记录并写入持久审计。

提升脚本测试：

```powershell
node --test .\scripts\tests\promote-demo-admin.test.mjs
```

## 备份与恢复

```powershell
.\deploy\backup.ps1 `
  -ComposeFile .\deploy\docker-compose.yml `
  -EnvFile .\deploy\.env `
  -ProjectName webcollector-m8-acceptance `
  -OutputRoot .\.superpowers\m8.2\backups

.\deploy\restore.ps1 `
  -ComposeFile .\deploy\docker-compose.yml `
  -EnvFile .\deploy\.env `
  -ProjectName webcollector-m8-acceptance `
  -BackupDirectory .\.superpowers\m8.2\backups\20261108-152706-680
```

所有 Compose 操作都会显式使用 `-p`、`--env-file` 和 `-f`，避免误连其他项目的数据卷。备份包含：

- `mysql.sql`：在 MySQL 容器内通过 `mysqldump --single-transaction` 生成，并通过 `docker compose cp` 复制；
- `assets/`：链接资产文件；
- `capture-work/`：采集工作目录；
- `manifest.json`：每个文件的相对路径、大小和 SHA-256。

恢复脚本会先校验清单与全部文件哈希，再停止 backend、导入 SQL、替换两个数据目录并重启 backend。任一步失败时脚本非零退出；如果 backend 已经停止，会保持停止状态，避免部分恢复继续接收写入。

脚本行为测试：

```powershell
node --test .\deploy\tests\backup-restore.test.mjs
```

独立验收栈在 M8.1/M8.2 中固定使用项目名 `webcollector-m8-acceptance` 和宿主机端口 `18080`。`deploy/.env` 的普通默认端口仍是 `8080`，验收启动时需要显式覆盖：

```powershell
$env:WEB_PORT = "18080"
docker compose -p webcollector-m8-acceptance --env-file .\deploy\.env `
  -f .\deploy\docker-compose.yml up -d --no-build
```

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

