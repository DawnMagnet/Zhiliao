# 知乎版本适配与开发

## 本机环境

- JDK 17
- Android SDK 36、Build Tools 36
- NDK 30.0.16248370
- CMake 3.22.1、Ninja
- JADX 1.5.5
- dex2jar 2.4（大型 APK 的 Java 内存上限建议设为 8GB）
- ADB 和安装了 LSPosed 的 arm64 测试设备

项目使用 `JAVA_HOME` 和 `ANDROID_HOME`。本机 SDK 路径写在被 Git 忽略的 `local.properties` 中。

## 模块结构

模块使用 [libxposed](https://github.com/libxposed/api) 的 Modern Xposed API 102，依赖 `io.github.libxposed:api:102.0.0`，需要实现 API 102 的框架（LSPosed 2.1.0 及以上）。

- 入口类 `com.shatyuka.zhiliao.MainHook` 继承 `io.github.libxposed.api.XposedModule`，由 `app/src/main/resources/META-INF/xposed/java_init.list` 声明，不再使用 `assets/xposed_init`。
- `META-INF/xposed/module.prop` 声明 `minApiVersion`、`targetApiVersion`、`staticScope`，`META-INF/xposed/scope.list` 声明作用域。这些文件必须进入 APK，所以 `packagingOptions` 只能排除具体的 `META-INF` 元数据文件，不能整体排除 `META-INF/**`。
- 模块名取 `android:label`，模块描述取 `android:description`，不再需要 `xposedmodule`、`xposedscope` 等 meta-data。
- `com.shatyuka.zhiliao.xposed` 是薄适配层：`XposedBridge`、`XC_MethodHook`、`XC_MethodReplacement`、`XposedHelpers` 保留 before/after 回调写法，内部转换成 API 102 的 `hook(Executable).intercept(Hooker)` 拦截器链。业务 Hook 沿用原有写法，新代码也可以直接使用链式 API。
- 框架不再提供 `XposedHelpers`，适配层只实现本项目用到的部分。Modern API 移除了资源 Hook，`NavRes` 通过 Hook 普通 Java 方法实现。
- release 构建用 proguard 保持入口类名，并用 `-adaptresourcefilecontents META-INF/xposed/java_init.list` 让 R8 同步重命名后的类名。

## 新版适配流程

把合法取得的知乎 APK 放进 `targetapp`，然后执行：

```powershell
.\tools\prepare-target.ps1 -ApkPath '.\targetapp\知乎11.1.0.apk' -RunTests
```

脚本会自动读取版本名和 versionCode，生成 `targetapp/jars/<版本名> <versionCode>.jar`，并生成可搜索的 JADX 源码。只想生成测试 JAR 时增加 `-SkipDecompile`。

也可以单独测试已经准备好的所有目标版本：

```powershell
$env:ZHILIAO_TEST_DIR = (Resolve-Path '.\targetapp\jars').Path
.\gradlew.bat testDebugUnitTest --rerun-tasks
```

## Hook 目标选择原则

新增或修复 Hook 时按以下顺序定位目标：

1. 优先使用没有混淆的稳定接口、模型类和 Android 回调。
2. 类名会变化时，用接口、父类、字段类型和方法签名组合识别。
3. 方法名会变化时，使用 `TargetResolver` 按参数、返回值、修饰符和稳定顺序解析。
4. 只有无法枚举目标包时才增加候选类名；不要继续堆叠版本号判断。
5. 如果目标跨包移动或没有可枚举名称，再引入 DexKit，用字符串和调用关系查找。`TargetResolver` 应继续作为统一入口，避免业务 Hook 直接依赖 DexKit。唯一的破例是 `AutoRefresh` 用 `findMethodsBySignature` 按精确签名找缓存写入方法——签名是结构信息，不是混淆名；这条扫描只在后台线程跑，结果记进 `ResolutionCache`，下次冷启动不重复扫。
6. 包内混淆名按 R8 的顺序 `a` … `z`、`a0` … `z0`、`a1` … 递增，`Helper.findClass` 的 `cycleRound` 就是枚举窗口，每轮 26 个名字。窗口不够时定位会直接失败：11.10.0 的 `WebViewClientWrapper` 已经排到 `com.zhihu.android.app.mercury.web.z0`，刚好落在 `cycleRound = 2` 之外，需要放宽到 3 轮。定位失败时先数清目标包里的类，再决定窗口大小。
7. 同一 `versionCode` 的不同渠道包混淆名可能完全不同（如 11.10.0 的 market 包与小米包）：`targetapp/jars` 里的审计 JAR 只代表那一份构建。定位一律按结构/签名，运行期常量（例如刷新来源名 `Pull` / `TabClick`）比类名和方法名稳定；改动后必须用真机日志确认。
8. 静态找不到真目标时，可以从真机 `base.apk` 重新生成审计 JAR，再配合 `[自检][触发]` 追踪与临时打印调用栈的 Hook 定位真实链路（AutoRefresh 就是这么定位到 `CommonReturnTopDelegate` 的）。

每个 Hook 的 `init()` 必须完成全部目标解析，`hook()` 只负责安装 Hook。这样 JVM 兼容性测试可以在没有 LSPosed 的情况下发现大多数版本变化。

唯一的例外是"目标解析本身很贵"的情况：`AutoRefresh` 的 DexKit 兜底要在启动时扫整个 APK，不能放在主线程。约定是 `init()` 只做名单/解析缓存这类一次 `Class.forName` 的便宜解析，扫 dex 交给后台线程，扫到后由后台线程补装那一条 Hook（`XposedBridge.hookMethod(member, callback, feature)` 显式带功能名，不依赖安装期的 `currentFeature`）。新增同类目标时照这个形状写，别再往主线程塞扫描。

## 真机验证

单元测试不能执行 LSPosed 的 native Hook，因此发布前仍需在 Android 14 或更高版本的 arm64 设备验证：

1. 安装构建出的模块 APK 和目标知乎版本。
2. 在 LSPosed 中只勾选知乎，强制停止后重新启动知乎。设置在独立的知了 App（桌面图标或 LSPosed 管理页）里打开，不再在知乎设置页内。
3. 逐项检查启动广告、信息流、回答、评论、搜索、分享和 WebView。
4. 失败时保存 LSPosed 模块日志和 `adb logcat`。日志中的 `[兼容性][版本][功能][阶段]` 可区分目标定位失败与 Hook 安装失败。

## 独立设置 App

设置界面是模块自己的 Activity（`MainActivity`，Material 3 Compose），不再注入知乎设置页。Android 12 及以上默认跟随壁纸取色（`switch_dynamic_color`），更早的系统用内置配色。宽屏（展开宽度）左侧是分区导航。要点：

- 配置存放在 LSPosed 远端偏好（`zhiliao_preferences` 组）：App 侧经 `XposedServiceHelper` 读写并自动申请/收敛知乎 scope；Hook 侧在 `MainHook` 用 `getRemotePreferences` 读取，**只读**。
- 旧版设置文件（知乎私有目录的 `zhiliao_preferences.xml`）**不参与读取**。曾经的"远端缺键就回落旧值"会造成界面与行为打架：App 按默认值显示全关、Hook 按旧文件里的开关干活（旧配置里开着的过滤视频/过滤文章/禁止自动刷新足以让推荐首页全空）。现在只在首次检测到时弹一次提示（`Helper.detectLegacyPreferences`），让用户在新设置页重新确认。
- 图标沿用原设置页的 Lucide 资源，外层是分组色的圆角底座。配色走 `ZhiliaoTheme`：Android 12+ 且 `switch_dynamic_color` 为真时用 `dynamicLightColorScheme` / `dynamicDarkColorScheme`，否则用内置方案。
- "重启知乎""清理临时文件"走 signature 级自定义权限的广播（`RESTART_ZHIHU` 权限），Hook 侧 `MainHook.registerModuleBroadcasts` 接收。
- 状态回执：设置页的「自检」行发有序广播 `ACTION_STATUS`，Hook 侧把 `Hooks.statusLine()`（如 `知乎 11.10.0/41012 初始化成功=31/31`）塞进 result extras，App 的 resultReceiver 取回来显示。发送方要有权限，靠的是 Hook 侧注册接收器时声明的 `RESTART_PERMISSION`；`sendOrderedBroadcast` 的第二个参数是"接收方需持有的权限"，别搞反。接收器必须 `RECEIVER_EXPORTED` 才收得到跨应用广播。
- 置灰（自动降级）：`HookStatus` 里维护"功能名 → 它负责的设置项"映射，某条 Hook 定位/安装失败时把对应键记进账本，随状态回执一起发 `failedKeys`；设置页把这些键的开关置灰、副标题换成"当前知乎版本暂不支持"。新增 Hook 忘了登记映射会被 `HookStatusTest.everyHookIsAccountedFor` 拦下。
- 预演降级界面：`adb shell am broadcast -a com.shatyuka.zhiliao.DEBUG --es cmd unsupported --es feature 去搜索广告`（`--es feature clear` 复位），不用真的把 Hook 改坏就能看效果。

## DexKit 兜底（第三阶段）

候选名单和结构特征都落空时的最后一级。几个必须知道的坑：

- **原生库要自己加载**。DexKit 2.x 不再自动加载 `.so`，而模块跑在知乎进程里，知乎的 `nativeLibraryDirectories` 里没有 `libdexkit.so`。可以用 LSPosed 支持的 `模块APK路径!/lib/arm64-v8a/libdexkit.so` 形式 `System.load`，见 `DexKitFallback.ensureLoaded()`。日志里会打 `[兜底] DexKit 原生库已加载：...`。
- **扫的是宿主的 APK**（`Helper.context.getApplicationInfo().sourceDir`），不是模块自己的 APK——扫错对象会一个都命中不了，而且不报错。
- **R8 必须保留 dexkit 类**：`-keep class org.luckypray.dexkit.** { *; }`。native 侧按类名/签名对齐，被 `-repackageclasses` 挪走就崩。
- **规则从严**：限定 `com.zhihu.android` 包、结果数超过上限直接放弃、必须带运行期字符串锚点；扫描完立刻 `close()`，不挂在知乎整个生命周期。
- **不许跑在主线程**：DexKit 要先 `System.load` 原生库再遍历整个 APK，换渠道包、候选名单落空的那次冷启动如果同步扫，界面会停在这里。`AutoRefresh` 把它放在 `ZhiliaoAutoRefreshResolve` 后台线程，命中后补装 Hook；扫到的类名写进 `ResolutionCache`（键 `autoRefresh.feedCacheWriter.dexkit`），指纹变化时随缓存整份作废。开关没开时连这个线程都不起。
- 探针：`adb shell am broadcast -a com.shatyuka.zhiliao.DEBUG --es cmd dex`（不带 `--es str` 跑诊断，带则按字符串锚点查询）。真机结果：`dex=18 包内类=118757 按全名命中=1 RecyclerView.Adapter子类=130 含"Pull"=33`。

现在接的只有「精简文章页面」一处：名单落空时按"有 `getItemCount` 且持有 Fragment 字段"扫一遍，扫出唯一一个才用，0 个或多个都退回原来的结构化链。真机上这条结构规则还没命中（知乎的适配器隔着一层自家基类），所以目前等于安全的空转——不影响任何现有功能。规则怎么调准，记在 ROADMAP 第三阶段。
- 构建：AGP 9 内置 Kotlin 支持（不要再加 `kotlin-android` 插件）；libxposed service 102 要求 compileSdk 37；R8 + Compose 需要 `org.gradle.jvmargs=-Xmx8g`（已在 gradle.properties）。

## 兼容性巡检 CI

.github/workflows/compat-patrol.yml，每天一次（UTC 20:00，约北京时间 04:00），也能手动触发。流程：问官网入口要最新包 → tools/verify-zhihu-apk.ps1 校验包名/版本/签名证书 → tools/prepare-target.ps1 生成审计 JAR → gradlew testDebugUnitTest -PskipNativeBuild → 失败自动开 issue（已有同名未关闭 issue 时不再开新的）。

普通 push 的 Android CI 不下载知乎 APK：`HookTest` 在 `ZHILIAO_TEST_DIR` 和 `targetapp/jars` 都没有审计 JAR 时整类跳过，只跑纯逻辑单测；目标解析的兼容性只由巡检用真实包验证。所以「本地绿、CI 也绿」不等于解析没坏——发版前要么跑一次 `prepare-target.ps1`，要么看巡检结果。

开箱即用时不用配任何变量：下载地址问知乎官网入口要，受信证书指纹写在 `tools/zhihu-trusted-certs.txt`（公开信息，随仓库走）。下面这些只在特殊情况下才用：

| 变量 | 必填 | 作用 |
| --- | --- | --- |
| `ZHIHU_APK_URL` | 否 | 手动指定下载地址（镜像站、回放旧版本）；不配就自动找最新包 |
| `ZHIHU_APK_CHANNEL` | 否 | 下载渠道，默认 `zhihuwap64`（arm64）；armv7 用 `zhihuwap` |
| `ZHIHU_CERT_SHA256` | 否 | 临时覆盖受信证书 |
| `ZHIHU_MIN_VERSION_CODE` | 否 | 低于这个 versionCode 判失败，防镜像回退旧包 |

本机试跑同一套校验：`.\tools\verify-zhihu-apk.ps1 -ApkPath .\targetapp\<包>.apk` —— 证书白名单默认读仓库里那份，不用手填。
小米渠道包 11.10.0/41012 与 11.11.0/41106 的证书指纹同为 bd8450557cb3965c051f1611d4286a5f029b909cae3de757ec152d0563c3f7fa —— 版本更新不动证书，所以钉死它不用每次跟着维护。

-PskipNativeBuild 跳过 CMake/NDK 整条链，只留 JVM 单测——CI 里没有 NDK，也不需要 native 产物。

### 已知卡点：新版 APK 转不出审计 JAR

dex2jar 2.4 在知乎 11.11.0/41106 上会在解析指令时崩掉（acceptInsn 抛出），那一版暂时进不了单测。试过的偏方是加 `-nc` 跳过代码体：出来的 JAR 能反射，但会让 16 个 Hook 集体报错，**在 11.10.0 上同样报错**，说明那是假故障，不能拿来判断兼容性。下一步换 enjarify 或 dex2jar 源码版。
## 命令行调试开关

调开关不用点界面：知乎进程里挂了一个广播接收器（`DebugBridge`），覆盖值存知乎本地 `zhiliao_debug`，读取顺序是"命令行覆盖 > 远端偏好"——不污染用户真正的设置，`clear` 即还原。

```powershell
# 列出当前覆盖项
adb shell am broadcast -a com.shatyuka.zhiliao.DEBUG --es cmd list
# 改一个开关（马上生效于下一次读取；装在 hook() 里的开关要配合 restart）
adb shell am broadcast -a com.shatyuka.zhiliao.DEBUG --es cmd set --es key switch_video --es value true
# 看某个键的最终值（含远端与覆盖）
adb shell am broadcast -a com.shatyuka.zhiliao.DEBUG --es cmd get --es key switch_video
# 清空覆盖
adb shell am broadcast -a com.shatyuka.zhiliao.DEBUG --es cmd clear
# 直接重启知乎进程（等价于 force-stop）
adb shell am broadcast -a com.shatyuka.zhiliao.DEBUG --es cmd restart
```

结果都打在 LSPosed 日志的 `[Zhiliao][调试]` 行里。接收器要求发送方持有 `android.permission.DUMP`，shell 与 root 天然满足，普通 App 发不进来，所以这扇门只对 adb 开。




