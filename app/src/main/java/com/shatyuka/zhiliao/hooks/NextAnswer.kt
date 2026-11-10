package com.shatyuka.zhiliao.hooks

import android.view.View
import android.view.ViewGroup
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XC_MethodReplacement
import com.shatyuka.zhiliao.xposed.XposedBridge
import com.shatyuka.zhiliao.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method

class NextAnswer : IHook {
    override fun getName(): String {
        return "移除下一个回答按钮"
    }

    override fun init(classLoader: ClassLoader) {
        Helper.requireTarget(Helper.AnswerPagerFragment, "com.zhihu.android.answer.module.pager.AnswerPagerFragment")
        if (Helper.versionCode > 2614) {
            // 旧短容器(mixshortcontainer)与新版短容器(feature.short_container_feature)是两套平行实现,
            // 两边的按钮类互不继承, 所以两条路都要挂; 任一边缺失只降级, 不整条失败。
            try {
                NextContentAnimationView = classLoader.loadClass("com.zhihu.android.mix.widget.NextContentAnimationView")
                NextContentAnimationView_short = classLoader.loadClass("com.zhihu.android.mixshortcontainer.function.next.NextContentAnimationView")
                MixShortContainerFragment = classLoader.loadClass("com.zhihu.android.mixshortcontainer.MixShortContainerFragment")
                PopupMenuNextButton = classLoader.loadClass("com.zhihu.android.mixshortcontainer.function.next.PopupMenuNextButton")

                initView = Helper.getMethodByParameterTypes(MixShortContainerFragment, 0, View::class.java)
                initLayout = Helper.getMethodByParameterTypes(MixShortContainerFragment, 1, View::class.java)
                if (initView != null) {
                    initView!!.isAccessible = true
                }
                if (initLayout != null) {
                    initLayout!!.isAccessible = true
                }

                nextButton = Helper.findFieldByType(MixShortContainerFragment!!, PopupMenuNextButton!!)
            } catch (e: Throwable) {
                XposedBridge.log("[Zhiliao] 移除下一个回答按钮: 旧短容器目标已缺失, 仅处理新短容器: " + e)
            }

            // 11.10.0 起回答流默认走新短容器模块; 该模块包名未混淆(真机 ShortContainerHostActivity 同包), 可直接按全名加载
            NextContentAnimationView_feature = loadClassOrNull(
                classLoader,
                "com.zhihu.android.feature.short_container_feature.ui.widget.next.NextContentAnimationView",
            )
            PopupMenuNextButton_feature = loadClassOrNull(
                classLoader,
                "com.zhihu.android.feature.short_container_feature.ui.widget.next.PopupMenuNextButton",
            )

            if (PopupMenuNextButton == null && PopupMenuNextButton_feature == null) {
                throw ClassNotFoundException("PopupMenuNextButton")
            }
        }
    }

    override fun hook() {
        if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_nextanswer", false)) {
            XposedHelpers.findAndHookMethod(Helper.AnswerPagerFragment!!, "setupNextAnswerBtn", XC_MethodReplacement.returnConstant(null))
            if (nextButton != null) {
                if (initView != null) {
                    XposedBridge.hookMethod(initView!!, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            hideNextButton(param)
                        }
                    })
                }
                if (initLayout != null) {
                    XposedBridge.hookMethod(initLayout!!, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            hideNextButton(param)
                        }
                    })
                }
            }

            if (Helper.versionCode > 2614) {
                XposedHelpers.findAndHookMethod(
                    ViewGroup::class.java,
                    "addView",
                    View::class.java,
                    ViewGroup.LayoutParams::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (isNextButtonView(param.args[0])) {
                                (param.args[0] as View).setVisibility(View.GONE)
                            }
                        }
                    },
                )

                XposedHelpers.findAndHookMethod(
                    View::class.java,
                    "setVisibility",
                    Int::class.javaPrimitiveType!!,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isNextButtonView(param.thisObject)) {
                                param.args[0] = View.GONE
                            }
                        }
                    },
                )
            }

            for (buttonClass in arrayOf(PopupMenuNextButton, PopupMenuNextButton_feature)) {
                if (buttonClass == null) {
                    continue
                }
                XposedBridge.hookAllConstructors(buttonClass!!, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val created = param.thisObject
                        if (created is View) {
                            created.setVisibility(View.GONE)
                            created.scaleX = 0f
                            created.scaleY = 0f
                        }
                    }
                })
            }
        }
    }

    companion object {
        @JvmField
        var NextContentAnimationView: Class<*>? = null

        @JvmField
        var NextContentAnimationView_short: Class<*>? = null

        @JvmField
        var MixShortContainerFragment: Class<*>? = null

        @JvmField
        var PopupMenuNextButton: Class<*>? = null

        @JvmField
        var NextContentAnimationView_feature: Class<*>? = null

        @JvmField
        var PopupMenuNextButton_feature: Class<*>? = null

        @JvmField
        var initView: Method? = null

        @JvmField
        var initLayout: Method? = null

        @JvmField
        var nextButton: Field? = null

        private fun isNextButtonView(obj: Any?): Boolean {
            if (obj !is View) {
                return false
            }
            return (NextContentAnimationView != null && NextContentAnimationView!!.isInstance(obj))
                || (NextContentAnimationView_short != null && NextContentAnimationView_short!!.isInstance(obj))
                || (NextContentAnimationView_feature != null && NextContentAnimationView_feature!!.isInstance(obj))
        }

        private fun loadClassOrNull(classLoader: ClassLoader, name: String): Class<*>? {
            return try {
                classLoader.loadClass(name)
            } catch (_: Throwable) {
                null
            }
        }

        // 置 null 会让宿主逻辑 NPE 或跳过后续处理，只隐藏按钮本体
        private fun hideNextButton(param: MethodHookParam) {
            try {
                if (nextButton == null || !MixShortContainerFragment!!.isInstance(param.thisObject)) {
                    return
                }
                val button = nextButton!!.get(param.thisObject)
                if (button is View) {
                    button.setVisibility(View.GONE)
                }
            } catch (_: Throwable) {
            }
        }
    }
}
