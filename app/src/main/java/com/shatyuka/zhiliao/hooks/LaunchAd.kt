package com.shatyuka.zhiliao.hooks

import android.content.Context
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.TargetResolver
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Method

class LaunchAd : IHook {
    override fun getName(): String {
        return "去启动页广告"
    }

    override fun init(classLoader: ClassLoader) {
        AdNetworkManager = Helper.findClass(classLoader, "com.zhihu.android.sdk.launchad.") { clazz ->
            clazz.declaredFields.any { field -> field.type.name.startsWith("okhttp3.") }
        }
        if (AdNetworkManager == null) {
            throw ClassNotFoundException("com.zhihu.android.sdk.launchad.AdNetworkManager")
        }
        val launchAdInterface = classLoader.loadClass("com.zhihu.android.ad.LaunchAdInterface")
        val launchAdHelper = Helper.findClass(classLoader, "com.zhihu.android.app.util.", 0, 32) { clazz ->
            launchAdInterface.isAssignableFrom(clazz) && clazz !== launchAdInterface
        }
        if (launchAdHelper == null) {
            throw ClassNotFoundException("com.zhihu.android.app.util.LaunchAdHelper implements LaunchAdInterface")
        }
        isShowLaunchAd = launchAdHelper.getMethod("isShowLaunchAd")
        showLaunchAd = findShowLaunchAd(launchAdHelper)
        requestLaunchAd = TargetResolver.requireMethod(
            AdNetworkManager,
            false,
            0,
            "requestLaunchAd(int,long,long,String)",
        ) { method ->
            method.returnType === String::class.java &&
                java.util.Arrays.equals(
                    method.parameterTypes,
                    arrayOf<Class<*>>(
                        Int::class.javaPrimitiveType!!,
                        Long::class.javaPrimitiveType!!,
                        Long::class.javaPrimitiveType!!,
                        String::class.java,
                    ),
                )
        }
        combineCheckData = resolveCombineCheckData(classLoader)
        isShowCombineAd = resolveStatusMethod(classLoader, AD_LAUNCH_STATUS, "isShowCombineAd")
        isSoSoCombineAd = resolveStatusMethod(classLoader, AD_LAUNCH_STATUS, "getSoSoCombineAdShow")
        existAdSoSoData = resolveStatusMethod(classLoader, AD_SOSO_SERVER, "existAdFeedSoSoData")
    }

    override fun hook() {
        XposedBridge.hookMethod(isShowLaunchAd!!, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (enabled()) {
                    param.result = false
                }
            }
        })
        XposedBridge.hookMethod(requestLaunchAd!!, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (enabled()) {
                    param.result = ""
                }
            }
        })
        // 唤醒开屏（推送 / 外链把知乎叫起来时弹的那支）走的是这条，它不看 isShowLaunchAd。
        if (showLaunchAd != null) {
            XposedBridge.hookMethod(showLaunchAd!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled()) {
                        param.result = null
                    }
                }
            })
        }
        // 联投开屏：进首页那一刻，AdCombineWatcher 先问「有没有素材」（超级首映状态位 / 搜搜联动数据），
        // 再问自己的 checkData()，全过了才路由到开屏页、把整屏视图盖在信息流上。
        if (combineCheckData != null) {
            XposedBridge.hookMethod(combineCheckData!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled()) {
                        param.result = false
                    }
                }
            })
        }
        // 三个素材判断一起答"没有"：单堵 checkData 的话，搜搜联动那个标志没人消费，每回一次首页都要再判一次
        // 并发一条错误埋点；浮层视频广告也会因为等"联投动画播完"把监听挂在那儿再也等不到。
        for (flag in arrayOf(isShowCombineAd, isSoSoCombineAd, existAdSoSoData)) {
            if (flag == null) {
                continue
            }
            XposedBridge.hookMethod(flag!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled()) {
                        param.result = false
                    }
                }
            })
        }
        clearCachedLaunchAd()
    }

    companion object {
        @JvmField
        var AdNetworkManager: Class<*>? = null

        @JvmField
        var isShowLaunchAd: Method? = null

        @JvmField
        var requestLaunchAd: Method? = null

        @JvmField
        var showLaunchAd: Method? = null

        /**
         * 联投开屏（超级首映 / 搜搜联动）：冷启动进首页后把广告视图直接盖在信息流上，和 sdk.launchad 是两条链路。
         * 代码 11.10 就有，11.13 起才开始投放；旧基线同样会装上这两条，那两版没做真机回归。
         */
        @JvmField
        var combineCheckData: Method? = null

        @JvmField
        var isShowCombineAd: Method? = null

        @JvmField
        var isSoSoCombineAd: Method? = null

        @JvmField
        var existAdSoSoData: Method? = null

        private const val COMBINE_WATCHER = "com.zhihu.android.app.ui.fragment.ad.combine.AdCombineWatcher"
        private const val AD_LAUNCH_STATUS = "com.zhihu.android.ad.launch.AdLaunchStatus"
        private const val AD_SOSO_SERVER = "com.zhihu.android.ad.launch.AdSoSoServer"

        /** 开屏广告的本地缓存库（LaunchAdRoomFactory#roomDbName），放的是预加载回来的广告与资源记录。 */
        private const val CACHE_DB = "room_launch_ad"
        private const val LOCAL_PREFS = "zhiliao_local"
        private const val CACHE_CLEARED_KEY = "launch_ad_cache_cleared"

        /** 接口方法不会被混淆，但老版本可能有别的实现类，找不到就跳过这一条，别连累整个功能。 */
        private fun findShowLaunchAd(helper: Class<*>): Method? {
            return try {
                helper.getMethod("showLaunchAd", String::class.java)
            } catch (_: NoSuchMethodException) {
                XposedBridge.log("[Zhiliao][去启动页广告] 没找到 showLaunchAd(String)，唤醒开屏这条不装")
                null
            }
        }

        /**
         * 联投开屏的数据闸门。类上有 @Keep，名字没被混淆；方法名跟着类保留，签名再验一遍防同名顶替。
         * 11.13 之前没有这条链路，解析不到就是"本来就没有"，不打日志。
         */
        private fun resolveCombineCheckData(classLoader: ClassLoader): Method? {
            return try {
                val watcher = classLoader.loadClass(COMBINE_WATCHER)
                val method = TargetResolver.findMethod(watcher, false, 0) { candidate ->
                    "checkData" == candidate.name &&
                        candidate.parameterTypes.isEmpty() &&
                        candidate.returnType === Boolean::class.javaPrimitiveType!!
                }
                if (method == null) {
                    XposedBridge.log("[Zhiliao][去启动页广告] " + COMBINE_WATCHER + " 里没有 checkData()，联投开屏这条不装")
                }
                method
            } catch (_: ClassNotFoundException) {
                null
            } catch (e: Throwable) {
                XposedBridge.log("[Zhiliao][去启动页广告] 联投开屏监视器解析失败: " + e)
                null
            }
        }

        /** 公开的素材状态位 / 素材判断，方法名不会被混淆；类缺失（更老的版本）或方法缺失都只跳过这一条。 */
        private fun resolveStatusMethod(classLoader: ClassLoader, className: String, methodName: String): Method? {
            return try {
                classLoader.loadClass(className).getMethod(methodName)
            } catch (_: ClassNotFoundException) {
                null
            } catch (e: Throwable) {
                XposedBridge.log("[Zhiliao][去启动页广告] " + className + "#" + methodName + " 解析失败: " + e)
                null
            }
        }

        /** 单测用：联投开屏四条目标解析成了什么（「类名#方法名」），没解析到为 null。 */
        @JvmStatic
        fun combineTargets(): Array<String?> {
            return arrayOf(
                describe(combineCheckData),
                describe(isShowCombineAd),
                describe(isSoSoCombineAd),
                describe(existAdSoSoData),
            )
        }

        private fun describe(method: Method?): String? {
            return if (method == null) null else method.declaringClass.name + "#" + method.name
        }

        private fun enabled(): Boolean {
            return Helper.prefs!!.getBoolean("switch_mainswitch", false)
                && Helper.prefs!!.getBoolean("switch_launchad", true)
        }

        /**
         * 上面这些 Hook 只管这次启动的请求；装模块之前就已经落库的那支广告还在，得一起清掉。
         * 只在第一次清：清完之后请求已经被堵死，库里不会再长出新的开屏广告，没必要每次冷启动都去动
         * 知乎自己的数据库（知乎有多个进程，反复删容易撞上别人正开着的连接）。
         */
        private fun clearCachedLaunchAd() {
            if (!enabled()) {
                return
            }
            try {
                val context = Helper.context ?: return
                val local = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                if (local.getBoolean(CACHE_CLEARED_KEY, false)) {
                    return
                }
                val database: File? = context.getDatabasePath(CACHE_DB)
                if (database == null || !database.exists()) {
                    local.edit().putBoolean(CACHE_CLEARED_KEY, true).apply()
                    return
                }
                val deleted = context.deleteDatabase(CACHE_DB)
                local.edit().putBoolean(CACHE_CLEARED_KEY, deleted).apply()
                XposedBridge.log(
                    if (deleted) {
                        "[Zhiliao][去启动页广告] 已清空开屏缓存 " + CACHE_DB
                    } else {
                        "[Zhiliao][去启动页广告] 清空开屏缓存失败，下次启动再试 " + CACHE_DB
                    },
                )
            } catch (e: Throwable) {
                XposedBridge.log("[Zhiliao][去启动页广告] 清空开屏缓存异常: " + e)
            }
        }
    }
}
