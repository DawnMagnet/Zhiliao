package com.shatyuka.zhiliao.hooks

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field

class FollowButton : IHook {
    override fun getName(): String {
        return "去关注按钮"
    }

    override fun init(classLoader: ClassLoader) {
        try {
            followWithAvatarView = classLoader.loadClass("com.zhihu.android.unify_interactive.view.follow.FollowWithAvatarView")
            bottomReactionView = classLoader.loadClass("com.zhihu.android.mixshortcontainer.function.mixup.view.BottomReactionView")

            followPeopleButton = classLoader.loadClass("com.zhihu.android.unify_interactive.view.follow.FollowPeopleButton")
            zHAuthorInfoView = classLoader.loadClass("com.zhihu.android.mixshortcontainer.function.mixup.author.ZHAuthorInfoView")

            followWithAvatarViewField = Helper.findFieldByType(bottomReactionView!!, followWithAvatarView!!)
            followPeopleButtonField = Helper.findFieldByType(zHAuthorInfoView!!, followPeopleButton!!)
        } catch (ignore: ClassNotFoundException) {
        }
    }

    override fun hook() {
        if (followWithAvatarViewField != null) {
            XposedBridge.hookAllMethods(bottomReactionView!!, "setData", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_subscribe", false)) {
                        val followWithAvatarViewInstance = followWithAvatarViewField!!.get(param.thisObject) as FrameLayout?
                        if (followWithAvatarViewInstance != null) {
                            followWithAvatarViewInstance.visibility = View.GONE
                        }
                    }
                }
            })
        }

        if (followPeopleButtonField != null) {
            XposedBridge.hookAllMethods(zHAuthorInfoView!!, "setData", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_subscribe", false)) {
                        val followPeopleButtonFieldInstance = followPeopleButtonField!!.get(param.thisObject) as ViewGroup?
                        if (followPeopleButtonFieldInstance != null) {
                            followPeopleButtonFieldInstance.visibility = View.GONE
                        }
                    }
                }
            })
        }
    }

    companion object {
        @JvmField
        var followWithAvatarView: Class<*>? = null

        @JvmField
        var bottomReactionView: Class<*>? = null

        @JvmField
        var followPeopleButton: Class<*>? = null

        @JvmField
        var zHAuthorInfoView: Class<*>? = null

        @JvmField
        var followWithAvatarViewField: Field? = null

        @JvmField
        var followPeopleButtonField: Field? = null
    }
}
