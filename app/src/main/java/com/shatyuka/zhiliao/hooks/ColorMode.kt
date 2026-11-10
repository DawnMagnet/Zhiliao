package com.shatyuka.zhiliao.hooks

import android.view.Window
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedHelpers

class ColorMode : IHook {
    override fun getName(): String {
        return "禁止切换色彩模式"
    }

    override fun init(classLoader: ClassLoader) {
    }

    override fun hook() {
        XposedHelpers.findAndHookMethod(
            Window::class.java,
            "setColorMode",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_colormode", false)) {
                        param.result = null
                    }
                }
            },
        )
    }
}
