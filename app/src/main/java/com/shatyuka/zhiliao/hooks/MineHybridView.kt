package com.shatyuka.zhiliao.hooks

import android.view.View
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field

class MineHybridView : IHook {
    override fun getName(): String {
        return "隐藏「我的」底部混合卡片"
    }

    override fun init(classLoader: ClassLoader) {
        try {
            val fragment = classLoader.loadClass("com.zhihu.android.app.ui.fragment.more.mine.MineTabFragment")
            val hybrid = classLoader.loadClass("com.zhihu.android.app.ui.fragment.more.mine.widget.MineHybridView")
            mineTabFragment = fragment
            mineHybridView = hybrid
            mineHybridViewField = Helper.findFieldByType(fragment!!, hybrid)
        } catch (_: ClassNotFoundException) {
        }
    }

    override fun hook() {
        if (mineHybridViewField != null) {
            XposedBridge.hookAllMethods(mineTabFragment!!, "onCreateView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_minehybrid", false)) {
                        val hybridView = mineHybridViewField!!.get(param.thisObject) as View?
                        if (hybridView != null) {
                            hybridView.setVisibility(View.GONE)
                        }
                    }
                }
            })
        }
    }

    companion object {
        @JvmField
        var mineTabFragment: Class<*>? = null

        @JvmField
        var mineHybridView: Class<*>? = null

        @JvmField
        var mineHybridViewField: Field? = null
    }
}
