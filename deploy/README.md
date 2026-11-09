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

限流默认策略：

| 接口 | 默认额度 | subject |
|---|---|---|
| `POST /api/v1/auth/login` | 10 次 / 60 秒 | 可信客户端 IP |
| `POST /api/v1/auth/register` | 5 次 / 600 秒 | 可信客户端 IP |
| `POST /api/v1/links` | 30 次 / 60 秒 | 登录用户 ID |

对应 `RATE_LIMIT_*_ENABLED`、`RATE_LIMIT_*_LIMIT` 和
`RATE_LIMIT_*_WINDOW_SECONDS` 均可通过 `.env` 覆盖。Nginx 与 backend
通过 Compose 私网通信，`RATE_LIMIT_TRUSTED_PROXY_CIDRS` 默认只信任
Docker 私网；不要改成 `0.0.0.0/0`，否则客户端可以伪造转发地址。

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

### 创建并保留正式管理员

正式实例先保持 `REGISTRATION_ENABLED=true`，在网页注册专用账号（默认约定为 `admin`）并设置高强度密码。

随后从仓库根目录执行：

```powershell
.\scripts\promote-admin.ps1 `
  -Username admin `
  -ComposeFile .\deploy\docker-compose.yml `
  -EnvFile .\deploy\.env `
  -ProjectName webcollector
```

脚本只把指定 Compose 项目中的 `USER` 提升为 `ADMIN`。重复运行是幂等的，未知账号、异常角色或错误项目会失败。数据库凭据由容器环境读取，脚本不会读取或输出账号密码；密码只以 BCrypt 哈希保存在 MySQL。

确认 `/api/v1/auth/me` 返回 `role=ADMIN` 且 `/admin` 可访问后，把 `deploy/.env` 中的 `REGISTRATION_ENABLED` 改为 `false` 并重建 backend：

```powershell
docker compose -p webcollector --env-file .\deploy\.env `
  -f .\deploy\docker-compose.yml up -d backend
```

管理员账号与角色保存在 `webcollector_mysql_data` 数据卷，现有备份的 `mysql.sql` 也包含 `app_user`。停止服务时只执行不带 `-v` 的 `docker compose down`；执行 `down -v` 会删除管理员、链接和资产。

密码遗失时，临时重新开启注册，创建新的受控账号、提升为 ADMIN 并再次关闭注册。不要在长期运行的公开实例上保持注册开放。

本地开发仍可使用兼容入口提升指定账号：

```powershell
.\scripts\promote-demo-admin.ps1 -Username demo
```

该入口固定操作 `deploy/local/docker-compose.yml`，不会误连正式实例。

管理员可以查看跨账号聚合指标和失败任务元数据，包括用户名、来源域名、尝试次数和错误码；不能查看其他用户的完整 URL、标题、正文、错误原文或资产内容。管理员重试会创建新任务、清理旧失败任务的部分资产业务记录并写入持久审计。

管理员脚本测试：

```powershell
node --test .\scripts\tests\promote-admin.test.mjs
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

使用版本化数据集执行；脚本会创建不带凭据输出的一次性账号：

```powershell
pwsh -NoProfile -NonInteractive -File .\scripts\accept-real-urls.ps1 `
  -BaseUrl http://127.0.0.1:18080 `
  -DatasetFile .\scripts\acceptance\m9-real-urls.json `
  -OutputFile .\data\acceptance\m9\results.json `
  -JobTimeoutSeconds 300 `
  -PollIntervalSeconds 3
```

脚本顺序提交 30 项，验证 `SUCCEEDED` 任务至少有一个资产可回读，输出逐项 JSON、成功率、中位耗时、三格式完整率和负向预期匹配率。JSON 位于被 `.gitignore` 忽略的 `data/` 下，不记录密码或 token。

只补跑负向控制时，仍会完整校验 30 条数据集，但只提交选中的案例：

```powershell
pwsh -NoProfile -NonInteractive -File .\scripts\accept-real-urls.ps1 `
  -BaseUrl http://127.0.0.1:18080 `
  -DatasetFile .\scripts\acceptance\m9-real-urls.json `
  -OutputFile .\data\acceptance\m9\negative-controls.json `
  -JobTimeoutSeconds 300 `
  -PollIntervalSeconds 3 `
  -CaseId failure-timeout,failure-redirect,failure-size,failure-ssrf
```

该模式报告 `run.scope=SELECTED`，不能单独替代完整 30 项正向成绩；最终验收单需明确列出两轮证据来源。

`local/` 仅用于开发机启动 MySQL/Redis；`reference/` 只用于 Linkwarden 上游研究，不进入自有项目部署。

