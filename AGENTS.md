# AGENTS.md

知了（Zhiliao）：知乎 Android 客户端的 LSPosed 模块，去广告 + 界面净化。运行在知乎进程内，不是独立 App。

## 怎么跑

```powershell
# 兼容性单测（不需要设备）：遍历 targetapp/jars 里每个审计 JAR 跑每个 Hook 的 init()
$env:ZHILIAO_TEST_DIR = (Resolve-Path '.\targetapp\jars').Path; .\gradlew.bat testDebugUnitTest

# 从 APK 生成审计 JAR（可加 -RunTests）
.\tools\prepare-target.ps1 -ApkPath '.\targetapp\<知乎>.apk' -SkipDecompile

# 发布构建（R8 + native），产物 app/build/outputs/apk/release/Zhiliao_<版本>.apk
.\gradlew.bat assembleRelease
```

真机验证：`adb install -r <apk>` → `adb shell am force-stop com.zhihu.android` → 启动知乎 → 看 LSPosed 日志里的 `[Zhiliao][自检]` 行
（`Hook句柄=… 初始化成功=32/32 全部可用` 表示所有 Hook 装上；失败会打 `[兼容性][版本][功能][阶段]`）。
设备端日志：`/data/adb/lspd/log/modules_*.log`（LSPosed 2.x）。

## 技术栈

- Kotlin（K2）+ libxposed Modern API 102。薄适配层在 `app/src/main/java/com/shatyuka/zhiliao/xposed/`（保留旧 before/after 写法）。设置页是 Material 3 Compose。唯一的 C++ 是非官方包的 `fopen` 拦截，原因见 `docs/NATIVE.md`。
- 入口 `MainHook`（`app/src/main/resources/META-INF/xposed/`）→ `Hooks` 依次 init/hook 每个 `IHook`。
- CMake native（Dobby 子模块，仅 `zhiliao.cpp`）+ R8 full mode；版本号在 `gradle.properties` 的 `appVerName` / `appVerCode`。构建脚本是 Kotlin DSL，依赖版本在 `gradle/libs.versions.toml`。

## 约定（改 Hook 前必读）

- 每个 Hook 一个类：`init()` 只解析目标，`hook()` 只安装 Hook —— 单测靠这个约定在没有 LSPosed 的 JVM 里抓版本变化。
- 目标定位按结构/签名（`TargetResolver`、`Helper.findClass`、类型校验），不要堆版本号判断；方法名/类名会变，运行期常量（如刷新来源名 `Pull` / `TabClick`）更稳。
- **同一 versionCode 的不同渠道包混淆名不同**：`targetapp/jars` 里的审计 JAR 只代表那一份构建，改动必须真机验证。详见 `docs/DEVELOPMENT.md`。
- 改动用户可见行为时同步 `README.md` 的功能列表；设置项写在 `app/src/main/java/com/shatyuka/zhiliao/MainActivity.kt` 的 `Catalog`（图标见 `tools/update-icons.mjs`，Lucide，ISC），新功能还要在 `HookStatus` 里登记「功能名 → 开关键」，否则定位失败时设置页不会置灰。

## 发行说明

- GitHub Release 的说明用书面文案：`- 功能名：说明。` 一句一条，只写用户可感知的变化，3～5 条封顶。
- 不写实现细节、人称、口语与语气词，不贴 Full Changelog 链接；末尾单独一行 `适配基线：知乎 <版本>（<versionCode>）。`
- 补发或修订旧版本的说明时沿用同一格式，`gh release edit <tag> --notes-file`。

## 当前状态

- 现役 26.11.08；真机已验证：知乎 11.14.0/41410（官网包）32/32 Hook 可用、连开三次冷启动无崩溃，联投开屏的拦截点已命中（广告视觉回归等下一次投放）；11.11.1/41108（小米商店包）、11.11.0/41106 小米渠道包与官网包在 26.10.20 上验证过。
- 顶部焦点广告栏与浮层/彩蛋广告是两条独立链路，都不走 `FeedList`：前者由 `ad_card` 模块出视图（拦 `AdCardViewInterfaceImpl#getAdCardView(回调,int)`），后者是「特形广告」`AdEgg`/`AdFloat`（拦父类的 `initData(String)`，注意别挑成同签名的私有 `isImageCached`）。详见 compat-audit §七。
- 联投开屏（超级首映 / 搜搜联动）是第三条开屏链路：`AdCombineWatcher` 在首页 resume 时把 `LaunchImpl` 建好的整屏视图盖在信息流上，既不看 `isShowLaunchAd`，也不进 `sdk.launchad`。`LaunchAd` 现在让 `AdCombineWatcher#checkData` 和 `AdLaunchStatus#isShowCombineAd`／`getSoSoCombineAdShow`、`AdSoSoServer#existAdFeedSoSoData` 都答 false（只堵 `checkData` 会漏掉搜搜联动、并让 `AdFloatVideo` 卡在等联投动画）。这些类 11.10 就有、11.13 起才投放，旧基线同样会装但没做真机回归。详见 compat-audit §八。
- 禁止自动刷新按刷新来源名分流：冷启动 `AppStart` 放行整条链路（缓存渲染成上次那一屏），随后在 `postRefreshSucceed` 丢弃网络结果，并在数据层拦截这次网络结果写回推荐缓存——两步一起才叫冻结（只丢 UI 结果的话，磁盘缓存仍会前进一格，下次冷启动就被带跑）。点「首页」`TabClick` 直接拦截；下拉 `Pull` 放行并解除冻结。冷启动不能直接拦截——拦掉刷新等于连缓存渲染一起拦掉，首页整片空白（真机已验证）。
- 缓存写入按结构定位：`FeedLocalDataSource` 的 `void(FeedList)` + 缓存回调接口 + 无参清缓存 + String 缓存键四个特征，候选名单（官网 11.11 `q0.x`、小米 `b.b`、market 11.10 `o0.x`）优先，落空时 DexKit 按精确签名扫 `com.zhihu.android.app.feed` 兜底；绝不写死渠道包的混淆名。未知刷新来源用 `refreshType` 自己的 boolean 判定，避免新来源被当成主动刷新放行。
- `docs/compat-audit-11.10.0.md`、`docs/watermark-audit-11.10.0.md` 是审计快照：现役结论看 compat-audit §六～§八，水印结论目前只有静态证据、真机未验证。
- 反追踪的分享链路在 11.11.0 小米渠道包上类名整体漂移（`sharecore.u.d`→`sharecore.i.d` 等）。二维码**只能本地生成**：服务端 `image_share/qrcode` 并不按 `content` 原样编码，而是缩成 `http://s.zhihu.com/2_xxxx` 短链（用 OpenCV 解码同一链接的带参/不带参两张图确认，两次短链还不一样），所以改 `content`、改请求、改 URL 一律等于白改。现做法在 `ZHDraweeView#setImageURI(String)`（该方法定义在父类 Fresco `SimpleDraweeView` 上，按继承链找）拦下 `image_share` 地址，用 zxing 按干净链接本地编码一张，经 `getHierarchy().setImage(...)` 塞进控件并 `setResult(null)`，不联网也不经过 `ImageUrlUtils`。二维码**不能**换成本地 data URI：`setImageURI` 前会过 `ImageUrlUtils`，`data:` 直接抛异常，卡片留白框、切样式闪退（真机已复现）。
- 分享链接去参改走**文本级**：拦 `ClipboardManager#setPrimaryClip` 与 `Intent#putExtra/putExtras` 里的 `EXTRA_TEXT`，用正则认知乎链接再剥 `utm_*`/`share_code`，完全不依赖混淆名（真机已验证：复制链接粘出来是干净地址）。单测 `ShareLinkTest`。长图分享的二维码由 `WebRenderShareActivity` 里的网页自己画，不归客户端 hook 管，详见 tracking-audit §五～§七。
