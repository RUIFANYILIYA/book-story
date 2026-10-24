# GitHub 同类项目功能调研报告

**日期**：2026-10-24
**对照对象**：本项目（ebookd 服务端 + Book's Story 阅读器 fork）
**调研范围**：自建电子书库/阅读服务器类开源项目（GitHub），聚焦「特别、实用、值得借鉴」的功能

---

## 一、一句话结论

这个赛道已经很拥挤，但**没有人做你这种形态**（零原生依赖的 Node/SQLite 服务端 + 深度定制的 Android 阅读器）。竞品真正领先的不是核心功能（入库/书架/阅读），而是三件事：

1. **生态接口**——OPDS、KOReader 同步、Kobo 同步、Send-to-Kindle。有了它们，第三方阅读器（Moon+、Librera、KOReader、电纸书）就能直接用你的书库。
2. **阅读资产**——书签、高亮/标注、阅读统计。用户读得越久，这些东西越值钱，也是换不掉你的原因。
3. **入库自动化**——监视文件夹自动入库、元数据自动抓取、自动转换格式。

对你来说，**第 1 项投入最小、回报最大**（一个 OPDS 端点半天到一天）。

---

## 二、竞品全景

| 项目 | Stars | 语言/许可 | 定位 | 一句话特色 |
|---|---|---|---|---|
| **calibre-web**（janeczku） | 18.4k | Python / GPL-3.0 | 老牌 Calibre 库的 Web 前端 | 生态最全：OPDS、Kobo 同步、Send-to-Kindle、多用户权限 |
| **Audiobookshelf** | 14.8k | Node / GPL-3.0 | 有声书 + 播客（也支持电子书） | 移动端体验最好的自建音频/电子书服务，原生 App 全开源 |
| **Kavita** | 11.9k | C# / GPL-3.0 | 漫画/轻小说/电子书全能 | 元数据生态最狠（AniList/MAL/ComicVine），EPUB 标注、智能筛选 |
| **Komga** | 6.8k | Kotlin / MIT | 漫画/杂志/电子书 | 工程最扎实：OPDS v1+v2、Kobo/KOReader 同步、重复页检测 |
| **Calibre-Web-Automated**（CWA） | 6.5k | Python / GPL-3.0 | 给 calibre-web 加自动化 | 自动入库 + 自动转换 + 元数据写回文件 + 深度统计 |
| **BookLore** | 1.3k | Java / AGPL-3.0 | 新一代多用户书库 | BookDrop 投递即入库、Magic Shelves、三端同步；**已转维护模式** |
| **BookOrbit**（BookLore 继任者） | 新项目 | NestJS / AGPL-3.0 | 书 + 有声书 + 漫画 + PDF 一体 | 功能最激进：三端高亮聚合、阅读洞察、14 个元数据源、iOS + 手表 |
| **Talebook** | — | Python+Vue | 中文向自建书库 | 豆瓣/百度百科元数据、Legado 书源、Kindle 推送、AI 自然语言管理 |

> 说明：BookLore 官方已宣布进入维护模式，活跃开发转移到 BookOrbit（2026 年 9 月）。这说明**这类项目的维护成本很高**，功能堆得越多越难持续——对你的启示是：抄功能要抄"低维护成本"的。

---

## 三、特别且实用的功能清单（按可借鉴价值排序）

### A. 生态接入（投入小、回报大）⭐️⭐️⭐️

| 功能 | 谁有 | 具体做法 | 对你的价值 |
|---|---|---|---|
| **OPDS 目录**（1.2 / 2.0） | calibre-web、Kavita、Komga、BookLore、BookOrbit、Talebook | 一个只读的 Atom XML 目录 + 下载链接，按「作者/书架/最近添加」分组 | 立刻让 Moon+ Reader、Librera、KOReader、KyBooks 等第三方阅读器接入你的库，**不用自己写客户端** |
| **KOReader 进度同步**（kosync） | calibre-web（CWA 内置）、Komga、BookLore、BookOrbit | 两个接口：`/users/auth`（Basic Auth）+ `/syncs/progress`；文档 ID 用「文件 MD5 前 8 位 + 文件大小」 | 让电纸书（Kindle 装 KOReader、Kobo、Boox）和手机共享阅读进度 |
| **Kobo 原生同步** | calibre-web、Komga、BookLore、BookOrbit | 模拟 Kobo 的 `/kobo/<token>/v1/library/sync` 协议，返回 kepub 下载地址 | Kobo 用户不用装任何东西，书直接出现在设备书架上（协议逆向成本较高） |
| **Send-to-Kindle**（邮件推送） | calibre-web、CWA、BookLore、BookOrbit、Talebook、Audiobookshelf | 管理 SMTP 账号 + 收件地址，一键把书作为附件发到 Kindle 邮箱 | 一个 SMTP 配置就能覆盖"没有 App 的设备" |

### B. 入库自动化（省时间，但要注意坑）⭐️⭐️

| 功能 | 谁有 | 具体做法 | 备注 |
|---|---|---|---|
| **监视文件夹自动入库**（BookDrop / ingest） | CWA、BookLore、BookOrbit、calibre-web（CWA 层） | 轮询或 inotify 监视目录 → 自动解析 → 元数据 → 进待确认队列 | 你已经有 `imports/` 落地目录 + 手动导入接口，只差"自动触发 + 待确认队列"。**CWA 的坑：处理完会删源文件**，官方建议先下载完再移入 |
| **元数据自动抓取** | 几乎所有 | Google Books / Open Library / Amazon；中文用豆瓣、百度百科、新华书店、番茄小说（Talebook） | 你已有 `pinned_fields_json`（人工修改不被覆盖）机制，接抓取源是顺手的事 |
| **自动格式转换**（KEPUB/EPUB/MOBI） | CWA、Talebook、BookOrbit | 调 calibre 或 kepubify 二进制 | **与你的"零原生依赖"设计冲突**——建议做成可选 sidecar 容器，不进主镜像 |
| **重复检测** | CWA（SQL+模糊匹配，可自动合并）、Komga（重复文件 + 重复页检测/删除） | 归一化标题+作者+大小的相似度聚类 | 你已有内容哈希去重（同字节），缺"不同文件但同一本书"的检测 |
| **元数据写回文件** | CWA | 改封面/元数据时真正写进 epub 文件（否则 Send-to-Kindle 后别的设备看不到） | 你需要一个 epub 读写库，成本中等 |

### C. 组织与发现（结构上的升级）⭐️⭐️

| 功能 | 谁有 | 具体做法 | 对你的价值 |
|---|---|---|---|
| **Magic Shelves / 智能书架** | CWA、BookLore、BookOrbit | 规则驱动的动态书架：条件 + AND/OR 逻辑，实时更新；CWA 内置「最近添加 / 高评分 / 无封面 / 系列不全」模板 | 你的书架是静态两层分类；加"保存的筛选器"作为虚拟书架，等于免费送用户一堆实用书架 |
| **阅读列表 / 合集 / 想读** | Kavita（Want to Read、Reading Lists 支持 CBL 导入）、Komga（Read Lists）、BookOrbit | 手动编排的跨书架列表 | 你服务端已有 `collections`（合集）表和 `collection_books.position`、API 也支持按合集过滤——缺的是 App 端入口和"阅读列表"这种跨书架的编排 |
| **全文搜索 + 搜索语法** | Komga、BookLore（全文）、BookOrbit（可搜索高亮中心） | 支持 `author:`、`tag:` 之类限定词 | 你已有 SQLite FTS（`books_fts`），缺搜索语法解析和 UI 提示 |
| **多书库 / 按库权限** | Komga（per-library access）、BookOrbit（多书库+独立扫描规则） | 每个用户只看得到被授权的库 | 家庭场景有用（孩子的库 vs 大人的库） |

### D. 阅读体验（用户感知最强）⭐️⭐️⭐️

| 功能 | 谁有 | 具体做法 | 备注 |
|---|---|---|---|
| **书签** | Komga、BookOrbit | 记录位置 + 备注，跨设备同步 | **你的路线图里明确还没做**——这是最基础的一块 |
| **高亮 / 标注** | Kavita（EPUB 标注）、BookLore、BookOrbit（三端聚合 + 导出 MD/CSV/JSON） | 选中文字 → 存 CFI/字符偏移 + 颜色 + 笔记 | BookOrbit 的做法值得抄：把 Web/设备上的高亮汇总成一个可搜索、可导出的中心 |
| **TTS 朗读** | BookOrbit、Audiobookshelf、Talebook 的 Moke 客户端 | 系统 TTS 或流式合成 | Android 端用系统 TTS 即可，成本低 |
| **连续阅读 / 虚拟分页** | Kavita | 滚动模式不离开阅读器直接下一章；EPUB 虚拟分页 | 你的 App 已有上下滚动与翻页两种模式，属于打磨项 |
| **阅读统计** | CWA（深度分析+CSV 导出）、BookOrbit（时长/热力图/连续天数/年度目标/50+ 成就/Reading DNA）、BookLore | 记录每次阅读会话（开始/结束/页数）→ 聚合 | 你已有 `progress`/`last_read_at`，加一张 `reading_sessions` 表就能出热力图和 streak |

### E. 账号、集成与边缘能力（看需求）⭐️

| 功能 | 谁有 | 备注 |
|---|---|---|
| **OIDC / SSO** | Kavita、Komga、BookLore、BookOrbit、CWA | 家庭/小团队用 Authentik/Keycloak 统一登录；个人用不上，低优先 |
| **外部同步**（Hardcover / Readwise / StoryGraph） | BookOrbit | 把进度和高亮推到第三方阅读社区；属于"锦上添花" |
| **有声书** | Audiobookshelf、BookOrbit、Talebook | 书 + 听一体是明显趋势（BookOrbit 直接把它做进统计和进度体系） |
| **AI 能力** | Talebook（AI 识别书籍信息、自然语言管理书库） | 竞品里真正落地的 AI 还很少，多是"元数据识别"这种窄场景 |
| **原生移动端** | Audiobookshelf（Android/iOS 全开源）、BookOrbit（iOS + Apple Watch） | 你已有 Android 端，这是你的相对优势 |

---

## 四、对照你的项目：现在缺什么

| 能力 | 你现在 | 竞品普遍有 | 建议 |
|---|---|---|---|
| 入库（嗅探/哈希去重/解析/封面） | ✅ | ✅ | 保持 |
| 浏览/检索/批量整理 | ✅（FTS） | ✅ | 补搜索语法提示 |
| 增量同步/进度 | ✅（delta + 字符偏移） | ✅ | 保持 |
| 管理台在线阅读 | ✅（TXT） | ✅（EPUB/PDF/漫画） | 可扩 EPUB |
| 上传冲突三选/单本删除 | ✅（差异化） | 少见 | 保持，这是亮点 |
| 书架（两层分类 + 子书架磁贴） | ✅（形态独特） | ✅ | 保持 |
| **OPDS** | ❌ | ✅ 几乎全员 | **优先做** |
| **KOReader 同步** | ❌ | ✅ | **优先做** |
| **Send-to-Kindle** | ❌ | ✅ 多数 | 次优先 |
| **书签** | ❌ | ✅ | 优先（用户最基础诉求） |
| **高亮/标注** | ❌ | ✅ 多数 | 中期 |
| **阅读统计** | ❌ | ✅ 多数 | 中期（数据现成） |
| **元数据抓取** | ❌ | ✅ 几乎全员 | 中期 |
| **自动入库（监视目录）** | ❌（有 imports 目录但需手动触发） | ✅ 多数 | 中期 |
| **Magic Shelves** | ❌ | ✅ | 中期（低成本高感知） |
| 格式转换 | ❌（刻意不做） | ✅ 多数 | 低优先（会破坏零依赖设计） |
| 重复检测 | 部分（同字节去重） | ✅ 模糊检测 | 低优先 |
| OIDC | ❌ | ✅ 部分 | 低优先 |

---

## 五、给你的落地建议（按投入产出排序）

> ⚠️ 本项目定位是**个人自用（单用户）**，第 5 节的排序偏"通用产品"，实际执行请看**第七节「自用裁剪」**——那里砍掉了所有团队/多用户向的功能。

| # | 功能 | 工作量 | 为什么先做 |
|---|---|---|---|
| 1 | **OPDS 目录**（1.2 即可） | 半天～1 天 | 一个只读端点换来一整批第三方阅读器支持，投入产出比最高 |
| 2 | **KOReader 同步**（kosync 两个接口） | 1～2 天 | 电纸书用户的核心诉求；协议极简（Basic Auth + MD5 前 8 位做文档 ID） |
| 3 | **书签**（服务端表 + App 入口 + 同步） | 2～3 天 | 你路线图里的欠账，最基础的阅读资产 |
| 4 | **阅读统计**（`reading_sessions` + 热力图/连续天数） | 2 天 | 数据基本现成，感知强，还能反哺"最近在读"排序 |
| 5 | **元数据抓取**（Google Books + Open Library，中文加豆瓣/百度百科兜底） | 2～3 天 | 你有 `pinned_fields_json` 兜底，抓错了也不会覆盖人工修改 |
| 6 | **Send-to-Kindle**（SMTP 推送） | 1 天 | 一个配置项覆盖没有 App 的设备 |
| 7 | **Magic Shelves**（保存的筛选器 → 虚拟书架） | 2～3 天 | 你的静态分类之上加动态层，用户立刻多出一堆实用书架 |
| 8 | **自动入库**（监视 imports 目录 + 待确认队列） | 2～3 天 | 已有落地目录和导入接口，补"自动触发 + 确认"即可；注意别学 CWA 直接删源文件 |
| 9 | **高亮/标注** | 3～5 天 | 抄 BookOrbit 的"聚合中心 + 导出"思路 |
| 10 | **KEPUB 转换** | 2 天（sidecar） | 只在你真的接 Kobo 时才需要；做成可选容器，别进主镜像 |

---

## 六、竞品的坑，以及你的机会

1. **BookLore / BookOrbit 明确不支持 NAS / NFS / SMB**——官方文档写死：文件操作只保证本地文件系统可靠，网络存储只能进受限模式（元数据只存数据库、不动文件）。**而你正是为 NAS 设计的**（零原生依赖、命名卷、权限指引、升级指南）。这是可以明确宣传的差异化。
2. **BookLore 一年半就转维护模式**，功能堆太多维护不动。你保持"单一 SQLite + 无外部依赖"的克制是对的，抄功能时要算维护账。
3. **CWA 的 ingest 目录处理完会删文件**，是社区常见踩坑点。你若做自动入库，建议"移动 + 保留原件到 processed 目录"，或至少给开关。
4. **Kavita 至今仍标 beta**（1.0 未发布），Kavita+ 是付费元数据服务——说明"元数据"这类功能靠爱发电很难持续，做成可插拔、可关掉的选项更稳。
5. **趋势**：书 + 有声书 + 漫画一体化（BookOrbit、Audiobookshelf）、阅读洞察（Reading DNA）、跨设备高亮聚合。你要做的是"书"这一块做深，而不是摊大饼。

---

## 附：主要信息来源

- Kavita：https://github.com/Kareadita/Kavita （11,915 ★，GPL-3.0）
- Komga：https://github.com/gotson/komga （6,777 ★，MIT）
- calibre-web：https://github.com/janeczku/calibre-web （18,446 ★，GPL-3.0）
- Calibre-Web-Automated：https://github.com/crocodilestick/Calibre-Web-Automated （6,473 ★，GPL-3.0）
- Audiobookshelf：https://github.com/advplyr/audiobookshelf （14,759 ★，GPL-3.0）
- BookLore：https://github.com/booklore-app/booklore （1,314 ★，AGPL-3.0，维护模式）
- BookOrbit：https://github.com/bookorbit/bookorbit （AGPL-3.0 + 附加条款）
- Talebook：https://github.com/talebook/talebook
- 星标数为 2026-10-24 抓取

---

## 七、自用裁剪（单人使用，这才是实际要做的）

本项目只给自己用，报告里一多半功能可以直接划掉。

### 直接砍掉

| 砍掉 | 原因 |
|---|---|
| OIDC / SSO、多角色权限、年龄限制、按库授权 | 一个人用不上；现有 admin 账号足够 |
| 多书库 | 一个库 + 两层书架已经够 |
| Kobo 原生同步 | 除非你有 Kobo 设备；协议逆向成本高 |
| Send-to-Kindle | 除非你有 Kindle 且习惯邮件推书 |
| 格式转换（KEPUB / EPUB） | 只有接 Kobo 才需要，还会破坏"零原生依赖" |
| 外部同步（Hardcover / Readwise / StoryGraph） | 社交向，自用无意义 |
| 有声书 | 除非你听书 |
| 成就 / 年度目标 / Reading DNA | 花架子；热力图 + 连续天数就够回顾了 |

### 只留 5 件（按"每天读书的体验"排序）

| 顺序 | 功能 | 为什么对自用有价值 | 工作量 |
|---|---|---|---|
| 1 | **书签** | 读长篇的刚需，现在只能靠记住位置 | 2～3 天 |
| 2 | **阅读统计**（热力图 + 连续天数 + 总时长） | 数据现成（progress / last_read_at），自己看很直观 | 2 天 |
| 3 | **元数据抓取**（Google Books + 豆瓣/百度百科兜底） | 省手动整理，你有 pinned_fields 兜底，抓错不覆盖 | 2～3 天 |
| 4 | **自动入库**（往 imports 丢书 → 自动解析 → 待确认） | 少点几下；目录和接口都现成 | 2～3 天 |
| 5 | **OPDS**（**仅当你用第三方阅读器时才做**） | Moon+ / Librera / KOReader 直接连你的库 | 半天～1 天 |

> 判断标准很简单：**书签和统计是"每天都会用到"的，OPDS / KOReader 同步 / Kindle 推送是"设备驱动"的**——你手里有对应设备才做，没有就别浪费工时。

### 一句话

自用场景下，最值得投入的是**书签 + 阅读统计**（直接改善每天的阅读体验），其次是元数据抓取和自动入库（省整理时间）。生态接口类（OPDS / KOSync / Kindle）取决于你有没有第二个阅读设备。
