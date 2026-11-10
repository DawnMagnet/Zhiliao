package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge

class HotBanner : IHook {
    override fun getName(): String {
        return "隐藏热点通知"
    }

    override fun init(classLoader: ClassLoader) {
        InternalNotificationManager = classLoader.loadClass("com.zhihu.android.app.feed.notification.InternalNotificationManager")
    }

    override fun hook() {
        XposedBridge.hookAllMethods(InternalNotificationManager!!, "fetchFloatNotification", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_hotbanner", false)) {
                    param.result = null
                }
            }
        })
    }

    companion object {
        @JvmField
        var InternalNotificationManager: Class<*>? = null
    }
}
