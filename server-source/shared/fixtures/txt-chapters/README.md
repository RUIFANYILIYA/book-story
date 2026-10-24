# TXT 智能断章黄金用例

同一批样本同时驱动两侧实现，避免 TypeScript（服务端预览/字数统计）与 Kotlin（`engine-core` 阅读器）
在"什么算一章"上各自漂移：

- Kotlin：`android/engine-core` 的 `TxtChapterSplitterFixtureTest` —— 跑完整管线（字节 → 编码 → 归一化 → 断章），
  断言 `charset`、章节数、每个 `startChar`/`title`/`level`，以及标题类锚点确实落在它命名的标题上。
- TypeScript：`server/test/metadata-txt.test.ts` —— 断言同一批字节的 `charset` 判定与期望一致（断章只在 Kotlin 侧实现，
  但两侧对编码的判定必须一致，否则服务端预览与阅读器会显示两种文本）。

新增样本时：把 `cases/<id>.txt` 放好，先跑一次 Kotlin fixture 测试 —— expected 缺失时它会把提案写到
`android/engine-core/build/fixture-proposals/<id>.json` 并失败；**人工对照样本正文校对提案**后移进 `expected/`，再跑一次。

## 目录约定

```
txt-chapters/
  cases/<id>.txt          输入样本（UTF-8 或 GB18030，靠 bytes 读入，不做转码）
  expected/<id>.json      期望输出
```

`expected/<id>.json` 形状（M8 定稿，字段先冻结语义）：

```json
{
  "id": "01-wangwen-diyi",
  "splitMode": "AUTO",
  "charset": "UTF-8",
  "chapters": [{ "title": "第一章 风雪夜", "level": 1, "startChar": 0 }],
  "chapterCount": 3,
  "warnings": []
}
```

字段语义：
- `charset` 是**探测结果**（不是文件本身编码的声明），两侧都必须得出这个值。
- `warnings` 是**必须出现的子串**列表，匹配的是"归一化警告 + 断章警告"合并后的文本；空列表表示不做断言。
- `chapters` 的 `startChar` 见下文（归一化后文本的偏移）。

`05-headingless-30mb` 不落盘：30 MB 的样本不该进 git 历史。它由
`TxtChapterSplitterFixtureTest` 用固定配方在内存里生成（长度兜底切分必须在 [8000, 30000] 窗口内、切口落在空行后）。

`startChar` 是**归一化后**文本的字符偏移（不是原始字节偏移），因为归一化会剥页码、合并断行；
跨版本对齐只认这个偏移，页码不参与。

## 必收录的样本类型

| id 前缀 | 覆盖点 |
|---|---|
| `01-wangwen-diyi` | 网文 `第N章`，编号单调递增，含 `第0章 楔子` |
| `02-zhanghuiti` | 章回体对仗长标题 + `回首` 变体 |
| `03-en-chapter` | `Chapter N` / `Book N` / `Epilogue` |
| `04-toc-with-pagenum` | 开头有目录区且带页码，目录区不得进正文 |
| `05-headingless-30mb` | 无任何标题的超长文本，走长度兜底切分 |
| `06-gb18030` | GB18030 编码 + 中文标题 |
| `07-utf16le` | UTF-16LE with BOM |
| `08-page-footer-pollution` | 每页夹页码与书眉，须被剥离且不误判为章 |
| `09-false-positive-bait` | 正文里出现"他想起第三章说过的话""1. 首先"，须**不**断章 |
| `10-mixed-punct-forms` | `第一章：…`／`第 一 章 …`／`（一）…` 全半角混排 |

`09` 是本套用例存在的理由：断章算法最容易出的事故不是漏切，而是把正文里的编号列表切成一章，
导致目录里出现"2. 然后他笑了"。
