package com.shatyuka.zhiliao.hooks

import android.view.View
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale

class NavButton : IHook {
    override fun getName(): String {
        return "隐藏导航栏按钮"
    }

    override fun init(classLoader: ClassLoader) {
        BottomNavMenuView = classLoader.loadClass("com.zhihu.android.bottomnav.core.BottomNavMenuView")

        val tabLayoutTabClass = classLoader.loadClass("com.google.android.material.tabs.TabLayout\$Tab")
        // e(r.g) 与 setMenuUiTestId(r.g) 参数相同，只有前者返回 Tab，按返回类型筛选
        getTab = Helper.requireTarget(
            BottomNavMenuView!!.declaredMethods.firstOrNull { method ->
                method.returnType === tabLayoutTabClass && method.parameterCount == 1
            },
            "BottomNavMenuView.getTab",
        )
        getTab!!.isAccessible = true
        IMenuItem = getTab!!.parameterTypes[0]

        // 菜单项 id 的方法名在不同渠道包里不一致，按"无参且返回 String"结构定位（优先名字含 id 的）
        getItemId = Helper.requireTarget(
            IMenuItem!!.declaredMethods.minWithOrNull(
                compareBy<Method> { method ->
                    if (method.name.lowercase(Locale.getDefault()).contains("id")) 0 else 1
                },
            ),
            "IMenuItem.getItemId",
        )
        getItemId!!.isAccessible = true

        Tab_tabView = Helper.requireTarget(tabLayoutTabClass.getField("view"), "TabLayout\$Tab.view")
    }

    override fun hook() {
        if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && (Helper.prefs!!.getBoolean("switch_homenav", false) || Helper.prefs!!.getBoolean("switch_findnav", false) || Helper.prefs!!.getBoolean("switch_panelnav", false) || Helper.prefs!!.getBoolean("switch_messagenav", false) || Helper.prefs!!.getBoolean("switch_profilenav", false))) {
            XposedBridge.hookMethod(getTab!!, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args == null || param.args.isEmpty() || !IMenuItem!!.isInstance(param.args[0])) {
                        return
                    }
                    if (!isHidden(getItemId!!.invoke(param.args[0]))) {
                        return
                    }

                    val tab = param.result
                    if (tab == null || !Tab_tabView!!.declaringClass.isInstance(tab)) {
                        return
                    }

                    val tabView = Tab_tabView!!.get(tab) as View?
                    if (tabView != null) {
                        tabView.setVisibility(View.GONE)
                    } else if (param.thisObject is View) {
                        // Tab 的 View 在 addTab 时才创建，此时先返回，稍后再隐藏
                        (param.thisObject as View).post { hideTab(tab) }
                    }
                }
            })
        }
    }

    companion object {
        @JvmField
        var BottomNavMenuView: Class<*>? = null

        @JvmField
        var IMenuItem: Class<*>? = null

        @JvmField
        var getItemId: Method? = null

        @JvmField
        var getTab: Method? = null

        @JvmField
        var Tab_tabView: Field? = null

        // 11.10.0 底栏实际下发 home/find/panel/message/profile
        private fun isHidden(itemId: Any?): Boolean {
            if ("home" == itemId) return Helper.prefs!!.getBoolean("switch_homenav", false)
            if ("find" == itemId) return Helper.prefs!!.getBoolean("switch_findnav", false)
            if ("panel" == itemId) return Helper.prefs!!.getBoolean("switch_panelnav", false)
            if ("message" == itemId) return Helper.prefs!!.getBoolean("switch_messagenav", false)
            if ("profile" == itemId) return Helper.prefs!!.getBoolean("switch_profilenav", false)
            return false
        }

        private fun hideTab(tab: Any?) {
            if (!Tab_tabView!!.declaringClass.isInstance(tab)) {
                return
            }
            try {
                val tabView = Tab_tabView!!.get(tab)
                if (tabView is View) {
                    tabView.setVisibility(View.GONE)
                }
            } catch (_: IllegalAccessException) {
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
