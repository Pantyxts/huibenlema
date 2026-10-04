# 微信读书官方 Agent Gateway API 笔记

来源：官方 Skill 包 `weread-skills.zip` v1.0.4（2026-07-01）+ 仓库 Tencent/WeChatReading

## 网关

- 统一入口：`POST https://i.weread.qq.com/api/agent/gateway`
- 鉴权：`Authorization: Bearer wrk-xxx`（API Key 绑定用户 vid，接口自动识别身份）
- `Content-Type: application/json`
- body：`{"api_name": "/xxx", ...业务参数平铺, "skill_version": "1.0.4"}`
- ⚠️ 业务参数与 `api_name` 同层，禁止包 `params` 对象（否则参数不被转发）
- `errcode != 0` 为错误；回包字段经服务端裁剪，只返回核心字段
- `{"api_name": "/_list"}` 可列出全部可用接口
- 回包出现 `upgrade_info` 时必须提示用户升级（App 内横幅），不能忽略

## 我们需要的接口

| api_name | 参数 | 关键返回 |
|---|---|---|
| `/shelf/sync` | 无 | `books[]`（bookId, title, author, cover, category, readUpdateTime, finishReading, updateTime, isTop, secret）、`albums[]`、`mp`、`archive[]`、`bookCount`。**无 progress、无 price** |
| `/book/info` | bookId | title, author, translator, cover, intro, category, publisher, publishTime, **isbn**, wordCount, newRating… 文档未列 price |
| `/book/getprogress` | bookId | `book.progress`（0-100 **整数**）、`book.recordReadingTime`（**秒**）、`book.updateTime`、`book.finishTime`（仅读完有）、`book.isStartReading` |
| `/book/chapterinfo` | bookId | `chapters[].price`（章节价格，0=免费）、`paid`、`chapterUid`、`title`、`wordCount`、`level` |
| `/readdata/detail` | mode=weekly\|monthly\|annually\|overall, baseTime | `totalReadTime`（秒）、`readDays`、`dayAverageReadTime`、`readTimes`（分桶时长：weekly/monthly **按天**，annually 按月，overall 按年）、`dailyReadTimes`（年度日级明细）、`readLongest[]`（周期内读得最多的书 top10，含 readTime 秒）、`readStat`（读过/读完/阅读/笔记） |
| `/user/notebooks` | count, lastSort | 官方 gateway 版笔记接口。**是否含 price 字段待实测**——决定定价能否全走官方通道 |

## 对 App 设计的影响

1. **书架/进度**：官方通道全覆盖。但进度需逐本 `getprogress` → 首同步全量，之后按 `readUpdateTime` 增量拉取
2. **每日阅读时长**：`readdata/detail` mode=monthly 的 `readTimes` 按天分桶，传历史 `baseTime` 可取历史月 → 「每日价值」平滑的数据源
3. **定价**：官方文档未见价格字段，实测路径优先级：
   a. `/user/notebooks`（gateway 版）是否含 price/centPrice
   b. `/book/chapterinfo` 章节价格求和（官方定价信号，可能≠图书定价，需校准）
   c. 私有 API `i.weread.qq.com/book/info`（Cookie，centPrice/originalPrice）
   d. ISBN 第三方 / 手动录入
4. **单位约定**：时长一律秒；progress 是 0-100 整数（100+finishTime 才算读完）；时间戳 Unix 秒

## 实测结论（2026-10-04，真实账号样本，见 tools/samples/）

| 接口 | 价格字段 | 结论 |
|---|---|---|
| `/user/notebooks`（官方） | ✅ **centPrice/price/originalPrice/payingStatus/payType/soldout/mcardDiscount 全有** | 官方批量定价通道！但只覆盖**有笔记的书**（实测 27/83 本） |
| `/readdata/detail` | ✅ readLongest/preferBooks 内的 book 对象含 centPrice | 附带覆盖 top10~12 本 |
| `/book/info`（官方） | ❌ 无价格，只有 isbn/publisher | 仅用于元信息和 ISBN 兜底 |
| `/book/chapterinfo` | ⚠️ chapters[].price，但已购书章节价全 0 | 对在读用户不可靠，弃用 |
| `/shelf/sync`（官方） | ❌ 无价格、无进度 | 需逐本 getprogress |
| 私有 API `i.weread.qq.com/book/info`（Cookie） | ✅ centPrice/originalPrice（WeReadPrice 已验证） | **补全剩余书籍定价的主通道** |

## 最终定价链路（PricingPipeline）

1. 本地缓存（付费书 TTL 24h / 免费书 7d，source 标记）
2. 官方 `/user/notebooks` 批量（有笔记的书）
3. 官方 `/readdata/detail` 附带采集（readLongest + preferBooks）
4. 私有 API `book/info` 逐本补全（有 Cookie 时；并发 ≤3、间隔 ≥400ms、单次上限 50 本）
5. ISBN 第三方（isbn.work 等，需 isbn 字段）→ 手动录入

## 进度同步链路

- 无 Cookie：官方 `shelf/sync` + 逐本 `getprogress`（首同步全量 ~83 次，之后按 `readUpdateTime` 增量）
- 有 Cookie：私有 `shelf/sync` 一次返回全部 bookProgress（高效），官方通道兜底
- 价格解析规则：`centPrice`(分) 优先；`originalPrice>0` 时取 max(originalPrice×100, centPrice)；centPrice=0 且 free/bookStatus=1 → 免费书缓存 0 元；payType 位标志仅作参考不计入口径

## 私有 API 备份通道（Cookie 登录态）

- `GET i.weread.qq.com/shelf/sync` — 书架 + 进度（一次全量，效率高）
- `GET i.weread.qq.com/book/info?bookId=` — 含 `price` / `originalPrice` / `centPrice` / `payingStatus` / `isbn` / `publisher` / `soldout` / `bookStatus` / `mcardDiscount`
- `GET i.weread.qq.com/user/notebooks` — 批量定价通道（每本含价格字段）
