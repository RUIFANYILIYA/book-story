# 02 · API

`shared/api-spec/openapi.yaml` 是线格式的**唯一真源**；这份文档解释约定与现状，不重复 schema 细节。

## 1. 全局约定

| 项 | 约定 |
| --- | --- |
| 基址 | 默认 `http://<host>:8787`；版本前缀 `/api/v1`，`/health`、`/ready`、`/meta` 不在前缀内 |
| 认证 | `Authorization: Bearer <access>`；access 默认 15 分钟，refresh 默认闲置 30 天 / 绝对 90 天 |
| 刷新 | **每次刷新都轮换**；已轮换的 refresh 令牌被再次使用 ⇒ 整条 `family_id` 立刻撤销（盗用响应） |
| 成功响应 | **裸资源**。没有 `{ data }` 外壳；列表是 `{items, nextCursor, hasMore, totalApprox, facets?}` |
| 失败响应 | 一律 `{"error":{"code","message","details","requestId"}}`；`code` 是 `errors.ts` 里的**封闭枚举** |
| 未知字段 | 客户端必须忽略未知 JSON 键，并把未知枚举值映射到 fallback 而不是失败（这样才允许只追加演进） |
| 分页 | 游标是 **HMAC 签名的排序键 + id**（id 是时间有序 ULID）；排序键走白名单，客户端无法伪造游标越权翻页 |
| 校验 | zod 在边界校验；`400` + `validation_failed` 带字段路径 |
| 限流 | `@fastify/rate-limit`；登录失败 5 次 / 15 分钟锁定（`login_attempts`） |
| 能力位 | `GET /auth/me` 返回 capabilities；deny-by-default，路由声明所需能力（见下） |

11 个能力：`library:read`、`library:edit`、`user:manage`、`file:upload`、`file:delete`、`book:delete`、
`batch:delete`、`collections:global`、`settings:write`、`audit:read`、`storage:manage`。
`admin` 全有；`user` 只有 `library:read` + `file:upload`。个人分组归创建者，共享分组需要 `collections:global`。

## 2. 端点（按域，标注所需能力）

### 发现 / 运维

| 方法 | 路径 | 能力 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/meta` | 无 | 免认证：`apiVersion`、`serverVersion`、`protocol`、`serverTime`、`features`、`formats`、`limits`、`minAppVersionCode` |
| GET | `/health` | 无 | 存活探针，**不碰库** |
| GET | `/ready` | 无 | 就绪探针；schema 未就位时 503 |
| GET | `/admin` | 无 | Web 管理页（同源静态页） |
| GET | `/api/v1/stats` | `library:read` | 计数、按格式的体积、任务、用户 |
| GET | `/api/v1/audit-logs` | `audit:read` | 审计日志。**端点是完整的，但全仓没有一处写 `operation_logs`，所以它恒返回空数组**（见 `05-路线图.md` S11） |

### 认证

| 方法 | 路径 | 能力 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/login` | 无 | 带 `device{label,platform}`，返回 access/refresh 三元组 |
| POST | `/api/v1/auth/refresh` | 无 | 轮换式刷新 |
| POST | `/api/v1/auth/logout` | 无 | 撤销当前设备族 |
| GET | `/api/v1/auth/me` | 令牌 | 自身资料 + capabilities |
| PATCH | `/api/v1/auth/me` | 令牌 | 自身偏好（`prefs_json`） |
| POST | `/api/v1/auth/me/password` | 令牌 | 改密；首登 `must_change_pw` 时强制 |
| GET | `/api/v1/auth/devices` | 令牌 | 已登录设备列表 |
| DELETE | `/api/v1/auth/devices/:id` | 令牌 | 撤销某设备 |

### 用户管理（`user:manage`）

`GET /users`、`POST /users`、`GET /users/:id`、`PATCH /users/:id`、`DELETE /users/:id`（**拒删最后一个 admin**）、
`POST /users/:id/password`、`POST /users/:id/tokens/revoke`。

### 入库

| 方法 | 路径 | 能力 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/uploads` | `file:upload` | multipart 同步入库；文本字段先于文件字段；上限 `EBOOK_MAX_UPLOAD_MB` |
| GET | `/api/v1/uploads/:id` | `file:upload` | 单次上传的结果 |
| POST | `/api/v1/import/dir` | `library:edit` | 服务器目录批量导入，支持 dry-run 预览 |

### 浏览与书籍

| 方法 | 路径 | 能力 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/books` | `library:read` | 筛选 + 排序 + 签名游标；每条带 `myProgress`（一次请求喂满 App 书架） |
| GET | `/api/v1/books/facets` | `library:read` | 分面计数 —— ⚠️ **已实现但未进契约**，见 §5 |
| GET | `/api/v1/books/:id` | `library:read` | 详情 + 我的状态 + 文件列表（文件 id 即下载路径）。详情含 `authors`，与列表同源同序 |
| PATCH | `/api/v1/books/:id` | `library:edit` | `title`/`description`/`publisher`/`publishedYear`/`language`/`isbn`/`categoryId`/`seriesName`/`seriesIndex`/`tags`/`authors`/`clearNeedsReview`。`authors` 只替换 `author` 这一种 role，且缺省≠空数组：不发就不动，发 `[]` 是清空 |

### 分类法

| 方法 | 路径 | 能力 |
| --- | --- | --- |
| GET/POST/PATCH/DELETE | `/api/v1/categories[/:id]` | 读 `library:read`；写 `library:edit` |
| GET / POST / DELETE | `/api/v1/tags[/:id]` | 读 `library:read`；写 `library:edit` |
| POST | `/api/v1/tags/merge` | `library:edit` |
| GET / POST | `/api/v1/collections` | 读 `library:read`；建个人分组只需令牌，建共享分组需 `collections:global` |
| PATCH / DELETE | `/api/v1/collections/:id` | 令牌 + 归属检查（`requireEditableCollection`） |
| GET / PUT / DELETE | `/api/v1/collections/:id/books` | 同上 |
| PATCH | `/api/v1/collections/:id/order` | 同上（手工排序） |

**书架只有两层。** `POST /categories` 的 `parentId` 指向一个已经有父节点的分类会被拒绝
（`validation_failed`，422）；`PATCH /categories/:id` 把带子书架的节点挂到别人下面同样被拒绝。
App 只画书架 + 子书架这一级，第三层在任何客户端上都不可见，所以在写入时就挡住。

**`GET /books` 的摘要带 `categoryId`**：一本书在服务端只属一个分类（`books.category_id`），
App 的「同步服务端书架」就是靠这个字段把本地书归位。字段可为 `null`（未归类），老客户端忽略它即可。

### 批量整理（`{bookIds[], dryRun}`，单事务，逐条返回 `{id, ok, error}`）

| 路径 | 能力 |
| --- | --- |
| `/api/v1/books/batch/tag`、`/collection`、`/category`、`/update` | `library:edit` |
| `/api/v1/books/batch/status` | `library:read`（书架状态是**个人层**，标记只需读权限） |
| `/api/v1/books/batch/delete` | `batch:delete` |

### 文件与封面

| 方法 | 路径 | 能力 | 说明 |
| --- | --- | --- | --- |
| GET / HEAD | `/api/v1/files/:fileId/download` | `library:read` | 附件式下发，支持 range |
| GET / HEAD | `/api/v1/files/:fileId/stream` | `library:read` | 内联式（阅读器取正文用） |
| GET | `/api/v1/covers/:bookId` | 令牌 | 个人封面覆写优先；ETag 由 sha 派生，命中回 304 |
| POST | `/api/v1/books/:bookId/cover` | 令牌 | 个人自定义封面；12 MiB 上限；只认 magic bytes |
| DELETE | `/api/v1/books/:bookId/cover` | 令牌 | 清除个人覆写 |

### 进度与同步（全部仅需令牌，数据是个人层）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/api/v1/progress/:bookId` | 写位置（`rev` 单调 + LWW；跨文件只信 percent） |
| GET | `/api/v1/progress` | 列出我的位置 |
| GET / POST | `/api/v1/bookmarks` | 书签列表 / 新建 |
| DELETE | `/api/v1/bookmarks/:id` | 删除（墓碑） |
| GET | `/api/v1/sync/delta?cursor` | 拉增量（元数据 + 个人状态） |
| POST | `/api/v1/sync/push` | 推个人层变更 |
| GET | `/api/v1/sync/offline-manifest` | "登录一次 → 整架可离线"；书目硬上限 5000 |

## 3. 文件下发契约

响应头与规则（`http/range-stream.ts`，`test/files-range.test.ts` 22 项覆盖）：

```
Accept-Ranges: bytes | Content-Length | ETag: "<sha256[0:32]>"（强 ETag，内容派生）
Content-Type | Content-Disposition: attachment; filename*=UTF-8''%E4%B8%89%E4%BD%93.epub
Cache-Control: private, max-age=31536000, immutable | X-Content-Sha256 | X-File-Id
206 才有：Content-Range: bytes 0-4194303/838860800
```

- `If-Range` 必须匹配强 ETag，**不匹配回 200 全量**——否则 App 续传出来的文件是静默损坏的。
- 越界 ⇒ `416` + `Content-Range: bytes */total`；只支持单区间。
- 该路由**永不 `Content-Encoding`**（gzip 会毁掉 range）。
- **`fileId` 是缓存键且行只追加**：换文件（`mode=replace`）= 插入新行 + 把旧行的 `is_primary` 置 0，
  旧行仍 `status='ready'`、按 `fileId` 仍可下载，所以别的设备/半截下载不会被污染。
  唯一的例外写是保留任务把字节已被回收的行改成 `quarantined`（`01-架构.md` §1.9）。

## 4. 契约工作流

```
shared/api-spec/openapi.yaml   ← 手写的唯一真源
        │  npm run contract:gen
        ▼
shared/api-spec/contract.json  ← 生成的规范化清单，提交进仓库（让破坏性改动变成 review diff）
        │  npm run contract:check（CI 每个推送都跑）
        ▼
   门禁三向比对：规范 ↔ 已提交清单 ↔ 服务端错误码表
```

`/api/v1` 内**只允许追加**：新增可选字段、新增端点、新增枚举值。删除或改名必须开 `/api/v2` 并行提供。
`meta.features` 是能力开关：App 靠"缺 flag 就隐藏 UI"而不是"探端点再处理 404"。

## 5. 覆盖情况与已知漂移

- **已实现且已入契约**：契约里的 62 个操作全部有对应处理程序（`/api/v1` 60 个 + `/health`、`/ready`）。
- **已实现、未入契约**：三个处理程序不在 `openapi.yaml` 里——`GET /admin`（静态管理页）、
  `GET /meta`（`/api/v1/meta` 的根路径别名）与 **`GET /api/v1/books/facets`**（前两个是页面与别名，
  无所谓；第三个是真漂移）。`contract:check` 只校验"规范 → 清单 → 错误码"，抓不到服务端**多出来**的端点，
  所以这个漂移不会被 CI 挡住。要么把它补进 `openapi.yaml` 并重新生成清单，要么删掉这个端点。
- **计划里有、尚未实现**：`PUT/DELETE /books/:id/overrides`（个人元数据覆写）、`POST /books/:id/reparse`、
  `DELETE /books/:id`（单本，目前只有批量删）、`GET /shelf`、`/series`、`/contributors`、
  `GET /jobs` + `/jobs/:id/retry`、`POST /progress/:bookId/finished`、`PATCH /bookmarks/:id`、
  `GET /users/:id/storage`、`POST /auth/devices/:id/token`（离线设备长效令牌）、
  `POST /files/:id/integrity-failure`、`GET /version`；批量端点缺 `contributors`/`series`/`reparse`/`merge`。
  完整台账见 `05-路线图.md`。
