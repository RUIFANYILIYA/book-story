# ebookd

自托管电子书库 + 安卓阅读器。两个部分，一个仓库：

- **`server/`** — 运行在 Docker 里的电子书管理服务（Node + TypeScript + SQLite）。多用户、多格式原文件存储、元数据与封面提取、分类分组标签、批量整理、Web 管理页、面向 App 的下发 API。**不做格式转码**，存什么格式就发什么格式。
- **`android/`** — Kotlin + Jetpack Compose 阅读器，自研分页引擎。登录服务端，在线读与离线读。

## 当前状态

**第一轮（M0–M11，可跑通的核心闭环）已完成并逐项验收；第二轮（M12）尚未开始。**
每个里程碑交付了什么、验收读数是多少、还剩什么，都在 `docs/05-路线图.md`。

已可运行：

```
npm ci
npm run dev -w server        # http://127.0.0.1:8787
npm run verify               # typecheck + 契约一致性 + 单测 + 构建产物冒烟
```

四条验证通道与读数（2026-09-22 下午实测）：

| 通道 | 命令 | 读数 |
| --- | --- | --- |
| 服务端 | `npm run verify` | typecheck 干净 · 契约 62 操作 / 28 schema / 32 错误码 · **270 单测**（21 文件）· dist 产物冒烟 **28/28** |
| 阅读引擎 | `cd android && ./gradlew :engine-core:test` | **68 测试**（纯 JVM，没有 Android SDK 也能跑） |
| App 逻辑层 | `./gradlew :core:testDebugUnitTest` | **69 测试**（其中 3 项 Live 测试需要真实服务器，否则自动跳过） |
| App 产物 | `./gradlew :app:assembleDebug` | 出 debug APK |

**已经能用**：多用户认证与令牌轮换、书籍入库（嗅探 → 哈希去重 → 解析 → 封面）、浏览检索与批量整理、
字节范围下发、进度/书签/增量同步、Web 管理页（`/admin/`）、Docker 镜像（含卷存活验证）、
**保留窗口到期的磁盘回收**（周期 sweeper：删除的书 / 过期上传临时区 / 失效设备行）；
Android 端「登录 → 书架 → 开始阅读（本机没有的书自动下载）」的完整闭环，含离线阅读、服务端生成的
封面渲染与进度回传。

**还没有**：EPUB / PDF / CBZ 等格式的**渲染**（服务端元数据解析已有）、书签、仿真翻页与上下滚动；
服务端的 `cli.ts` 与作业队列。逐项清单见 `docs/05-路线图.md` 的「遗留项台账」。

## 设计前提（改动前先读）

1. **服务端零原生依赖。** 数据库用 Node 24 内置的 `node:sqlite`，口令哈希用 `node:crypto` 的 scrypt，编码探测用内置 ICU `TextDecoder`。镜像里不编译任何 `.node`，因此不依赖构建机的 toolchain，也不依赖 apt 源。CI 的 `image-dependencies` job 每次推送都在验证这个前提。
2. **`/data/db` 绝不能挂在 `/mnt/c`（9p）上。** SQLite 的 WAL 在 9p 上没有可用的建议锁，会损坏数据库。用命名卷。
3. **`shared/api-spec/openapi.yaml` 是线格式的唯真源。** 改了它就跑 `npm run contract:gen` 并提交生成的 `contract.json`；`contract:check` 会挡住规范与已提交清单、以及服务端错误码表之间的漂移。`/api/v1` 内只允许**追加**：新增可选字段、新增端点、新增枚举值；删除或改名必须开 `/api/v2`。
4. **`android/settings.gradle.kts` 按条件包含模块。** `:engine-core`（文档模型、TXT 断章、分页）是纯 Kotlin/JVM，没有 Android SDK 也能编译和跑测试——这是最难逻辑先被验证的通道。
5. **单一共享书库 + 每人私有层。** 电子书库天然是同书多格式，按用户切分物理副本会让磁盘和"批量整理"同时失去意义。因此共享的是书籍/文件/分类/标签/共享分组，私有的是阅读状态、进度、书签、个人封面与个人分组；个人数据查询在仓储层强制 `WHERE user_id = :actor`，路由里不写权限判断。**没有**按书 ACL、用户间分享、分享链接——这不是遗漏，是本轮刻意不做的边界（见 `docs/05-路线图.md`）。

## 仓库结构

```
server/     Fastify 5 服务；src/{routes,db,auth,library,metadata,http}；public/ 是 Web 管理页
shared/     api-spec/openapi.yaml（真源）+ 生成的 contract.json + 跨语言测试样本
android/    engine-core(纯JVM) · engine-android(渲染) · core(网络/存储/同步) · app(Compose)
deploy/     docker-compose.yml、.env.example 与 NAS 部署说明
docs/       01-架构 · 02-API · 03-部署 · 04-Android · 05-路线图（含遗留项台账）
```

## 开发环境

| 需要的东西 | 说明 |
| --- | --- |
| Node ≥ 22.12 | 用到内置 `node:sqlite`；开发机为 24 |
| JDK 21 | Android/Gradle 构建 |
| Android SDK | `platforms;android-37.0` + `build-tools;37.0.0` + `platform-tools`（`compileSdk = 37` 是当前依赖集要求的编译期上限，`targetSdk` 仍是 36）；路径写进 `android/local.properties`（已 gitignore） |
| Docker | 仅在验证容器镜像时需要 |

Gradle 版本目录在 `android/gradle/libs.versions.toml`，是所有版本的唯一来源；AGP/Kotlin/KSP 三者必须成组升级。

## 端口

默认 `8787`（本机 8000 已被占用）。手机在同一局域网联调时用开发机的局域网 IP，例如 `http://192.168.86.4:8787`。
