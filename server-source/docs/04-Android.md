# 04 · Android

Kotlin + Compose，自研分页引擎。登录服务端，在线读与离线读。**目前只读 TXT**——EPUB/PDF/CBZ 的渲染是二轮（服务端元数据解析已有）。

## 1. 四模块与"无 SDK 也能跑引擎测试"的通道

```
engine-core     纯 Kotlin/JVM：文档模型、编码探测、归一化、TXT 断章、TOC 编解码、分页器
engine-android  渲染：AndroidGlyphMeasurer(Paint)、ReaderView(Canvas)、PageMotion
core            网络(Retrofit/OkHttp) · Room · DataStore · 内容寻址存储 · 下载 · 同步 · 离线队列
app             Compose 屏幕 + Hilt + 导航 + MainActivity
```

`settings.gradle.kts` **按 SDK 是否可用来条件包含** `:core`/`:engine-android`/`:app`，`:engine-core` 永远包含，
所以 `./gradlew :engine-core:test` 在只有 JDK 的机器上就是绿的——最难的分页与断章逻辑先被验证。
CI 有一个专门的 `engine` job 跑这条通道。

版本目录在 `android/gradle/libs.versions.toml`，是所有版本的唯一来源；AGP/Kotlin/KSP 三者必须成组升级。
AGP 9 使用**内建 Kotlin**（不要再 apply `org.jetbrains.kotlin.android`）。
`compileSdk = 37`（被 OkHttp 5.5 / Compose BOM / 一批 AndroidX 的 AAR 元数据要求顶上去的，编译期上限），
`minSdk = 26`、`targetSdk = 36`。

## 2. engine-core：TXT 流水线

```
字节 → 编码探测 → 归一化 → 断章 → 文档构建 → 分页
```

1. **编码探测**（`CharsetDetector`）：BOM → 严格 UTF-8 试解 → juniversalchardet（置信 <60 时）→
   候选集 `[UTF-8, GB18030, Big5, Shift_JIS, EUC_KR, windows-1252]` 用 `CharsetDecoder(REPORT)` 试解取错最少者。
   结果持久化，永不重探。（"一键改码"的界面**并没有**，这句以前是写上去的期望；见 `05-路线图.md` 的 A8。）
   **严格试解失败只有在样本没被截断时才算证据**：样本取前 64 KiB，切在一个多字节字符中间时合法 UTF-8 也会抛，
   而候选打分那边 GB18030 几乎总能"解通"——于是整本变乱码。失败时先从尾部回退最多 3 字节再判。
   服务端 `metadata/txt.ts` 是同一处逻辑的另写一份，两边一起改的。
2. **归一化**（`TextNormalizer`）：CRLF→LF、去 `U+FEFF`、`\t`→4 空格、`U+3000`→缩进标记、
   压缩连续空行、**剥离页眉页脚/页码**（同一形态出现 ≥8 次且 ≤40 字符）、段落重排合并。
   必须幂等——这是单条改进 TXT 观感性价比最高的一步。
3. **断章**（`TxtChapterSplitter`，620 行）：`SplitMode` + `MarkSource`；Pass0 识别目录区并从正文排除 →
   Pass1 标题正则族（`第N章`/章回体/`Chapter N`/`序章|楔子|番外`…）→ Pass2 加权打分（前置空行、缩进、
   与目录提示的模糊匹配）→ Pass3 全局校验杀假阳性（编号族要求单调递增且 ≥70% 连续 +1，
   防"他想起第三章说过的话"被当章节）→ Pass4 无章文本按段落边界定长切分。
4. **TOC 编解码**（`TocCodec`）：带版本与长度尾标；文件被改动/截断即判定失效并重新断章。
5. **分页**（`Paginator`，557 行）：`BreakIterator` 取候选断点 + **中文禁则**标点过滤 → 前缀和宽度 →
   两端对齐（先分摊 ASCII 空格再摊中文码点间隙，上限 0.33em）→ 孤寡行 ≥2、标题需与后续行同页 →
   熔断器 `maxIterations` 防测量 bug 死循环 → 锚点二分查找。

读数是硬的：**10 MB 小说断章 31.8 ms**（预算 50 ms，取热身后五次最好值）。

跨语言黄金用例在 `shared/fixtures/txt-chapters/`：同一批样本（章回体 / 网文 / 英文 / 带页码目录 /
GB18030 / UTF-16LE / 页脚污染 / 假阳性诱饵）在服务端 vitest 与 `:engine-core:test` 两边跑，要求结论一致。

## 3. engine-android：渲染

- `AndroidGlyphMeasurer`：Paint 测量 + 度量缓存。
- `ReaderView`（459 行）：`canvas.drawText` 逐行绘制（不是 Compose `Text`）；页眉页脚；暖色叠加层；
  触摸：**九区点击**（左右三分之一翻页、上中目录、下中设置、中心唤出工具栏）、滑动 >25%、
  点击翻页 180 ms 动画。
- `PageMotion`：无动画 / 滑动 / 覆盖三种。**仿真翻页（mesh 卷曲）刻意留给二轮**，接口先留好。
- `LineKind.IMAGE` **尚未绘制**（图片块随二轮来）。

## 4. core：网络、存储、同步

### 网络
- `EbookdApi`：`meta`、`login`、`refresh`、`logout`、`me`、`books`(游标)、`book(id)`、
  `sync/push`、`sync/delta`、`books/batch/{status,tag,delete}`、`tags`、`POST/DELETE books/{id}/cover`；
  文件下载走 `fileDownloadUrl()`（Range 请求由下载器自己发）。
- 令牌：access 只在内存；refresh 用 **Android Keystore 的 AES-256-GCM** 包裹后写 `noBackupFilesDir`
  （不用已废弃的 `EncryptedSharedPreferences`）。
- `TokenRefresher` 单飞（并发的多个 401 只轮换一次，且校验令牌家族，避免把新旧 refresh 用混）；
  `AuthInterceptor` 401 后重放一次；`BaseUrlInterceptor` 按 DataStore 里的地址改写 host。
- `ServerAddress`：规范化地址，**拒绝 `localhost/127.0.0.1`**（真机上那是手机自己），自动折掉尾部 `/api`。
- `ServerProbe`：把 DNS 失败 / 连接被拒 / 超时 / TLS 失败 / 200 但不是 ebookd **分类报因**，
  而不是笼统地"连不上"。

### 数据
- `LibraryRepository` + `LibraryRemoteMediator` + `LibraryQuery`：Paging 3 远端中介者 + Room 缓存。
- `ReaderRepository.open/savePosition`：**只支持 TXT**，其他格式抛 `UnreadableBook`（文案直说）。
- `OfflineRepository` → `DownloadWorker`（HiltWorker，WorkManager 唯一任务名，KEEP 策略防重复入队）。
- `SyncRepository.syncNow`：**先拉后推**（push 带着服务端真实持有的 revision），出站队列 `sync_outbox`，
  位置合并走 `ProgressMerge`（只信 percent + rev 单调 LWW）。
- `BatchRepository`：批量状态/打标签/删除；离线时**只有状态**进 `shelf_edit` 队列（拒绝是终局、
  书已不存在是终局，其余留在队列里等下一次同步），标签与删除离线时明确拒绝——那需要一个不存在的合并规则。
- `CoverRepository` + `CoverCrop`：SAF 选图 → 2:3 居中裁剪 → 本地生效 + `POST /books/:id/cover` 推送。
- `SettingsStore`：DataStore 存 baseUrl、用户名、`ReaderSettings`（字号/行距/边距/调色板/色温/亮度/翻页/音量键）。

### 存储与下载
- `ObjectStore`：`filesDir/objects/<sha[0..2]>/<sha>.bin`，**内容寻址**（同书重复零额外占用，服务端换文件天然失效）。
- `RangeDownloader`：HEAD 探测（`Content-Length`/`ETag`/`X-Content-Sha256`）→ 4 MiB 分段 `If-Range` 请求 →
  收到 200 而不是 206 时归零重来 → 进程被杀后按临时文件长度续传 → 全部完成后校验 sha256 才提升为正式文件，
  校验失败即隔离。
- Room v5 七张表：`books`、`library_remote_keys`、`download_task`、`reading_position`、`sync_outbox`、
  `sync_state`、`shelf_edit`。**书签、章节、页面缓存三张表还没建**。
  v4→v5 是这个 App 的第一个真实迁移（加表，不动别的东西）。

## 5. app：屏幕与阅读器

`ServerSetup → Login → Shelf → {Library, BookDetail, Reader, Downloads, Account}`。

阅读器已有：章节翻页（九区点击 + 音量键 + 跨章滚动）、目录抽屉、设置面板（字号 12–44 sp、行距、边距、
4 种调色板、色温、亮度、翻页方式、字体导入、音量键开关）、恢复上次阅读位置、进度回传。
M10 的验收读数：**冷开 20 MB TXT 中位 82 ms**（目标 <150 ms；同一实例优化前是 208–227 ms）。

**封面**：服务端给没有图的书（TXT、PDF）存一张生成的 SVG 占位封面，所以 App 侧的 Coil 注册了
`SvgDecoder`（`coil-svg`，它带进 `com.caverock:androidsvg`）。解码失败时 `BookCover` 退回本机那块
按书 id 取色的标题色块——以前它只在 `coverUrl` 为空时才画，于是"取到字节但解不出来"就是一片空白。

**开始阅读**：书籍页只剩一个动作。本机没有副本时由阅读器自己入队下载、把转圈标成"正在下载…"、
完成后重开（`ReaderViewModel.downloadThenOpen`，一次请求只自动下载一次）。为此 `UnreadableBook`
带上了可判别的 `reason`（`NOT_ON_DEVICE` / `FORMAT_UNSUPPORTED`）——按错误文案猜分支是不能要的。

## 6. 局域网明文 HTTP

Android 从 API 28 起默认禁明文，而 `network_security_config.xml` 只能白名单**固定域名**——用户自填的
局域网 IP 做不到，所以全局允许明文（`app/src/main/res/xml/network_security_config.xml` 里写了原因）。
配套的处理：登录页与服务器地址页常驻**「未加密连接」**角标；https 分支代码是写全的，切换只是改 URL；
debug 构建额外信任用户安装的 CA（这样自签证书能在设备上测，同时不削弱 release）。

> **缺口**：计划里的**自签证书 SPKI pin**（展示证书主体 + SPKI SHA-256，"信任此主机"把 pin 存进本机）
> 尚未实现。见 `05-路线图.md` 遗留项台账。

## 7. 测试现状

| 模块 | 测试 | 说明 |
| --- | --- | --- |
| `engine-core` | **67** | 纯 JVM，含黄金用例与 10 MB 性能测试；无 SDK 也能跑 |
| `core` | **69** | 其中 3 项是 **Live 测试**（`LiveDownloaderTest`、`LiveServerContractTest`），需要真实服务器与 `-Debookd.e2e.url/.user/.password`，否则自动跳过 |
| `engine-android` | **0** | 渲染只能靠模拟器人工验证 |
| `app` | **0** | 无 `androidTest` 源集 |

真机验证靠一套 adb 脚本驱动（模拟器 `ebookd_api36`，API 36 x86_64）；那套脚本目前**不在仓库里**。
**所有验证都在模拟器上，没有物理设备**——涉及真实触摸手感、相机/HEIC 图片、Keystore 语义、
后台 worker 调度、双设备冲突的项目都还带着 "NOT verified" 标签。
