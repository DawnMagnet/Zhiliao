# 知乎 11.10.0 (41012) 兼容性审查报告

- 审查对象：知了模块 31 个 Hook（libxposed API 102；2026-10-20 增至 32 个，见 §七）
- 审查方式：6 个并行子代理静态审查（javap 反汇编 + 整包常量池/字符串扫描，167486 个类）＋ 主代理真机实测（小米 2109119BC / Android 14 / LSPosed 2.2.0 / api=102）
- 证据 JAR：`targetapp/jars/11.10.0 41012.jar`
- 审查日期：2026-09-24

## 一、结论汇总

| 状态 | 数量 | Hook |
|---|---|---|
| ✅ 正常 | 15 | LaunchAd、FeedAd(主链)、AnswerListAd、CommentAd(回退)、AnswerAd、ShareAd、FollowButton、HotBanner、MineHybridView、ColorMode、NavRes、ExternLink、ThirdPartyLogin、Cleaner、ZhihuPreference |
| ⚠️ 可疑（有静默风险） | 7 | CustomFilter、Tag、Article、StatusBar、NextAnswer、RedDot、SearchAd |
| ❌ 已失效（静默/报错） | 7 | Horizontal(左右划)、FeedTopHotBanner、HeadZoneBanner、VIPBanner、FullScreen、LiveButton、AutoRefresh（处置见 §六） |

> 注：上表按"是否有功能级失效"划分；RedDot 实为 8/12 路径仍有效，AutoRefresh 为 2 处失效（含 1 处 P0）。
>
> 本表是 2026-09-24 的审计快照，不是现役结论：AutoRefresh 已按真机目标重写并通过真机验证（见 §二.7、§六），其余条目以 §六 修复进展为准。

## 二、已失效项（必须修）

### 1. 左右划切换回答（Horizontal）— 功能下线（已删除）
- 真机 LSPosed 日志抓到 `IllegalArgumentException / ClassCastException`（Integer → List）：`ActionSheetLayout` 的回调列表字段由 `z` 改为 `J`，而 `z` 被复用为 `private int z;`，强转抛异常并被适配层吞掉。
- 按字段类型重定位后仍不稳定；真机回答页已是 `ShortContainerHostActivity` + 纵向 `ViewPager2`，额外接管 `RecyclerView.onTouchEvent` 的手势实现也只做到单向可用。
- 新版知乎回答直接呈现在连续信息流中，翻页不再是核心交互，因此**整条功能删除**：`hooks/Horizontal.java`、设置项 `switch_horizontal` / `seekbar_sensitivity`、相关图标与 `Helper.sensitivity` 全部移除，设置页原「左右划」分类改为「回答」（仅保留「移除下一个回答按钮」）。

### 2. 隐藏推荐页置顶热门（FeedTopHotBanner）
- `deserialize` 已上移到父类 `com.zhihu.android.autojackson.BaseObjectStdDeserializer`；适配层 `hookAllMethods` 只扫本类声明方法 ⇒ 装 0 个 hook，完全静默。
- 修复：从父类按签名解析该方法 + 回调内 `thisObject.getClass()` 守卫（否则会波及全站反序列化）。

### 3. 隐藏热榜顶部置顶（HeadZoneBanner）
- `RankFeedList` 新增 `headZones`，宿主 fragment 优先消费 `headZones`，只清 `head_zone` 无效。
- 修复：同时置空 `headZones`；保留 `isInstance` 守卫。

### 4. 隐藏会员卡片（VIPBanner）
- `premium.view.VipEntranceView` 方法改名 `initView` → `init(Context)`，init 抛 NoSuchMethodException → **整条 hook（含 MoreVipData.isLegal）都不安装**。
- 修复：改解析 `init(Context)`；删已消失的 NewMoreFragment/resetStyle 分支；补 MoreVipData 的 null 守卫。

### 5. 禁止进入全屏（FullScreen）
- 旧匿名类 `clearscreen.d$c` 已不存在（匿名类序号在 11.10.0 大面积作废）。
- 修复：改钩具名 `enterClearScreen()`（`mix.mixshort.clearscreen.ClearScreenToolBarView` 与 `answer.module.mixshort.clearscreen.AnswerClearScreenToolBarView`），只拦 enter 不碰 exit。

### 6. 移除直播按钮（LiveButton）
- `FeedsTabsFragment.mEntranceManger` 字段已消失，且无同形态替代（该 fragment 所有自定义字段类型均无"首参 FrameLayout"构造器）。
- 修复：找不到稳定替代目标则显式报"不可用"，避免用户误以为功能仍在。

### 7. 禁止自动刷新（AutoRefresh）— 已按真机目标重写
- 静态审计（market 渠道包 JAR）给出的目标在真机上不存在：FeedAutoRefreshManager、com.zhihu.android.feed.delegate.m 运行期 ClassNotFoundException。
- 真机（小米渠道包）实际结构：BaseTabChildFragment.a(boolean)（= 返回顶部或刷新）、FeedFragment.a|b(boolean, delegate)（真实刷新入口）、feed.util.j（= 定时刷新管理器）。
- 旧实现的副作用：returnTopAndRefresh 解析成 BaseTabChildFragment.b(boolean)（另一个方法，被 no-op）；refreshSucceed 复位 hook 解析失败 ⇒ 第 2 次起 postRefreshSucceed 被永久吞掉。
- 新实现（真机日志已确认）：在 (boolean, refreshType) 入口按刷新来源名拦截 —— TabClick（再点「首页」tab）与 AutoRefreshBackground/DetailPage/OtherPage/SearchPage；Pull、RefreshButton 等主动刷新不受影响。实测：点「首页」只回顶部不再刷新，下拉刷新照常。

## 三、可疑项（静默风险，建议加固）

| Hook | 风险 | 建议 |
|---|---|---|
| CustomFilter | 新增 Author/RichText/TextImage/CoverContent 等嵌套元素；多处 NPE/越界被吞 | filter() 判 null type；补 extra/sourceLine/elements 守卫；作者行补 Author 分支 |
| Tag | `cardView.getChildAt()` 直接强转，结构一变即 CCE | instanceof 守卫；Card.extra 判空 |
| Article | `t == "article"` 引用比较恒假 | 改 `"article".equals()` |
| StatusBar | `n0$a` 字段改名 j/k，按名取绑错对象 → 主题回调每次抛 IAE | `Helper.findFieldByType` 精确定位 |
| NextAnswer | 定位到无关字段 `L`（真按钮字段是 `y`）；回调误用 `param.args[0]` | 按字段类型定位并置 GONE；修回调 |
| RedDot | 4 处目标消失（onUnReadCountLoaded 等），8/12 仍有效 | 移除死目标；`P1` 补 setAccessible |
| SearchAd | `SearchTopTabsItemList` 类消失；`SearchRecommendQuery.content` 改为 `commercialData` | 清空 `commercialData`；死分支降级不 throw |

## 四、真机实测（主代理）

| 项目 | 方法 | 结果 |
|---|---|---|
| 模块加载 | `/proc/<pid>/fd` 持有模块 APK、`libzhiliao.so` 已映射 | ✅ 注入成功 |
| 设置页注入 | `zhihu://app/settings` 打开知乎设置 | ✅ 「知了 / 当前版本 26.02.03」（当时的模块版本）正常显示 |
| 启动页广告 | 冷启动连续 10 次采样找"跳过/广告"标记 | ✅ 直接进 MainActivity，无广告痕迹 |
| 推荐流 | 滚动 6 屏 dump 检查"广告/推广/视频/播放"标记 | ✅ 均无命中 |
| 直播入口 | 首页 dump 检查"直播" | ✅ 无（switch_livebutton 生效） |
| 底部导航 id | 真机插桩记录 `getTab` 实参 | **实测 id = home / find / panel / message / profile**；旧 market/video/friend 已不存在 |
| 回答页翻页机制 | `javap -c PagerView.buildVP2()` | 纵向 ViewPager2（`setOrientation(1)`）；VP1/VP2 双实现由 AB 常量决定 |

## 五、可复用的经验（供后续版本适配）

1. **字段按名取是最大风险源**：11.10.0 出现"旧字段名被复用为其它类型"（ActionSheetLayout.z、n0$a 的 b→j/k）。一律用 `Helper.findFieldByType` 或类型/泛型校验。
2. **匿名类序号（`$c`、`$1`）在 11.10.0 大面积作废**：优先钩具名 public 方法。
3. **方法上移到父类**会让 `hookAllMethods`（只扫本类）静默装 0 个 hook —— 关键 hook 应显式解析父类方法或断言非空。
4. **运行期回调异常被静默吞掉**：只能靠 LSPosed 日志发现，关键 hook 建议显式打点。
5. **知乎字符串混淆**（"G…"/H.d）＝ 原地 XOR 固定 16 字节循环密钥 `09 e3 b5 7a df 50 cb 49 86 6e f0 28 92 85 a3 b7`，可用于核实常量与属性名。
6. **云端下发的 id 无法静态证实**（如底部导航 item id）：静态"全包扫描无命中"不能证明失效，必须真机验证。
7. **同一 versionCode 的不同渠道包混淆名完全不同**（market 包 vs 小米包）：审计 JAR 只代表那一份构建。Hook 目标要按结构/签名解析，运行期常量（如刷新来源名 Pull / TabClick）比类名、方法名稳定得多，改动后一律用真机日志验证。

## 六、修复进展（截至 2026-09-24）

- **静态修复**：6 个并行子代理按文件互斥分区完成；`gradlew testDebugUnitTest assembleRelease` 通过（单测 0 失败）。
- **功能下线**：LiveButton（直播入口已不存在）、Horizontal（左右划，见 §二.1）整条删除，并同步清理设置项、图标资源与 `Helper.sensitivity`。
- **设置页重排**：删除已消失的会员/视频/关注导航项与「左右划」分类；底部导航精简适配真机 `home / find / panel / message / profile` 五个 id；保留「禁用活动主题」「隐藏导航栏突起」。
- **真机回归**（小米 2109119BC / Android 14 / LSPosed 2.2.0）：模块注入正常、知了设置页可打开、冷启动无开屏广告、推荐流无广告与视频标记、底部导航隐藏生效、LSPosed 模块日志无新增异常。
- **遗留风险**：`targetapp` 审计 JAR 与真机安装的 base.apk 不是同一构建（183,316,018 vs 171,081,818 字节），混淆名不同 —— 一切以真机实测为准。
- **AutoRefresh 二次修复（2026-09-24）**：从真机拉取 base.apk → dex2jar 得到 targetapp/jars/xiaomi 11.10.0 41012.jar，用它 + 真机 hook 追踪（打印调用栈）定位真实刷新链路：
  BottomNavView.c → MainActivity.onTabReselected → MainDelegation.c → CommonReturnTopDelegate 的 onTabReselected 监听 → FeedFragment.a(boolean, TabClick)，再按刷新来源名拦截。
  真机日志：`[Zhiliao][禁止自动刷新] 已拦截 TabClick`；单测对两份 JAR 均通过。
- **AutoRefresh 三次修复 · 冷启动自动刷新（2026-09-24）**：真机日志里冷启动时 `FeedFragment#a/b`（已 Hook 的刷新入口）被调用却没有「已拦截」行 —— 说明来源名不在拦截名单里。
  静态定位（xiaomi 审计 JAR）：`FeedFragment.refresh(boolean)` 按入参选来源，`true → Pull(用户下拉)`、`false → AppStart(冷启动自动刷新)`；来源名全集在 `com.zhihu.android.feed.delegate.k` 的子类里，
  共 Ad / AppStart / AutoRefreshBackground|DetailPage|OtherPage|SearchPage / Back / Growth / Preload / PrivacyChange / Pull / RefreshButton / RefreshGuideButton / RestoreState / TabClick。
  修复：拦截名单加入 `AppStart`（原为 TabClick + AutoRefresh*）。
  真机验证（同版本号本地构建 / 小米 2109119BC / Android 14 / LSPosed 2.2.0）：冷启动 `[禁止自动刷新][调试] 来源 AppStart` → `已拦截 AppStart`，首页仍显示上次的缓存内容（断网冷启动同样有内容）；
  下拉刷新 `来源 Pull` 不拦截、`postRefreshSucceed` 正常触发；再点「首页」仍 `已拦截 TabClick`。
  经验：`k.b()` 是知乎自己的「用户主动刷新」判定（Pull / TabClick / Back / RefreshButton 为 true，其余为 false），来源名列表可能随版本增删，名单失效时可改用它。
- **AutoRefresh 四次修复 · 冷启动白屏（2026-10-18，知乎 11.11.0/41106）**：三次修复那套「按来源名拦截 `AppStart`」在 11.11.0 上会让推荐页整片空白——缓存渲染这条路本身就走刷新链路
  （`FeedFragment.b(boolean, refreshType)` → 数据源先吐 `FeedList.isCache=true` 的缓存，再吐网络结果），入口一拦，缓存也一起没了，屏幕上只剩顶栏和底栏。
  修复：`AppStart` 不再拦入口，改为放行整条链路，并在 `FeedFragment.postRefreshSucceed(ZHObjectList)` 里判断结果——带 `isCache` 标记的缓存照渲染，
  紧随其后的网络结果 `setResult(null)` 丢弃；用户主动刷新（Pull / RefreshButton / Back，非 TabClick / AutoRefresh*）时清掉抑制状态，新内容照常进来。
  首次安装没有缓存时第一份结果一律放行，避免「没缓存 + 丢结果」又白屏。
  真机日志：`冷启动放行 AppStart` → `渲染 AppStart 的内容（缓存=true）` → `丢弃 AppStart 的网络结果，保留上次内容`；
  截图确认第二次冷启动回到退出前那一屏，下拉刷新能换新内容，再点「首页」仍 `已拦截 TabClick`。
  单测断言 `AutoRefresh.canSuppressNetworkResult()`（两份审计 JAR 都能解析到 `postRefreshSucceed`）：解析不到就退回旧拦截行为，不再静默白屏而无人知。
- **AutoRefresh 五次修复 · 真正冻结缓存（2026-10-18，26.10.18，知乎 11.11.0/41106）**：四次修复只在 UI 层丢结果，数据层的请求照跑、结果照写缓存，
  所以「下次冷启动」渲染的是上次启动拉到的那批，屏幕看着没刷新，内容却一格格往前挪。
  这次不再拦请求，而是拦「网络结果写回推荐缓存」的那次写入：`FeedLocalDataSource` 的 `void(FeedList)` 方法，冷启动抑制期间直接跳过；缓存自己那份（`isCache=true`）照写，
  用户下拉后的新内容也照写。首装没有缓存时放行第一份结果并主动写回，避免下次冷启动白屏。
  定位不写死渠道包的混淆名：结构指纹是「`void(FeedList)` + 恰好两个方法的缓存回调接口 + 无参清缓存 + String 缓存键」，候选名单（官网 11.11 `q0.x#n`、小米 `b.b#a`、market 11.10 `o0.x`）
  优先，落空时用 DexKit 按精确签名（参数 FeedList、返回 void、限定 `com.zhihu.android.app.feed`）扫一遍并要求唯一命中。
  未知刷新来源改用 `refreshType` 自己的 boolean 判定（Pull / TabClick / Back / RefreshButton 为 true），避免新版本新增的自动来源被当成用户刷新放行。
  真机验证（小米渠道包 + 官网包，各跑一轮）：两次冷启动内容逐字一致；下拉刷新内容变化；再冷启动与下拉后逐字一致。
  日志：`缓存冻结=…b.b#a`（小米）/ `…q0.x#n`（官网）→ `渲染 AppStart 的缓存内容` → `已拦截网络结果写回缓存` → `丢弃 AppStart 的网络结果（第 1 份）`。
  单测新增 `autoRefreshFreezesCacheOnEveryAuditJar`，对 11.10.0 的 market / 小米两份 JAR 断言缓存写入目标都能解析到。

## 七、11.11.1 两处漏网广告（2026-10-20，知乎 11.11.1/41108 小米商店包）

真机截图里还剩两处广告：推荐流顶部横贯整屏的「京东 11.11」广告栏，以及浮在内容右侧、带关闭按钮的「天猫」浮窗。查下来它们是两条完全不同的链路，都不经过信息流数据，所以按条目删广告的那套从来没碰过它们。

1. **顶部广告栏不来自 FeedList**：`com.zhihu.android.ad_card` 是独立的广告卡片模块，feed 通过服务接口 `AdCardViewInterface#getAdCardView(回调, cardType)` 向它要一个 `AdFocusView`，拿回来自己插在列表最前面。
   所以 `FeedAd` 里删 `FeedAdvert`、`interceptDataRange` 那几条对它无效。修法是在「要视图」这一步回 null：`AdCardViewInterfaceImpl` 里签名是 `void(回调接口, int)` 的那个方法（方法名跟接口走，参数类型才是稳的锚）。
   真机日志：`[自检][命中] 去信息流广告 com.zhihu.android.ad_card.AdCardViewInterfaceImpl#getAdCardView`，列表顶部广告消失。
2. **浮窗是「特形广告」里的彩蛋**：`com.zhihu.android.ad.special.AdEgg` 把图片和关闭按钮加进 DecorView（`removeFromDecorView` 对应着加），所以它浮在所有内容之上；
   同族的 `AdFloat`（首屏浮层）、`AdFloatSecond`（二屏浮层）、`AdFloatVideo`（视频浮层）、`AdSearchFloatVideo`（搜索浮层）、`AdFeedEggManager`（推荐页彩蛋）共用一条数据链：
   先请求 `commercial_api/app_float_layer`，再按 `float_layer / second_float_layer / feed_egg / search_middle_page` 分四份存成 `ad.special.b` 包里的单例配置。
   展示前每个 presenter 都要问同一个继承来的 `initData(String)`「这个类型有没有数据」，所以新 Hook「去浮层广告」让它一律答 false；
   推荐页彩蛋管理器另走一条路，它那组无参方法（`void()`）全部拦掉。
   父类名是混淆的，presenter 名公开，所以顺着 presenter 的继承链取公开方法；注意父类里有**两个** `boolean(String)`：公开的 `initData` 和私有的 `isImageCached`，
   按签名挑会挑错（第一版就装到 `isImageCached` 上了，靠一行"目标="日志发现），现在只认名字，单测也会断言挂到的确实是 `initData`。
   真机日志：`[自检][命中] 去浮层广告 com.zhihu.android.ad.special.a#initData`；当天该设备已没有浮层数据（`initData` 全 false），浮窗的视觉回归要等下一次投放再确认。
3. **云端巡检一直是红的，但跟混淆名无关**：`.github/workflows/compat-patrol.yml` 的校验步骤用 `$args = @('-ApkPath', ...)` 再 `@args` 展开——数组展开在 PowerShell 里是**位置参数**，
   `-ApkPath` 会被当成值传进去，于是每次都报「找不到 APK：-ApkPath」，13 秒就失败，连包都没校验过。改成哈希表展开 `@{ ApkPath = 'zhihu.apk' }`。
   另外仓库关了 Issues，失败时那步 `issues.create` 会再抛一次 `Issues has been disabled`，加个 `has_issues` 判断。
   本地用同一份 APK 手动跑通：dex2jar 需要 8GB 堆（`_JAVA_OPTIONS=-Xmx8g`），`testDebugUnitTest` 三分包全绿。

## 八、联投开屏漏网（2026-11-08，知乎 11.13.0 / 11.14.0）

真机截图里冷启动进首页后，还有一支整屏创意直接盖在信息流上（京东 11.11 那种：右上角「2 关闭」，素材本身半透明），老的三条 `sdk.launchad` Hook 一条都碰不到它。

1. **链路**：`com.zhihu.android.behavior.ibehaviorreceiver.BehaviorInnerObserverProvider#getIAdCombine` 给首页 fragment 建一个
   `com.zhihu.android.app.ui.fragment.ad.combine.AdCombineWatcher`；它 `onHostResume` → `tryGoCombineFragment()`，依次问
   `IAdLaunchStatus#isShowCombineAd()`（超级首映）、`IAdSoSoServer#existAdFeedSoSoData()`（搜搜联动），再看自己的 `checkData()` 有没有数据；
   全过了就路由到 `zhihu://feed_new_ad_combine_fragment/2`，由 `AdNewNewCombineFragment`（继承 `LaunchAnimateFragment`，带圆形展开动画）把整屏视图盖上来。
2. **为什么老 Hook 拦不住**：视图本身是新的 `com.zhihu.android.launch` 模块出的——`LaunchApi#requestAd/buildLaunchView` → `u1.d.*` presenter，
   跳过按钮、摇一摇、浮层视频都在这里面。开屏页 `LaunchFragment` 也用这套 UI，但它挂在 `LauncherActivity` 里、由 `LaunchAdHelper#isShowLaunchAd` 管（已拦）；
   联投这条既不看 `isShowLaunchAd`，也不进 `com.zhihu.android.sdk.launchad`，所以老 Hook 和清 `room_launch_ad` 都碰不到它。
3. **修法**：`LaunchAd` 加四条判断，都归「去启动页广告」这个开关：`AdCombineWatcher#checkData` 答 false（有没有数据 → 没有），
   `AdLaunchStatus#isShowCombineAd` / `getSoSoCombineAdShow`、`AdSoSoServer#existAdFeedSoSoData` 答 false（素材状态位 → 没有）。
   只堵 `checkData` 不够：搜搜联动的标志是纯 getter，没人消费，每回一次首页都要再判一次并发一条 `CombineWatcherDataError` 埋点；
   `AdFloatVideo` 又会在 `isShowCombineAd() || getSoSoCombineAdShow()` 为真时注册「联投动画播完」的监听然后 return——联投页不出现，这个回调永远等不到，
   那支浮层视频整场不显示（现在被「去浮层广告」盖住了，只在用户关掉它时暴露）。
4. **版本口径**：这套类和路由在 11.10.0 的两份审计包里就已经齐全（`AdCombineWatcher`、`AdNewNewCombineFragment`、`LaunchApi`、`AdLaunchStatus` 方法名一致），
   **不是 11.13 新增的代码**，11.13.0 真机才看到实际投放。所以这四条 Hook 在旧基线上同样会装——旧基线没做真机回归，属于未验证改动。
5. **真机现状（素材未复现）**：知乎 11.14.0/41410，小米 2109119BC / Android 14 / LSPosed 2.2.0：`安装 去启动页广告 句柄=+7`（原 +3），
   `初始化成功=32/32 Hook句柄=92`；首页 resume 时日志出现 `[自检][命中]` 的 `AdLaunchStatus#isShowCombineAd`、`AdLaunchStatus#getSoSoCombineAdShow`、
   `AdSoSoServer#existAdFeedSoSoData` 三条，连开三次冷启动无崩溃，推荐流、下拉刷新正常。当天该设备已不再投放联投素材（把开关关掉也复现不出广告），
   **"广告消失"的视觉回归还没确认**，要等下一次投放——和 §七.2 的浮窗一样，属于「钩子已挂、素材待现」。
6. **残留风险**：没有拦 `LaunchApi#buildLaunchView`——它只负责「把广告视图建出来」，调用方是 `LaunchFragment`（受 `isShowLaunchAd` 管）和
   `AdNewNewCombineFragment`（受上面四道判断管）。将来若出现第三个触发点（比如信息流里的联投卡片直接开这个 fragment），视图还会被建出来；
   目前全包里引用它的只有这两处。
7. **单测**：`HookTest#launchAdCombineTest` 以「新开屏模块在不在」（`LaunchApi`）为门槛，在就要求四条目标全部解析到；四份审计包都过门槛。

