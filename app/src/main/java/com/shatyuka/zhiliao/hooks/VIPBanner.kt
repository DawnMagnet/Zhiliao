package com.shatyuka.zhiliao.hooks

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.R
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodReplacement
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Method

class VIPBanner : IHook {
    override fun getName(): String {
        return "隐藏会员卡片"
    }

    override fun init(classLoader: ClassLoader) {
        // 回退类 premium.view.VipEntranceView 的入口是 public init(Context)，initView(Context) 只是其私有实现
        VipEntranceView = classLoader.loadClass("com.zhihu.android.premium.view.VipEntranceView")
        initView_new = VipEntranceView!!.getDeclaredMethod("init", Context::class.java)
        try {
            initView = VipEntranceView!!.getDeclaredMethod("initView", Context::class.java)
            initView!!.isAccessible = true
        } catch (_: NoSuchMethodException) {
            initView = null
        }
        try {
            onClick = VipEntranceView!!.getDeclaredMethod("onClick", View::class.java)
        } catch (_: NoSuchMethodException) {
            onClick = null
        }
        try {
            MoreVipData = classLoader.loadClass("com.zhihu.android.api.MoreVipData")
            setData = Helper.getMethodByParameterTypes(VipEntranceView, MoreVipData!!, String::class.java, Boolean::class.javaPrimitiveType!!)
        } catch (_: ClassNotFoundException) {
            MoreVipData = null
        }
    }

    override fun hook() {
        if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_vipbanner", false)) {
            val replaceVipEntranceLayout = object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                    val inflaterContext = param.args[0]
                    val parent = param.thisObject
                    if (inflaterContext is Context && parent is ViewGroup) {
                        val layoutVipEntranceViewNew = Helper.modRes!!.getLayout(R.layout.layout_vipentranceview_new)
                        LayoutInflater.from(inflaterContext).inflate(layoutVipEntranceViewNew, parent)
                    }
                    return null
                }
            }
            XposedBridge.hookMethod(initView_new!!, replaceVipEntranceLayout)
            if (initView != null) {
                XposedBridge.hookMethod(initView!!, replaceVipEntranceLayout)
            }
            if (setData != null) {
                XposedBridge.hookMethod(setData!!, XC_MethodReplacement.returnConstant(null))
            }
            if (onClick != null) {
                XposedBridge.hookMethod(onClick!!, XC_MethodReplacement.returnConstant(null))
            }
            // 会员卡片已迁到「我的」页，MineTabFragment 仍消费 isLegal
            if (MoreVipData != null) {
                XposedBridge.hookAllMethods(MoreVipData!!, "isLegal", XC_MethodReplacement.returnConstant(java.lang.Boolean.FALSE))
            }
        }
    }

    companion object {
        @JvmField
        var VipEntranceView: Class<*>? = null

        @JvmField
        var MoreVipData: Class<*>? = null

        @JvmField
        var initView: Method? = null

        @JvmField
        var initView_new: Method? = null

        @JvmField
        var setData: Method? = null

        @JvmField
        var onClick: Method? = null
    }
}
