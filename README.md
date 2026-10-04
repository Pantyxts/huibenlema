# 回本了吗

纯本地、隐私优先的 Android 应用：拉取微信读书的书架与阅读进度，按书籍定价折算「已读总价值」，
对照你的阅读设备 + 会员等成本台账，计算回本进度与每日阅读价值。

- 应用名：回本了吗
- 包名：`com.huibenlema.app`
- 构建变体：`eink`（墨水屏版，先行）/ `universal`（通用手机版，阶段 3）
- 技术栈：Kotlin + Jetpack Compose + Retrofit + Room + DataStore + Hilt + WorkManager
- 数据源：微信读书官方 Agent Gateway（`wrk-` API Key）为主通道，私有 API 定价通道可降级
- 隐私：凭证 Keystore 加密、仅存本机、零上传

## 构建

```bash
./gradlew :app:assembleEinkDebug       # 墨水屏 debug 版
./gradlew :app:assembleEinkRelease     # 墨水屏签名版（阶段 4）
./gradlew :app:assembleUniversalDebug  # 通用 debug 版（阶段 3 起）
```

## 目录

- `app/` — Android 应用模块
- `tools/API-NOTES.md` — 官方 Agent Gateway 接口笔记（实现参考）
- `tools/test-weread-skill.sh` — 官方接口样本采集脚本
