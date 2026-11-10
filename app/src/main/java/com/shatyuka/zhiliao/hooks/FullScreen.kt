package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Method

class FullScreen : IHook {
    override fun getName(): String {
        return "禁止进入全屏模式"
    }

    override fun init(classLoader: ClassLoader) {
        ClearScreenToolBarView_enterClearScreen = findEnterClearScreen(
            classLoader,
            "com.zhihu.android.feature.short_container_feature.ui.widget.toolbar.clearscreen.ClearScreenToolBarView",
        )
        UnifyClearScreenToolBarView_enterClearScreen = findEnterClearScreen(
            classLoader,
            "com.zhihu.android.feature.short_container_feature.ui.widget.toolbar.clearscreen.UnifyClearScreenToolBarView",
        )
        MixClearScreenToolBarView_enterClearScreen = findEnterClearScreen(
            classLoader,
            "com.zhihu.android.mix.mixshort.clearscreen.ClearScreenToolBarView",
        )
        AnswerClearScreenToolBarView_enterClearScreen = findEnterClearScreen(
            classLoader,
            "com.zhihu.android.answer.module.mixshort.clearscreen.AnswerClearScreenToolBarView",
        )
        if (ClearScreenToolBarView_enterClearScreen == null && UnifyClearScreenToolBarView_enterClearScreen == null
            && MixClearScreenToolBarView_enterClearScreen == null && AnswerClearScreenToolBarView_enterClearScreen == null
        ) {
            throw ClassNotFoundException("com.zhihu.android.*.clearscreen.ClearScreenToolBarView.enterClearScreen")
        }
    }

    override fun hook() {
        val blockEnterClearScreen = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_fullscreen", false)) {
                    param.result = null
                }
            }
        }
        val targets = arrayOf(
            ClearScreenToolBarView_enterClearScreen,
            UnifyClearScreenToolBarView_enterClearScreen,
            MixClearScreenToolBarView_enterClearScreen,
            AnswerClearScreenToolBarView_enterClearScreen,
        )
        for (target in targets) {
            if (target != null) {
                XposedBridge.hookMethod(target, blockEnterClearScreen)
            }
        }
    }

    companion object {
        @JvmField
        var ClearScreenToolBarView_enterClearScreen: Method? = null

        @JvmField
        var UnifyClearScreenToolBarView_enterClearScreen: Method? = null

        @JvmField
        var MixClearScreenToolBarView_enterClearScreen: Method? = null

        @JvmField
        var AnswerClearScreenToolBarView_enterClearScreen: Method? = null

        private fun findEnterClearScreen(classLoader: ClassLoader, className: String): Method? {
            try {
                return classLoader.loadClass(className).getDeclaredMethod("enterClearScreen")
            } catch (ignored: ClassNotFoundException) {
                return null
            } catch (ignored: NoSuchMethodException) {
                return null
            }
        }
    }
}
