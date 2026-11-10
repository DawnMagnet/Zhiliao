package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.TargetResolver
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XC_MethodReplacement
import com.shatyuka.zhiliao.xposed.XposedBridge
import com.shatyuka.zhiliao.xposed.XposedHelpers

class NavRes : IHook {
    override fun getName(): String {
        return "导航栏样式"
    }

    override fun init(classLoader: ClassLoader) {
        // 混淆名随渠道包变（a/b/e 都见过）：顺序交给候选名单，结构验证兜底
        BottomNav = TargetResolver.findClassByQualifiedCandidates(classLoader, "navRes.bottomNav") { clazz ->
            looksLikeBottomNav(clazz)
        }
        if (BottomNav == null) {
            BottomNav = loadLegacyBottomNav(classLoader)
        }
        FeedConfigManager = Helper.findClass(classLoader, "com.zhihu.android.", 0, 2) { candidate ->
            val types = candidate.constructors[0].parameterTypes
            types.size == 6 && types[0] === String::class.java && types[1] === String::class.java &&
                types[2] === Long::class.javaPrimitiveType!! && types[3] === Long::class.javaPrimitiveType!! &&
                types[4] === types[5]
        }
    }

    override fun hook() {
        if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_navres", false)) {
            XposedHelpers.findAndHookMethod(BottomNav!!, "b", XC_MethodReplacement.returnConstant(null))
            if (FeedConfigManager != null) {
                XposedBridge.hookAllConstructors(FeedConfigManager!!, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[3] = 0
                    }
                })
            }
        }
    }

    companion object {
        @JvmField
        var BottomNav: Class<*>? = null

        @JvmField
        var FeedConfigManager: Class<*>? = null

        private fun looksLikeBottomNav(clazz: Class<*>): Boolean {
            return try {
                clazz.getDeclaredField("a").type === Boolean::class.javaPrimitiveType!!
            } catch (_: Throwable) {
                false
            }
        }

        private fun loadLegacyBottomNav(classLoader: ClassLoader): Class<*> {
            var last: Throwable? = null
            for (name in arrayOf(
                "com.zhihu.android.bottomnav.a",
                "com.zhihu.android.bottomnav.b",
                "com.zhihu.android.bottomnav.e",
            )) {
                try {
                    val clazz = classLoader.loadClass(name)
                    if (looksLikeBottomNav(clazz)) {
                        return clazz
                    }
                } catch (e: Throwable) {
                    last = e
                }
            }
            throw last ?: ClassNotFoundException("com.zhihu.android.bottomnav.BottomNav")
        }
    }
}
