package com.shatyuka.zhiliao.hooks

import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.View
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import com.shatyuka.zhiliao.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method

class StatusBar : IHook {
    override fun getName(): String {
        return "状态栏沉浸"
    }

    override fun init(classLoader: ClassLoader) {
        Helper.requireTarget(Helper.AnswerPagerFragment, "com.zhihu.android.answer.module.pager.AnswerPagerFragment")
        Helper.findClass(classLoader, "com.zhihu.android.base.util.", 0, 2) { statusBarUtil ->
            val className = statusBarUtil.name
            val combinedDrawable = classLoader.loadClass(className + "\$a")
            val statusBarDrawable = classLoader.loadClass(className + "\$b")
            if (!LayerDrawable::class.java.isAssignableFrom(combinedDrawable) || !Drawable::class.java.isAssignableFrom(statusBarDrawable)) {
                return@findClass false
            }
            // n0$a 里 k(=n0$b) 才是 StatusBarDrawable，j 是 origin Drawable，按字段名取会绑错接收者
            if (Helper.findFieldByType(combinedDrawable!!, statusBarDrawable) == null) {
                return@findClass false
            }
            CombinedDrawable = combinedDrawable
            StatusBarDrawable = statusBarDrawable
            true
        }
        if (CombinedDrawable == null) {
            throw ClassNotFoundException("com.zhihu.android.base.util.StatusBarUtil\$CombinedDrawable")
        }

        setColor = StatusBarDrawable!!.getMethod("a", Int::class.javaPrimitiveType!!)

        CombinedDrawable_statusBarDrawable = Helper.findFieldByType(CombinedDrawable!!, StatusBarDrawable!!)
        if (CombinedDrawable_statusBarDrawable == null) {
            throw NoSuchFieldException(CombinedDrawable!!.name + ".statusBarDrawable")
        }
    }

    override fun hook() {
        XposedHelpers.findAndHookConstructor(
            StatusBarDrawable!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_statusbar", false)) {
                        param.args[0] = getStatusbarColor()
                    }
                }
            },
        )
        XposedBridge.hookMethod(setColor!!, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_statusbar", false)) {
                    param.args[0] = getStatusbarColor()
                }
            }
        })
        XposedHelpers.findAndHookMethod(
            Helper.AnswerPagerFragment!!,
            "onViewCreated",
            View::class.java,
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_statusbar", false)) {
                        val created = param.args[0]
                        if (created is View) {
                            created.setBackgroundColor(getStatusbarColor())
                        }
                    }
                }
            },
        )
    }

    companion object {
        @JvmField
        var CombinedDrawable: Class<*>? = null

        @JvmField
        var StatusBarDrawable: Class<*>? = null

        @JvmField
        var setColor: Method? = null

        @JvmField
        var CombinedDrawable_statusBarDrawable: Field? = null

        private fun getStatusbarColor(): Int {
            return if (Helper.getDarkMode()) {
                0xFF121212.toInt()
            } else {
                0xFFFFFFFF.toInt()
            }
        }
    }
}
