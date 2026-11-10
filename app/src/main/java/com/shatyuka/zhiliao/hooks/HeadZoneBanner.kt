package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field
import java.util.Collections

class HeadZoneBanner : IHook {
    override fun getName(): String {
        return "隐藏热榜顶部置顶"
    }

    override fun init(classLoader: ClassLoader) {
        val rank = classLoader.loadClass("com.zhihu.android.api.model.RankFeedList")
        rankFeedList = rank
        val headZoneField = rank.getDeclaredField("head_zone")
        headZoneField.setAccessible(true)
        head_zone = headZoneField
        feedsHotListFragment2 = classLoader.loadClass("com.zhihu.android.app.feed.ui.fragment.FeedsHotListFragment2")

        // Gj(RankFeedList) 优先消费新字段 headZones，取不到时只降级清空 head_zone
        try {
            val zonesField = rank.getDeclaredField("headZones")
            zonesField.setAccessible(true)
            headZones = zonesField
        } catch (e: NoSuchFieldException) {
            headZones = null
            XposedBridge.log("[Zhiliao] RankFeedList.headZones 不存在: $e")
        }
    }

    override fun hook() {
        XposedBridge.hookAllMethods(feedsHotListFragment2!!, "postRefreshSucceed", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!Helper.prefs!!.getBoolean("switch_mainswitch", false) || param.args.isEmpty() || !rankFeedList!!.isInstance(param.args[0])) {
                    return
                }
                head_zone!!.set(param.args[0], null)
                val zones = headZones
                if (zones != null) {
                    zones.set(param.args[0], Collections.emptyList<Any>())
                }
            }
        })
    }

    companion object {
        @JvmField
        var feedsHotListFragment2: Class<*>? = null

        @JvmField
        var rankFeedList: Class<*>? = null

        @JvmField
        var head_zone: Field? = null

        @JvmField
        var headZones: Field? = null
    }
}
