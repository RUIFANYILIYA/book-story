# 分页器黄金用例

`engine-core` 的分页是纯函数：`(document, PageSpec) -> pages`。这里放输入/输出对，
让版面结果可回归，不至于改一行断点逻辑就把全书页码挪位。

```
pagination/
  specs/<id>.json         PageSpec：内容宽/高、字号、行距倍数、边距、字体度量表
  docs/<id>.json          Block 序列（不经文件解析，直接喂模型）
  expected/<id>.json      每页行数、每页 firstChar/lastChar、总页数
```

字体度量以**注入的 `GlyphMeasurer` 表**为准（等宽假字体），因此同一份用例在 JVM 上可确定复现，
不依赖 Android 的 `Paint`。真机字形差异由 M10 的设备验证覆盖，不进黄金用例。

**当前状态（M8）**：`specs/`、`docs/`、`expected/` 的用例文件尚未落盘；下面六类行为已由
`engine-core` 的 `PaginatorTest` 用合成度量表逐条钉住（含精确的行文本、对齐间隙与页数断言）。
落盘用例的增量价值在"跨版本版面回归"——同一份输入在改动断行逻辑后页码会挪位，设计约束见
下方列表；实现落地前不改这份契约。

必须覆盖：
- 中文禁则：行首不得出现 `，。、；：！？）》”`
- 中英混排：ASCII 词不拆断，CJK↔ASCII 边界可断
- 两端对齐：末行不对齐；中文间隙补偿上限 0.33em
- 首行缩进 2em 时行数与不缩进时的差异
- 图片块独占页 / 与文字混排的分页边界
- 熔断器：构造一个必然超迭代上限的 spec，断言抛 `PaginationOverflow` 而不是挂死
