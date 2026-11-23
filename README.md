# Savepoint

周期性世界备份 + 回档（还原）的 Paper 插件，带登录锁定保护。
Periodic world backups and restore-from-backup for Paper servers, with a login-lock safety flow.

- 插件名 / name: **Savepoint**
- 版本 / version: **1.4.1**
- 命令 / command: `/savepoint`（别名 `/sp`），权限 `savepoint.admin`（默认 op）

---

## 环境要求 Requirements

| 项目 | 要求 |
| --- | --- |
| 服务端 Server | Paper，`api-version: 26.2`（见 `resources/plugin.yml`） |
| Java | **Java 25**（本项目以 JDK 25 编译，产物为 Java 25 字节码，运行时需与服务端一致） |
| 依赖 Dependencies | 仅 Paper API，无软依赖 |

> 1.4.0 起插件由旧名 **WorldBackup** 改名而来，首次启动会自动导入 `plugins/WorldBackup/config.yml`（如果存在）。同一 `plugins/` 目录里不要同时放两份本插件的 jar，否则会并行备份并双重锁定登录。

---

## 安装 Install

1. 把 `Savepoint-<version>.jar` 放进服务端的 `plugins/` 目录。
2. 启动服务端；配置生成在 `plugins/Savepoint/config.yml`。
3. （可选）改完配置用 `/savepoint reload` 热重载。

---

## 命令 Commands

| 命令 | 作用 |
| --- | --- |
| `/savepoint` | 查看状态概要（目录、间隔、离线倒计时、保留份数、空服模式） |
| `/savepoint backup [名称] [单位...] [--force]` | 立即备份。默认全部世界 -> `backup-<时间戳>.zip`；给名称 -> `backup-<时间戳>_<名称>.zip`；给单位则只备份指定世界/维度；`--force` 在上次备份未结束时仍排队执行 |
| `/savepoint world list` | 列出可备份单位（世界整体，如 `bskyblock_world`；单维度，如 `bskyblock_world@the_nether`） |
| `/savepoint list` | 列出备份文件（最新优先） |
| `/savepoint restore <latest\|备份名> [单位...] [skip-backup] [--force]` | 回档，见下方流程 |
| `/savepoint cancel` | 取消待确认的回档 |
| `/savepoint unlock` | 手动恢复 `whitelist.json` / `server.properties`（回档锁定后开门） |
| `/savepoint reload` | 重载 `config.yml` |
| `/savepoint help` | 命令说明 |

### 回档流程 Restore flow

`/savepoint restore` 的完整链路：

```
二次确认 → 保护备份(可 skip-backup 跳过) → 锁定登录 → 踢出玩家 → 关服 → 还原文件 → 下次启动自动开门
```

- 回档前默认会先做一次“保护备份”（`backup-<时间戳>_pre-restore.zip`），由配置 `restore-backup-before` 控制；命令里加 `skip-backup` 可单次跳过。
- 文件还原在 **JVM 关服阶段** 完成：把当前目标世界目录改名成 `<世界>.pre-restore-<时间戳>`，解压备份到全新目录，再删除改名后的旧副本。
- Windows 上旧 JVM 进程可能仍占用 region 文件句柄，导致改名副本当场删不掉——这属正常，插件会在 **下次服务器启动时自动清理** 这些 `*.pre-restore-*` 残留，无需手动处理。
- 若解压失败，旧数据会被 **有意保留** 并在目录内写入标记 `SAVEPOINT_OLD_DATA_KEEP`，开机清理会跳过它，日志提示你手动处理，避免误删安全副本。
- 回档会临时把 `online-mode` 置为关闭并写入占位白名单锁定登录，还原完成后自动恢复。

> ⚠️ 只回档 **单个维度**（带 `@维度` 的单位）时 **必须加 `--force`**：维度回档不会回退玩家背包等数据，存在刷物品风险，请确认后再执行。

---

## 配置 config.yml

| 键 Key | 默认 Default | 说明 |
| --- | --- | --- |
| `backup-dir` | `"backups"` | 备份目录。相对路径基于服务器根目录；也可写绝对路径（Windows 反斜杠在 yaml 中需转义为 `\\`） |
| `interval-minutes` | `60` | 有玩家在线时的间隔备份时长（分钟） |
| `last-offline-delay-minutes` | `3` | 最后一名玩家离线后的倒计时（分钟），期间有人重新上线则重置 |
| `keep-count` | `48` | 最多保留的备份份数，超出自动删除最旧 zip |
| `when-empty` | `auto` | 无人在线时行为：`auto` 读 Paper 的 `pause-when-empty-seconds` 自动判定；`always` 一直按间隔备份；`final-only` 末次备份后停止直到有人再上线 |
| `restore-backup-before` | `true` | 回档前是否自动先做保护备份（命令 `skip-backup` 可单次跳过） |

---

## 构建 Build

本项目为单文件插件，无 Maven/Gradle，使用 `node build.cjs`（需 Node.js）：

| 命令 | 作用 |
| --- | --- |
| `node build.cjs` | 编译，产物在 `dist/Savepoint-<version>.jar` |
| `node build.cjs test` | 编译并运行还原逻辑校验 `test/RestoreLogicTest.java` |
| `node build.cjs deploy` | 编译并把 jar 复制到服务端 `plugins/`（需重启加载） |

> `build/`（编译中间产物）和 `dist/`（成品 jar）均可由上述命令重建，已在 `.gitignore` 中忽略，不入库。

---

## 许可 License

本项目采用 [MIT License](LICENSE) 开源。可自由使用、修改、分发，保留版权声明即可。
This project is licensed under the [MIT License](LICENSE).
