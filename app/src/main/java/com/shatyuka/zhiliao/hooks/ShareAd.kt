package com.shatyuka.zhiliao.hooks

import android.view.View
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Method

class ShareAd : IHook {
    override fun getName(): String {
        return "去分享广告"
    }

    override fun init(classLoader: ClassLoader) {
        val shareFragment = classLoader.loadClass("com.zhihu.android.library.sharecore.fragment.ShareFragment")
        if (shareFragment != null) {
            val methods = shareFragment.declaredMethods
            for (method in methods) {
                val types = method.parameterTypes
                if (types.size == 1 && types[0] === View::class.java) {
                    showShareAd = method
                    break
                }
            }
        }
        if (showShareAd == null) {
            throw NoSuchMethodException("com.zhihu.android.library.sharecore.fragment.ShareFragment.showShareAd()")
        }
    }

    override fun hook() {
        XposedBridge.hookMethod(showShareAd!!, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_sharead", true)) {
                    param.result = null
                }
            }
        })
    }

    companion object {
        @JvmField
        var showShareAd: Method? = null
    }
}
