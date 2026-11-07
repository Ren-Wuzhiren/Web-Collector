# 本地开发依赖

该 Compose 只提供自有项目需要的 MySQL 8.4 和 Redis 7.4，不包含上游 Linkwarden 服务。

```powershell
docker compose -f deploy/local/docker-compose.yml up -d
docker compose -f deploy/local/docker-compose.yml ps
```

默认端口：

- MySQL：`localhost:13307`
- Redis：`localhost:6380`
- 本地资产文件：`backend/data/assets`，不随启动目录变化

从仓库根目录启动本地后端：

```powershell
.\scripts\run-backend-local.ps1
```

该脚本会启动或复用下方依赖，读取 `webcollector-redis` 容器的实际密码，并且只在当前启动进程内设置 `REDIS_PASSWORD` 和 `SPRING_DATA_REDIS_PASSWORD`。用户级环境变量不会被修改。使用 `-CheckOnly` 可以只检查依赖、密码同步和端口占用：

```powershell
.\scripts\run-backend-local.ps1 -CheckOnly
```

未创建 `.env` 时使用仅供本地开发的默认密码；正式部署必须通过环境变量覆盖。停止服务但保留数据卷：

```powershell
docker compose -f deploy/local/docker-compose.yml down
```

不要添加 `-v`，否则会删除数据库和 Redis 数据卷。

