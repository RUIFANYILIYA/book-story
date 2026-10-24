# 部署到 NAS

服务端是一个容器，两个命名卷，没有外部依赖（不需要 Postgres/Redis，也不需要构建工具链）。

## 1. 前提

- 目标机器是 **x86_64** 架构，已装 Docker 与 Compose 插件。
- 至少 1 GB 可用内存（首次启动会跑迁移，之后常驻约 80–150 MB）。
- 库文件占用的磁盘空间按你的书库大小算，另外预留约 10% 给封面与临时上传。

## 2. 构建并启动

```bash
git clone <仓库> ebookd && cd ebookd
cp deploy/.env.example deploy/.env

# 生成两个真实密钥，别用示例值
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
# 把结果分别填进 EBOOK_JWT_SECRET 与 EBOOK_MASTER_KEY

docker compose -f deploy/docker-compose.yml up --build -d
docker compose -f deploy/docker-compose.yml logs -f ebookd
```

**首次启动时请务必看日志。** 如果你没有在 `.env` 里设置 `EBOOK_ADMIN_PASSWORD`，服务端会生成一个随机密码并且**只打印这一次**：

```
WARN  bootstrap admin created with a generated password; sign in and change it...
      username=admin password=xxxxxxxxxxxxxxxxxxxxxxxx
```

用它登录 `/admin/`，然后立刻改密码。改完之后建议把那一行从日志轮转里清掉。

### 不构建也能起（只有图形界面的 NAS，或拉不到基础镜像）

`docker-compose.no-build.yml` 是同一份服务定义，只去掉 `build:` 这一块——它要求镜像已经在机器上，
所以配合导出文件用：

```bash
docker save -o ebookd-0.2.0-image.tar.gz ebookd:0.2.0    # 在能构建的那台机器上
docker load -i ebookd-0.2.0-image.tar.gz                  # 在 NAS 上
docker compose -f deploy/docker-compose.no-build.yml up -d
```

只有 Web 界面的 NAS（绿联、群晖这类）走的是同样的两步：先在 Docker 应用里把那个 tar 导入成镜像
（入口各版本叫法不同，一般叫"导入 / 本地镜像"），再把 `docker-compose.no-build.yml` 的内容贴进
"项目 / Compose"，路径指到一个放着 `deploy/.env` 的目录。卷名、端口、环境变量与 `docker-compose.yml`
完全一致，所以哪天改回构建版，数据不用挪。

2026-09-24 在本机用这条路径起过一个空卷实例：容器 healthy，`.env` 里的初始口令能登录，
灌一本 125,555 字节的 UTF-8 TXT（采样边界正好切在一个汉字中间）后 `files[0].charset` 是 `UTF-8`、
简介是正常中文。

### 在 WSL 里构建时

如果你像开发机一样在 WSL 中构建，注意路径要在 WSL 原生文件系统内，否则镜像构建期间的
文件 I/O 会慢 5–10 倍（9p 挂载）。仓库在 `/mnt/c` 下时可以构建，只是慢。

## 3. 卷布局与数据安全

| 卷 | 容器内路径 | 内容 |
| --- | --- | --- |
| `ebookd_db` | `/data/db` | SQLite 数据库，含 WAL |
| `ebookd_library` | `/data/library` | 按内容哈希存放的原文件、封面、上传临时区、回收站 |
| `ebookd_imports` | `/data/library/imports` | 批量导入的落地目录 |

**不要把 `/data/db` 换成指向 Windows 共享或 SMB 的 bind mount。** SQLite 的 WAL 依赖可用的建议锁，
在 9p/SMB 上拿不到锁的后果不是"变慢"，而是**数据库损坏**。原文件是追加写的一次性文件，放共享上只是慢，
但数据库不行。若确实要把书库放在 NAS 的共享目录上，请只把 `/data/library` 换成 bind mount。

### 备份

数据库在运行时不能直接 `cp`（WAL 里可能还有未合并的数据）。两种可靠做法：

```bash
# 1) 停服冷备：最简单，且一致
docker compose -f deploy/docker-compose.yml down
docker run --rm -v ebookd_db:/db -v "$PWD:/out" alpine tar czf /out/ebookd-db.tgz -C /db .
docker compose -f deploy/docker-compose.yml up -d

# 2) 不停服热备原文件（它们是不可变的，随时可拷）
docker run --rm -v ebookd_library:/lib -v "$PWD:/out" alpine tar czf /out/ebookd-library.tgz -C /lib .
```

恢复就是反向操作：先 `down`，把内容解回卷，再 `up`。

## 4. 手机访问

服务端默认监听 `0.0.0.0:8787`，同一局域网内用 NAS 的 IP 即可：

```
http://<NAS 的局域网 IP>:8787
```

在 NAS 上通常还需要放行 8787 端口（群晖的"防火墙"、威联通的"安全设置"）。

> 若在 **WSL 里**跑着调试，Windows 的 WSL2 是 NAT 网络，手机无法直连其中的端口。日常联调建议把服务端
> 直接跑在 Windows 本机（`npm run dev`，它会绑定 `0.0.0.0`），或按第 6 节做端口转发。

## 5. HTTPS：你有动态公网 IP，请务必看这一节

**不要把 8787 直接映射到公网。** 明文 HTTP 传输的是你的账号密码和整本书；端口扫描是常态，
一个暴露在公网、只靠口令保护的入口不是"自用"。

三个可接受的方案，按推荐程度：

1. **只走内网 + 组网**：用 WireGuard / Tailscale 把手机和 NAS 连成一个私有网络，不开放任何公网端口。
   这是最省事也最安全的做法，客户端不需要证书。
2. **反向代理 + 证书**（compose 里注释掉的 Caddy，或群晖/威联通自带的反向代理 + Let's Encrypt）：
   把 `EBOOK_BIND` 设为 `127.0.0.1`，让反代独占 443 并转发到 8787。配合 DDNS 域名即可。
   Caddy 示例（`deploy/Caddyfile`）：

   ```
   ebook.example.com {
       reverse_proxy ebookd:8787
   }
   ```

3. 至少也要：限制来源 IP、开启失败锁定（已内建：5 次失败锁 15 分钟）、并使用长随机密码。

反向代理下记得设 `EBOOK_TRUST_PROXY=true`，否则登录限流会把所有请求算作同一个来源 IP —— 反过来，
**只有在反代可信时才开**，否则 `X-Forwarded-For` 是任何人都能伪造的。

## 6. 从局域网访问 WSL 中的实例（仅调试用）

WSL2 默认 NAT，手机连不上。两种办法，都需要你手动执行，因为都改系统状态：

```powershell
# A) 端口转发（需管理员权限的 PowerShell）
netsh interface portproxy add v4tov4 listenport=8787 listenaddress=0.0.0.0 `
  connectaddress=<WSL 的 IP> connectport=8787
# 查看 WSL IP： wsl -d Ubuntu-24.04 -- hostname -I
# 移除： netsh interface portproxy delete v4tov4 listenport=8787 listenaddress=0.0.0.0
```

```ini
# B) 镜像网络模式（改 C:\Users\<你>\.wslconfig 后执行 wsl --shutdown）
[wsl2]
networkingMode=mirrored
```

另外 Windows 防火墙需要放行该端口（首次 `npm run dev` 监听时系统会弹窗，选"专用网络"即可）。

## 7. 升级

```bash
git pull
docker compose -f deploy/docker-compose.yml up --build -d
```

迁移会在启动时自动执行，只向前，且带校验和：**改动已执行过的迁移文件会导致启动失败**，
这是刻意的——否则代码与库结构会悄悄不一致。要加字段就新增一个迁移文件。

## 8. 排障

| 现象 | 原因与处理 |
| --- | --- |
| 启动即退出，日志里有 `EBOOK_JWT_SECRET and EBOOK_MASTER_KEY are required` | 生产模式缺密钥，见第 2 节 |
| `SQLITE_CANTOPEN` | `/data/db` 的属主不对。镜像已 `chown`，若你 bind mount 了宿主目录需自行确保容器内 uid 可写 |
| 迁移报 `applied migrations were modified after the fact` | 有人改了已执行过的迁移文件；新增一个而不是修改旧的 |
| 手机能打开 `/admin/` 但封面是空白 | 封面需要鉴权头；浏览器端已用带 token 的 fetch 处理。若你自建了页面，注意 `<img src>` 无法携带该头 |
| 上传大文件失败 | 检查 `EBOOK_MAX_UPLOAD_MB`，以及反向代理自身的 body 大小限制 |
| 日志里出现 `bootstrap admin` 但没有密码 | 已经创建过了；密码只在首次创建时打印。用管理页重置，或删库重来（数据会丢） |
