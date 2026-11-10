package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.DexKitFallback
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.ResolutionCache
import com.shatyuka.zhiliao.TargetResolver
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AutoRefresh : IHook {
    /**
     * 刷新来源名。11.10.0/11.11.0 的刷新入口 refresh(boolean, refreshType) 会用 refreshType 的
     * 一个无参 String 方法说明来源，已知取值：Pull / RefreshButton / Back / TabClick /
     * RefreshGuideButton（用户操作）、AppStart（冷启动自动刷新）、AutoRefreshBackground /
     * AutoRefreshDetailPage / AutoRefreshSearchPage / AutoRefreshOtherPage（回前台等）。
     *
     * 冷启动要分两步才算真的不刷新：
     * 1. 放行 AppStart 整条链路，让知乎把磁盘缓存渲染成上次那一屏；
     * 2. 在 postRefreshSucceed 丢掉随后的网络结果，同时掐掉这条网络结果写回本地缓存的那次写入。
     *
     * 第 2 步里"写回缓存"是以前一直漏掉的：请求本身在数据层执行，早于 postRefreshSucceed，
     * 于是屏幕看着没变，磁盘缓存却前进了一格，下次冷启动渲染的正是这一格。现在按
     * FeedLocalDataSource 的结构特征定位它的 void(FeedList) 写入方法，冷启动抑制期间直接跳过。
     *
     * 数据层的类名每个版本/渠道都不同（11.10.0 market 是 o0.x，小米是 b.b；
     * 11.11.0 官网是 q0.x，小米是 b.b），所以候选名单 + 结构校验 + DexKit 精确签名兜底，
     * 不写死任何一个名字。
     */
    override fun getName(): String {
        return FEATURE
    }

    override fun init(classLoader: ClassLoader) {
        val baseTabChildFragment = loadClass(classLoader, "com.zhihu.android.app.feed.ui.fragment.BaseTabChildFragment")
        if (baseTabChildFragment != null) {
            findReturnTopOrRefresh(baseTabChildFragment)
        }

        val feedAutoRefreshManager = loadClass(classLoader, "com.zhihu.android.app.feed.util.FeedAutoRefreshManager")
        if (feedAutoRefreshManager != null) {
            findTryRefreshMethod(feedAutoRefreshManager)
        } else {
            // 部分渠道包把类名也混淆了，按方法签名在整个包里找
            Helper.findClass(classLoader, "com.zhihu.android.app.feed.util.", 0, 1, this::findTryRefreshMethod)
        }

        val feedFragment = loadClass(classLoader, "com.zhihu.android.app.feed.ui2.feed.FeedFragment")
        if (feedFragment != null) {
            findRefreshEntries(feedFragment)
            findPostRefreshSucceed(feedFragment)
        }

        try {
            FeedList = classLoader.loadClass("com.zhihu.android.api.model.FeedList")
            FeedList_isCache = FeedList!!.getField("isCache")
        } catch (_: Throwable) {
        }

        findFeedCacheWriter(classLoader)

        val writer = feedCacheWrite
        XposedBridge.log(
            "[Zhiliao][自检] 禁止自动刷新 目标 返回顶部=" + (returnTopOrRefresh != null)
                + " 定时刷新=" + (tryRefresh != null)
                + " 刷新入口=" + refreshEntries.size
                + (if (refreshEntries.isEmpty()) "" else " 来源名=" + refreshEntries[0].typeName.name)
                + " 用户刷新判定=" + (userRefreshMethod != null)
                + " 丢弃网络结果=" + canSuppressNetworkResult()
                + " 缓存冻结=" + (
                    if (writer != null) {
                        writer.declaringClass.name + "#" + writer.name
                    } else if (dexKitScanStarted) {
                        "后台定位中"
                    } else {
                        "无"
                    }
                    ),
        )
    }

    override fun hook() {
        if (!isEnabled()) {
            return
        }
        if (returnTopOrRefresh != null) {
            XposedBridge.hookMethod(returnTopOrRefresh!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 返回 1 表示「已在顶部」，调用方不会再刷新
                    val flag = param.args[0]
                    if (flag is Boolean && flag) {
                        param.result = 1
                    }
                }
            })
        }
        if (tryRefresh != null) {
            XposedBridge.hookMethod(tryRefresh!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
        }
        if (postRefreshSucceed != null) {
            XposedBridge.hookMethod(postRefreshSucceed!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val list = if (param.args.size > 0) param.args[0] else null
                    if (dropRefreshResult(param.thisObject, list)) {
                        param.result = null
                    }
                }
            })
        }
        if (feedCacheWrite != null) {
            installCacheWriteHook(false)
        }
        for (entry in refreshEntries) {
            val captured = entry
            XposedBridge.hookMethod(captured.entry!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size != 2 || param.args[1] == null) {
                        return
                    }
                    val type = captured.typeName.invoke(param.args[1]) as String?
                    if (type == null) {
                        return
                    }
                    val userRefresh = isUserRefresh(param.args[1], type)
                    if (APP_START == type && canSuppressNetworkResult()) {
                        allowColdStart(param.thisObject, type)
                        return
                    }
                    if (APP_START == type || TAB_CLICK == type
                        || type.startsWith(AUTO_REFRESH_PREFIX) || !userRefresh
                    ) {
                        param.result = null
                        if (loggedTypes.add(type)) {
                            XposedBridge.log("[Zhiliao][禁止自动刷新] 来源 " + type + " → 已拦截")
                        }
                        return
                    }
                    if (loggedTypes.add(type)) {
                        XposedBridge.log("[Zhiliao][禁止自动刷新] 来源 " + type + " → 放行")
                    }
                    // 用户主动刷新（Pull / RefreshButton / Back / 刷新引导按钮）：撤销冷启动抑制，新内容照常进来
                    unfreeze(param.thisObject)
                }
            })
        }
    }

    /** 定时刷新：void(long, int, 回调接口, 抽象的参数类) */
    private fun findTryRefreshMethod(clazz: Class<*>): Boolean {
        for (method in clazz.declaredMethods) {
            val types = method.parameterTypes
            if (method.returnType === Void.TYPE && types.size == 4 &&
                types[0] === Long::class.javaPrimitiveType!! &&
                types[1] === Int::class.javaPrimitiveType!! &&
                (types[2].modifiers and Modifier.INTERFACE) != 0 &&
                (types[3].modifiers and Modifier.ABSTRACT) != 0
            ) {
                method.isAccessible = true
                tryRefresh = method
                return true
            }
        }
        return false
    }

    class RefreshEntry(val entry: Method, val typeName: Method)

    class Suppression {
        var active: Boolean = false
        var cacheSeen: Boolean = false
        var dropped: Int = 0
        var type: String? = null
    }

    companion object {
        private const val TAB_CLICK = "TabClick"
        private const val APP_START = "AppStart"
        private const val AUTO_REFRESH_PREFIX = "AutoRefresh"
        private const val FEATURE = "禁止自动刷新"

        /** DexKit 兜底扫到的类名，下次冷启动直接试，别为同一个渠道包反复扫 dex。 */
        private const val DEXKIT_CACHE_KEY = "autoRefresh.feedCacheWriter.dexkit"

        /** 已知的用户主动刷新：放行并撤销冷启动抑制。 */
        private val USER_REFRESH: Set<String> = HashSet(
            listOf("Pull", "RefreshButton", "Back", "RefreshGuideButton"),
        )

        @JvmField
        var returnTopOrRefresh: Method? = null

        @JvmField
        var tryRefresh: Method? = null

        @JvmField
        var postRefreshSucceed: Method? = null

        /** FeedLocalDataSource#void(FeedList)：网络结果写回推荐缓存 */
        @JvmField
        @Volatile
        var feedCacheWrite: Method? = null

        /** 主线程（名单命中）和后台线程（DexKit 兜底）可能同时想装这条 Hook，只允许一次生效 */
        private val cacheWriteHookInstalled = AtomicBoolean()

        /** 后台 dex 扫描已经放出去，自检日志据此说明"暂时没有"还是"还在找" */
        @Volatile
        private var dexKitScanStarted: Boolean = false

        @JvmField
        var FeedList: Class<*>? = null

        @JvmField
        var FeedList_isCache: Field? = null

        /** 刷新入口与其对应的来源名方法；不同重载的 refreshType 类型可能不同，两者按入口配对 */
        private var refreshEntries: Array<RefreshEntry> = emptyArray()

        /** refreshType 的"是否用户主动刷新"判断（Pull/TabClick/Back/RefreshButton 返回 true） */
        @JvmField
        var userRefreshMethod: Method? = null

        private val loggedTypes: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** 每个 feed fragment 一份：AppStart 放行后，用它判断缓存渲染完了没有 */
        private val suppressions: MutableMap<Any?, Suppression> = WeakHashMap()

        /** 缓存写入被我们主动放行的一次性列表（首装没缓存时，把放行的那份写回磁盘）。 */
        private val persistAllowed: MutableList<Any> = Collections.synchronizedList(ArrayList(4))

        @Volatile
        private var cacheBlockedLogged: Boolean = false

        @Volatile
        private var cacheBlockedCount: Int = 0

        @Volatile
        private var cacheRendered: Boolean = false

        /**
         * 能不能在放行冷启动刷新之后把网络结果丢掉。没有这个回调就只能退回直接拦截，
         * 代价是首页整片空白——所以单测把它当断言。
         */
        @JvmStatic
        fun canSuppressNetworkResult(): Boolean {
            return postRefreshSucceed != null
        }

        /** 能不能连缓存写入一起冻结。找不到就退化为旧行为（屏幕不刷新，但缓存会前进一格）。 */
        @JvmStatic
        fun canFreezeCache(): Boolean {
            return feedCacheWrite != null
        }

        /** 冷启动刷新放行前登记状态：缓存内容照渲染，之后的所有网络结果丢弃，直到用户自己刷新。 */
        private fun allowColdStart(fragment: Any?, type: String) {
            synchronized(suppressions) {
                var state = suppressions[fragment]
                if (state == null) {
                    state = Suppression()
                    suppressions[fragment] = state
                }
                state.active = true
                state.cacheSeen = false
                state.dropped = 0
                state.type = type
            }
            cacheRendered = false
            if (loggedTypes.add(type)) {
                XposedBridge.log(
                    "[Zhiliao][禁止自动刷新] 来源 " + type
                        + " → 放行（缓存照渲染，网络结果与缓存写入一起丢弃）",
                )
            }
        }

        private fun unfreeze(fragment: Any?) {
            synchronized(suppressions) {
                suppressions.remove(fragment)
            }
            cacheRendered = false
        }

        /**
         * 是否丢弃这次刷新结果。返回 false 表示放行：
         * 带 isCache 标记的缓存内容，或者抑制刚开始、还没渲染过任何内容（首装没有缓存时不能白屏）。
         * 抑制一旦生效就保持到用户主动刷新为止，网络结果晚到多久都进不来。
         */
        private fun dropRefreshResult(fragment: Any?, list: Any?): Boolean {
            synchronized(suppressions) {
                val state = suppressions[fragment]
                if (state == null || !state.active) {
                    return false
                }
                if (isCacheList(list)) {
                    state.cacheSeen = true
                    cacheRendered = true
                    XposedBridge.log("[Zhiliao][禁止自动刷新] 渲染 " + state.type + " 的缓存内容")
                    return false
                }
                if (!state.cacheSeen && !cacheRendered) {
                    state.cacheSeen = true
                    XposedBridge.log("[Zhiliao][禁止自动刷新] 没有缓存可渲染，放行第一份结果并写回缓存")
                    persistAllowed(list)
                    return false
                }
                state.dropped++
                if (state.dropped <= 3) {
                    XposedBridge.log(
                        "[Zhiliao][禁止自动刷新] 丢弃 " + state.type + " 的网络结果（第 "
                            + state.dropped + " 份）",
                    )
                }
                return true
            }
        }

        /** 缓存写入是否放行：缓存内容本身照写，用户刷新后的新内容照写，只有抑制期间新拉回来的网络结果要拦。 */
        private fun cacheWriteAllowed(list: Any?): Boolean {
            val feedListClass = FeedList
            if (list == null || feedListClass == null || !feedListClass.isInstance(list) || isCacheList(list)) {
                return true
            }
            if (!hasActiveSuppression()) {
                return true
            }
            synchronized(persistAllowed) {
                for (allowed in persistAllowed) {
                    if (allowed === list) {
                        return true
                    }
                }
            }
            return false
        }

        /**
         * 首装没有缓存时，放行的那份网络结果要主动写回缓存，否则下次冷启动没有内容可渲染。
         * 写入方法内部可能是异步的，用对象身份做一次性放行标记，不依赖线程。
         */
        private fun persistAllowed(list: Any?) {
            val feedListClass = FeedList
            if (list == null || feedListClass == null || !feedListClass.isInstance(list) || feedCacheWrite == null) {
                return
            }
            synchronized(persistAllowed) {
                if (persistAllowed.size > 4) {
                    persistAllowed.clear()
                }
                persistAllowed.add(list)
            }
            val instance = cacheWriterInstance() ?: return
            Thread({
                if (!hasActiveSuppression()) {
                    return@Thread
                }
                try {
                    feedCacheWrite!!.invoke(instance, list)
                } catch (e: Throwable) {
                    XposedBridge.log("[Zhiliao][禁止自动刷新] 写回缓存失败: " + e)
                }
            }, "ZhiliaoFeedCachePersist").start()
        }

        private fun cacheWriterInstance(): Any? {
            return try {
                val constructor = feedCacheWrite!!.declaringClass.getDeclaredConstructor()
                constructor.isAccessible = true
                constructor.newInstance()
            } catch (_: Throwable) {
                null
            }
        }

        private fun hasActiveSuppression(): Boolean {
            synchronized(suppressions) {
                for (state in suppressions.values) {
                    if (state.active) {
                        return true
                    }
                }
            }
            return false
        }

        /** 来源名已知时按名单走，未知来源用知乎自己的"是否用户触发"判断，避免新来源被当成主动刷新放行。 */
        private fun isUserRefresh(refreshType: Any?, type: String): Boolean {
            if (USER_REFRESH.contains(type)) {
                return true
            }
            if (TAB_CLICK == type || type.startsWith(AUTO_REFRESH_PREFIX) || APP_START == type) {
                return false
            }
            val method = userRefreshMethod
            if (method != null) {
                try {
                    return java.lang.Boolean.TRUE.equals(method.invoke(refreshType))
                } catch (_: Throwable) {
                }
            }
            return true
        }

        private fun isCacheList(list: Any?): Boolean {
            val feedListClass = FeedList
            val cacheField = FeedList_isCache
            if (list == null || feedListClass == null || cacheField == null || !feedListClass.isInstance(list)) {
                return false
            }
            return try {
                cacheField.getBoolean(list)
            } catch (_: Throwable) {
                false
            }
        }

        private fun loadClass(classLoader: ClassLoader, name: String): Class<*>? {
            return try {
                classLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
                null
            } catch (_: LinkageError) {
                null
            }
        }

        /**
         * 定位 FeedLocalDataSource 的 void(FeedList) 写入方法。
         *
         * 主线程只做便宜的那部分：名单（q0.x / b.b / o0.x）和上次 DexKit 的结果，都是一次
         * Class.forName + 结构校验。真正扫 dex 的兜底放后台线程——DexKit 要先加载原生库再遍历
         * 整个 APK，换渠道包、名单落空的那次冷启动原来就卡在这里。
         */
        private fun findFeedCacheWriter(classLoader: ClassLoader) {
            if (FeedList == null) {
                return
            }
            try {
                val clazz = TargetResolver.findClassByQualifiedCandidates(
                    classLoader,
                    "autoRefresh.feedCacheWriter",
                ) { candidate -> looksLikeLocalFeedStore(candidate) }
                if (clazz != null) {
                    feedCacheWrite = findVoidFeedListMethod(clazz)
                }
                if (feedCacheWrite == null) {
                    reuseDexKitResult(classLoader)
                }
            } catch (e: Throwable) {
                XposedBridge.log("[Zhiliao][禁止自动刷新] 缓存写入定位失败: " + e)
            }
            if (feedCacheWrite == null && isEnabled()) {
                startDexKitScan(classLoader)
            }
        }

        /** 上次 DexKit 扫到的类名；指纹（知乎版本 + 名单 revision + APK）变了 ResolutionCache 会整份作废。 */
        private fun reuseDexKitResult(classLoader: ClassLoader) {
            val cached = ResolutionCache.peek(DEXKIT_CACHE_KEY)
            if (cached == null || cached.isEmpty()) {
                return
            }
            try {
                val clazz = Class.forName(cached, false, classLoader)
                if (!looksLikeLocalFeedStore(clazz)) {
                    return
                }
                feedCacheWrite = findVoidFeedListMethod(clazz)
                if (feedCacheWrite != null) {
                    XposedBridge.log("[Zhiliao][禁止自动刷新] 缓存写入沿用上次兜底定位：" + cached)
                }
            } catch (_: Throwable) {
            }
        }

        /** 后台扫 dex，命中后补装 Hook；扫描失败就维持"屏幕不刷新但不冻结缓存"的降级状态。 */
        private fun startDexKitScan(classLoader: ClassLoader) {
            dexKitScanStarted = true
            val thread = Thread({
                try {
                    val unique = scanCacheWriterBySignature(classLoader)
                    if (unique == null) {
                        return@Thread
                    }
                    feedCacheWrite = unique
                    ResolutionCache.remember(DEXKIT_CACHE_KEY, unique.declaringClass.name)
                    XposedBridge.log(
                        "[Zhiliao][禁止自动刷新] 缓存写入由 DexKit 兜底定位: "
                            + unique.declaringClass.name + "#" + unique.name,
                    )
                    installCacheWriteHook(true)
                } catch (e: Throwable) {
                    XposedBridge.log("[Zhiliao][禁止自动刷新] 后台兜底定位失败: " + e)
                }
            }, "ZhiliaoAutoRefreshResolve")
            thread.isDaemon = true
            thread.start()
        }

        /**
         * 按"参数恰好是 FeedList、返回 void、限定 feed 包"扫一遍，结果必须过结构校验且唯一。
         * 候选不唯一时宁可不冻结缓存，也不赌其中一个。
         */
        private fun scanCacheWriterBySignature(classLoader: ClassLoader): Method? {
            val candidates = DexKitFallback.findMethodsBySignature(
                arrayOf("com.zhihu.android.app.feed"),
                arrayOf<Class<*>>(FeedList!!),
                Void.TYPE,
                64,
                classLoader,
            )
            var unique: Method? = null
            for (method in candidates) {
                if (!looksLikeLocalFeedStore(method.declaringClass)) {
                    continue
                }
                if (unique != null) {
                    XposedBridge.log("[Zhiliao][禁止自动刷新] 缓存写入候选不唯一，放弃冻结缓存")
                    return null
                }
                unique = method
            }
            return unique
        }

        /** 名单命中的主线程与 DexKit 的后台线程都会走到这里；CAS 保证只装一次。 */
        private fun installCacheWriteHook(late: Boolean) {
            val target = feedCacheWrite
            if (target == null || !isEnabled()) {
                return
            }
            if (!cacheWriteHookInstalled.compareAndSet(false, true)) {
                return
            }
            try {
                XposedBridge.hookMethod(
                    target,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val list = if (param.args.size > 0) param.args[0] else null
                            if (cacheWriteAllowed(list)) {
                                if (isCacheList(list)) {
                                    cacheRendered = true
                                }
                                return
                            }
                            param.result = null
                            cacheBlockedCount++
                            if (!cacheBlockedLogged) {
                                cacheBlockedLogged = true
                                XposedBridge.log(
                                    "[Zhiliao][禁止自动刷新] 已拦截网络结果写回缓存"
                                        + "（" + target.declaringClass.name + "#"
                                        + target.name + "）",
                                )
                            }
                        }
                    },
                    FEATURE,
                )
                if (late) {
                    XposedBridge.log(
                        "[Zhiliao][自检] 禁止自动刷新 缓存写入 Hook 已补装："
                            + target.declaringClass.name + "#" + target.name,
                    )
                }
            } catch (e: Throwable) {
                cacheWriteHookInstalled.set(false)
                XposedBridge.log("[Zhiliao][禁止自动刷新] 缓存写入 Hook 安装失败: " + e)
            }
        }

        /** 开关没开就不该替它扫 dex——换渠道包的那次冷启动代价，不该由不用这个功能的人付。 */
        private fun isEnabled(): Boolean {
            val prefs = Helper.prefs ?: return false
            return prefs.getBoolean("switch_mainswitch", false)
                && prefs.getBoolean("switch_autorefresh", false)
        }

        private fun findVoidFeedListMethod(clazz: Class<*>): Method? {
            for (method in clazz.declaredMethods) {
                if (method.returnType === Void.TYPE && method.parameterCount == 1
                    && method.parameterTypes[0] === FeedList
                ) {
                    method.isAccessible = true
                    return method
                }
            }
            return null
        }

        /**
         * FeedLocalDataSource 的结构指纹，刻意收得很窄：
         * - 一个 void(FeedList) 写入方法；
         * - 一个 void(回调接口) 读取方法，回调接口恰好有 onSuccess(Object) 和 onError(Throwable)；
         * - 一个无参 void（删除缓存）；
         * - 一个 String 字段（缓存键）。
         */
        private fun looksLikeLocalFeedStore(clazz: Class<*>?): Boolean {
            if (clazz == null || FeedList == null) {
                return false
            }
            try {
                if (findVoidFeedListMethod(clazz) == null) {
                    return false
                }
                var callback = false
                var noArg = false
                for (method in clazz.declaredMethods) {
                    if (method.returnType !== Void.TYPE) {
                        continue
                    }
                    if (method.parameterCount == 0) {
                        noArg = true
                    } else if (method.parameterCount == 1 && method.parameterTypes[0].isInterface
                        && looksLikeCacheCallback(method.parameterTypes[0])
                    ) {
                        callback = true
                    }
                }
                if (!callback || !noArg) {
                    return false
                }
                for (field in clazz.declaredFields) {
                    if (field.type === String::class.java) {
                        return true
                    }
                }
            } catch (_: Throwable) {
            }
            return false
        }

        private fun looksLikeCacheCallback(type: Class<*>): Boolean {
            val methods = type.methods
            if (methods.size != 2) {
                return false
            }
            var success = false
            var failure = false
            for (method in methods) {
                if (method.returnType !== Void.TYPE || method.parameterCount != 1) {
                    continue
                }
                val parameter = method.parameterTypes[0]
                if (parameter === Any::class.java) {
                    success = true
                } else if (parameter === Throwable::class.java) {
                    failure = true
                }
            }
            return success && failure
        }

        /** 返回顶部或刷新：public int xxx(boolean)，老版本叫 returnTopOrRefresh，新版本被混淆成 a */
        private fun findReturnTopOrRefresh(clazz: Class<*>) {
            // 名单优先（assets/candidates-v1.json，云端可追加），验证器兜底防误绑
            val byName = TargetResolver.findMethodByCandidates(
                clazz,
                "autoRefresh.returnTopOrRefresh",
                true,
            ) { method ->
                method.returnType === Int::class.javaPrimitiveType!!
                    && method.parameterCount == 1
                    && method.parameterTypes[0] === Boolean::class.javaPrimitiveType!!
            }
            if (byName != null) {
                returnTopOrRefresh = byName
                return
            }
            // 无名单（或名单落空）时的结构化解析：public int xxx(boolean)
            for (method in clazz.methods) {
                if (method.returnType === Int::class.javaPrimitiveType!! && method.parameterCount == 1
                    && method.parameterTypes[0] === Boolean::class.javaPrimitiveType!!
                ) {
                    returnTopOrRefresh = method
                    return
                }
            }
        }

        /** 刷新入口：public void xxx(boolean, refreshType)，refreshType 是带来源名的抽象基类 */
        private fun findRefreshEntries(feedFragment: Class<*>) {
            val entries = ArrayList<RefreshEntry>()
            for (method in feedFragment.declaredMethods) {
                if (method.returnType !== Void.TYPE || method.parameterCount != 2) {
                    continue
                }
                val types = method.parameterTypes
                if (types[0] !== Boolean::class.javaPrimitiveType!! || types[1].isInterface || types[1] === Any::class.java) {
                    continue
                }
                val name = findTypeName(types[1]) ?: continue
                if (userRefreshMethod == null) {
                    userRefreshMethod = findUserRefreshMethod(types[1])
                }
                entries.add(RefreshEntry(method, name))
            }
            refreshEntries = entries.toTypedArray()
        }

        /** 刷新成功回调：void xxx(ZHObjectList)，缓存内容和网络内容都从这里进列表 */
        private fun findPostRefreshSucceed(feedFragment: Class<*>) {
            val byName = TargetResolver.findMethodByCandidates(
                feedFragment,
                "autoRefresh.postRefreshSucceed",
                false,
            ) { method ->
                method.returnType === Void.TYPE && method.parameterCount == 1
            }
            if (byName != null) {
                postRefreshSucceed = byName
                return
            }
            for (method in feedFragment.declaredMethods) {
                if (method.returnType === Void.TYPE && method.parameterCount == 1
                    && "postRefreshSucceed" == method.name
                ) {
                    postRefreshSucceed = method
                    return
                }
            }
        }

        private fun findTypeName(refreshType: Class<*>): Method? {
            for (method in refreshType.methods) {
                if (method.parameterCount == 0 && method.returnType === String::class.java
                    && Modifier.isPublic(method.modifiers) && "toString" != method.name
                ) {
                    return method
                }
            }
            return null
        }

        /** refreshType 的 public boolean xxx()：Pull/TabClick/Back/RefreshButton 为 true */
        private fun findUserRefreshMethod(refreshType: Class<*>): Method? {
            for (method in refreshType.methods) {
                if (method.parameterCount == 0 && method.returnType === Boolean::class.javaPrimitiveType!!
                    && Modifier.isPublic(method.modifiers)
                    && method.declaringClass !== Any::class.java
                ) {
                    return method
                }
            }
            return null
        }
    }
}
