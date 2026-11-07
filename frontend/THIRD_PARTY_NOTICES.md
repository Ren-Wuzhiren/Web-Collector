# 第三方依赖说明

本文件记录前端直接或间接引入的第三方运行时依赖及其许可证。

| 包名 | 版本 | 许可证 | 用途 | 引入方式 |
|---|---|---|---|---|
| motion-v | 2.11.1 | MIT（Copyright (c) 2024 Rick Huang） | Vue 版动效库，用于 `ArchiveCounter` 的数字滚动 | 直接依赖 |
| framer-motion | 14.9.1 | MIT（Copyright (c) 2018 Framer B.V.） | motion-v 依赖的动效内核，提供 `animate` | motion-v 间接依赖 |
| motion-dom | 14.9.1 | MIT（Copyright (c) 2024 Motion B.V.） | framer-motion 依赖的 DOM 动效实现 | 间接依赖 |
| motion-utils | 14.7.0 | MIT（Copyright (c) 2024 Motion B.V.） | 动效工具函数 | 间接依赖 |

## 说明

- 版本以 `frontend/package-lock.json` 为准；升级时同步更新本表。
- 许可证文本以各包 `node_modules/<包名>/LICENSE*` 为准。
- 只使用了动效 API，没有复制源码到仓库。
