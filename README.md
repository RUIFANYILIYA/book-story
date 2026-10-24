# Book's Story × ebookd 交付包（2026-10-24）

个人自用的自建电子书库：服务端（Node 24 + SQLite，Docker 单容器）+ 深度定制的 Android 阅读器。

## 版本

| 部件 | 版本 |
|---|---|
| App | `1.8.8-eb27-edit`（versionCode 25） |
| 服务端 | `0.2.0`（镜像 `ebookd:0.2.0`） |
| 数据库结构 | schemaVersion 10 |
| API 契约 | 65 个操作 / 29 个 schema |

## 包里有什么

```
book-story-20261024/
├── apk/book-story-1.8.8-eb27-edit-release.apk   App 安装包（已签名，可直接覆盖安装）
├── image/ebookd-0.2.0-image.tar                 服务端镜像（docker load / NAS 镜像导入）
├── deploy/                                      部署用 compose
│   ├── docker-compose.0.2.0.yml                  ← 当前在用的这份（含监听文件夹）
│   ├── docker-compose.named.yml                  命名卷模板
│   └── docker-compose.nas.yml                    绑定挂载模板
├── app-source/                                  Android 阅读器源码（Gradle 项目）
├── server-source/                               服务端源码（Node + TypeScript）
├── 升级指南-绿联UGOS-Pro.md                      NAS 部署 / 升级 / 排障
├── 交付说明.md                                   每项改动的原因、实现、验证记录
└── 竞品调研-GitHub同类项目功能报告.md            同类项目功能调研
```

**不含**：书籍数据（`server/data` 已剔除）、`node_modules`、App 与服务的构建产物（`app/build`、`server/dist`）。都是可重新生成的。

## Git 仓库里没有什么

公开仓库通过 `.gitignore` 排除了两类文件，**都在本地保留、不上传**：

| 排除项 | 原因 | 本地位置 |
|---|---|---|
| `apk/`、`image/` | 110MB 大文件不进 git 历史，随 GitHub **Release** 附件分发 | 原样留在文件夹里 |
| 签名密钥 `*.jks`、`keystore.properties`、`local.properties` | 签名密钥泄露 = 任何人可冒充本应用签名 | 原样留在文件夹里 |
| `deploy/.env` | 含 NAS 真实 JWT / MASTER_KEY，重建项目时从这份取回值填回 | 原样留在文件夹里 |

## 部署（NAS）

1. 导入镜像：Docker → 镜像 → 本地导入 → 选 `image/ebookd-0.2.0-image.tar`
2. 用 `deploy/docker-compose.0.2.0.yml` 覆盖项目里的 compose
3. 重新部署

细节、回滚、排障见 `升级指南-绿联UGOS-Pro.md`。

## 自动构建与发布（GitHub Actions）

代码推送到 `main` 会自动跑 CI（类型检查 / 契约检查 / 325 测试 / 冒烟 / 阅读引擎测试）；打 `v*` tag（如 `v0.3.0`）会自动构建 `linux/amd64` 镜像推到 **`ghcr.io/ruifanyiliya/ebookd:<tag>`**，并把离线 tar.gz 附到 GitHub Release。之后 NAS 升级可以不走 tar 导入，把 compose 里 `image:` 换成 ghcr 地址重新部署即可——详见 `升级指南-绿联UGOS-Pro.md` 第 7-4 节。

### 版本约定：一条发布线

App 与服务端**共用同一个版本号和同一个 tag**，一个 Release 同时覆盖两端（例如 `v0.3.0` 同时是 App 1.8.8 之后的下一版，也是服务端 0.2.0 之后的下一版）。镜像 tag 与 Release 号永远一致。

### 发版清单

```bash
git push                                    # 1. 推送代码（CI 自动验证）
git tag v0.3.0 && git push --tags           # 2. 打 tag：自动验证 → 构建镜像推 ghcr → 生成 tar.gz Release
```

3. 如果本版有 App 改动：把 `app-source/app/build/outputs/apk/release/app-release.apk` 拖进同一个 Release
4. NAS 升级：compose 的 `image:` 换成 `ghcr.io/ruifanyiliya/ebookd:v0.3.0` → 重新部署

## 构建

**服务端**（需要 Node 24）：

```bash
cd server-source
npm ci
npm run verify        # 类型检查 + 契约检查 + 325 测试 + 28 项冒烟
docker build -f server/Dockerfile -t ebookd:0.2.0 --platform linux/amd64 .
```

**App**（需要 JDK 21 + Android SDK，`local.properties` 里写 `sdk.dir`）：

```bash
cd app-source
# 签名密钥在 keystore.properties（git 忽略，本地已配好；模板见 keystore.properties.example）
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
# 证书 SHA-256 ae54ab4a…310b
```

## 最近几项改动（详见交付说明）

- 书架同步按内容哈希匹配（本地导入的书也能归到服务端书架）
- 服务器地址改成显眼的输入框
- 改书名/作者后封面自动重绘
- 管理台在线阅读 EPUB（按 spine 分章）
- 监听文件夹原地入库（不复制、不移动、不删除，NAS 专用）
