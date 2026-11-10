package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodReplacement
import com.shatyuka.zhiliao.xposed.XposedHelpers

class ThirdPartyLogin : IHook {
    override fun getName(): String {
        return "第三方登录"
    }

    override fun init(classLoader: ClassLoader) {
        try {
            UiConfigImpl = classLoader.loadClass("com.zhihu.android.account.provider.UiConfigImpl")
        } catch (_: ClassNotFoundException) {
            UiConfigImpl = classLoader.loadClass("com.zhihu.android.app.f\$6")
            obfuse = true
        }
    }

    override fun hook() {
        if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_thirdpartylogin", false)) {
            if (obfuse) {
                XposedHelpers.findAndHookMethod(UiConfigImpl!!, "b", XC_MethodReplacement.returnConstant(true))
                XposedHelpers.findAndHookMethod(UiConfigImpl!!, "c", XC_MethodReplacement.returnConstant(true))
                XposedHelpers.findAndHookMethod(UiConfigImpl!!, "d", XC_MethodReplacement.returnConstant(true))
            } else {
                XposedHelpers.findAndHookMethod(UiConfigImpl!!, "showQQ", XC_MethodReplacement.returnConstant(true))
                XposedHelpers.findAndHookMethod(UiConfigImpl!!, "showSina", XC_MethodReplacement.returnConstant(true))
                XposedHelpers.findAndHookMethod(UiConfigImpl!!, "showWeChat", XC_MethodReplacement.returnConstant(true))
            }
        }
    }

    companion object {
        @JvmField
        var UiConfigImpl: Class<*>? = null

        @JvmField
        var obfuse: Boolean = false
    }
}
