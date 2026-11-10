package com.shatyuka.zhiliao.hooks

import android.view.View
import android.view.ViewGroup
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XC_MethodReplacement
import com.shatyuka.zhiliao.xposed.XposedBridge
import com.shatyuka.zhiliao.xposed.XposedHelpers
import java.lang.reflect.Method

class RedDot : IHook {
    override fun getName(): String {
        return "不显示小红点"
    }

    override fun init(classLoader: ClassLoader) {
        try {
            val bottomNavMenuItemView = classLoader.loadClass("com.zhihu.android.bottomnav.core.BottomNavMenuItemView")
            BottomNavMenuItemView_setUnreadCount = Helper.getMethodByParameterTypes(bottomNavMenuItemView, Int::class.javaPrimitiveType!!)
        } catch (_: ClassNotFoundException) {
        }
        try {
            val bottomNavMenuItemViewForIconOnly = classLoader.loadClass("com.zhihu.android.bottomnav.core.BottomNavMenuItemViewForIconOnly")
            BottomNavMenuItemViewForIconOnly_setUnreadCount = Helper.getMethodByParameterTypes(
                bottomNavMenuItemViewForIconOnly,
                Int::class.javaPrimitiveType!!,
            )
        } catch (_: ClassNotFoundException) {
        }
        try {
            val baseBottomNavMenuItemView = classLoader.loadClass("com.zhihu.android.bottomnav.core.BaseBottomNavMenuItemView")
            val navBadge = classLoader.loadClass("com.zhihu.android.bottomnav.api.model.NavBadge")
            BaseBottomNavMenuItemView_setNavBadge = Helper.getMethodByParameterTypes(baseBottomNavMenuItemView, navBadge)
        } catch (_: ClassNotFoundException) {
        }
        try {
            val notiMsgModel = classLoader.loadClass("com.zhihu.android.notification.model.viewmodel.NotiMsgModel")
            NotiMsgModel_getUnreadCount = notiMsgModel.getDeclaredMethod("getUnreadCount")
        } catch (_: ClassNotFoundException) {
        } catch (_: NoSuchMethodException) {
        }
        try {
            val iconWithDotAndCountView = classLoader.loadClass("com.zhihu.android.community_base.view.icon.IconWithDotAndCountView")
            IconWithDotAndCountView_setUnreadCount = Helper.getMethodByParameterTypes(
                iconWithDotAndCountView,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
        } catch (_: ClassNotFoundException) {
        }
        try {
            val countDotView = classLoader.loadClass("com.zhihu.android.notification.widget.CountDotView")
            CountDotView_setUnreadCount = Helper.getMethodByParameterTypes(
                countDotView,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            )
        } catch (_: ClassNotFoundException) {
        }
        for (name in arrayOf(
            "com.zhihu.android.recentlyviewed.ui.viewholder.BaseFeedFollowAvatarViewHolder",
            "com.zhihu.android.moments.viewholders.BaseFeedFollowAvatarViewHolder",
        )) {
            try {
                val clazz = classLoader.loadClass(name)
                val method = Helper.getMethodByParameterTypes(clazz, View::class.java, Boolean::class.javaPrimitiveType!!)
                if (method != null) {
                    // P1 是包级私有方法，XposedBridge.hookMethod 不会自动 setAccessible
                    method.isAccessible = true
                    BaseFeedFollowAvatarViewHolder_setUnreadTipVisibility = method
                    break
                }
            } catch (_: ClassNotFoundException) {
            }
        }
        try {
            ViewModel = classLoader.loadClass("com.zhihu.android.app.feed.ui.fragment.help.tabhelp.model.ViewModel")
        } catch (_: ClassNotFoundException) {
        }

        if (BottomNavMenuItemView_setUnreadCount == null && BottomNavMenuItemViewForIconOnly_setUnreadCount == null
            && BaseBottomNavMenuItemView_setNavBadge == null && BaseFeedFollowAvatarViewHolder_setUnreadTipVisibility == null
            && NotiMsgModel_getUnreadCount == null && IconWithDotAndCountView_setUnreadCount == null
            && CountDotView_setUnreadCount == null && ViewModel == null
        ) {
            throw ClassNotFoundException("小红点目标全部消失")
        }
    }

    override fun hook() {
        if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_reddot", false)) {
            if (BottomNavMenuItemView_setUnreadCount != null) {
                XposedBridge.hookMethod(BottomNavMenuItemView_setUnreadCount!!, XC_MethodReplacement.returnConstant(null))
            }
            if (BottomNavMenuItemViewForIconOnly_setUnreadCount != null) {
                XposedBridge.hookMethod(BottomNavMenuItemViewForIconOnly_setUnreadCount!!, XC_MethodReplacement.returnConstant(null))
            }
            if (BaseBottomNavMenuItemView_setNavBadge != null) {
                XposedBridge.hookMethod(BaseBottomNavMenuItemView_setNavBadge!!, XC_MethodReplacement.returnConstant(null))
            }
            if (BaseFeedFollowAvatarViewHolder_setUnreadTipVisibility != null) {
                XposedBridge.hookMethod(BaseFeedFollowAvatarViewHolder_setUnreadTipVisibility!!, XC_MethodReplacement.returnConstant(null))
            }
            if (NotiMsgModel_getUnreadCount != null) {
                XposedBridge.hookMethod(NotiMsgModel_getUnreadCount!!, XC_MethodReplacement.returnConstant(0))
            }
            if (IconWithDotAndCountView_setUnreadCount != null) {
                XposedBridge.hookMethod(IconWithDotAndCountView_setUnreadCount!!, XC_MethodReplacement.returnConstant(null))
            }
            if (CountDotView_setUnreadCount != null) {
                XposedBridge.hookMethod(CountDotView_setUnreadCount!!, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? {
                        val target = param.thisObject
                        if (target is View) {
                            target.setVisibility(View.GONE)
                        }
                        return null
                    }
                })
            }
            if (ViewModel != null) {
                XposedHelpers.findAndHookConstructor(ViewModel!!, View::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val arg = param.args[0]
                        if (arg is ViewGroup) {
                            if (arg.childCount == 2) { // red_parent
                                arg.setVisibility(View.GONE)
                            }
                        }
                    }
                })
            }
        }
    }

    companion object {
        @JvmField
        var ViewModel: Class<*>? = null

        @JvmField
        var BottomNavMenuItemView_setUnreadCount: Method? = null

        @JvmField
        var BottomNavMenuItemViewForIconOnly_setUnreadCount: Method? = null

        @JvmField
        var BaseBottomNavMenuItemView_setNavBadge: Method? = null

        @JvmField
        var NotiMsgModel_getUnreadCount: Method? = null

        @JvmField
        var IconWithDotAndCountView_setUnreadCount: Method? = null

        @JvmField
        var CountDotView_setUnreadCount: Method? = null

        @JvmField
        var BaseFeedFollowAvatarViewHolder_setUnreadTipVisibility: Method? = null
    }
}
