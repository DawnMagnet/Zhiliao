package com.shatyuka.zhiliao.hooks

import android.content.Context
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.TargetResolver
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import com.shatyuka.zhiliao.xposed.XposedHelpers
import java.lang.reflect.Modifier

class AnswerListAd : IHook {
    override fun getName(): String {
        return "去回答列表广告"
    }

    override fun init(classLoader: ClassLoader) {
        Helper.requireTarget(Helper.MorphAdHelper, "com.zhihu.android.morph.ad.utils.MorphAdHelper")
        try {
            AnswerListWrapper = classLoader.loadClass("com.zhihu.android.question.api.model.AnswerListWrapper")
        } catch (ignored: ClassNotFoundException) {
        }
        // 包名随渠道变（api.model / adbase.model），顺序交给候选名单
        AnswerListAd = TargetResolver.findClassByQualifiedCandidates(
            classLoader,
            "answerListAd.model",
        ) { clazz ->
            clazz.simpleName == "AnswerListAd" && Modifier.isPublic(clazz.modifiers)
        }
        if (AnswerListAd == null) {
            try {
                AnswerListAd = classLoader.loadClass("com.zhihu.android.api.model.AnswerListAd")
            } catch (e: ClassNotFoundException) {
                AnswerListAd = classLoader.loadClass("com.zhihu.android.adbase.model.AnswerListAd")
            }
        }
    }

    override fun hook() {
        XposedHelpers.findAndHookMethod(
            Helper.MorphAdHelper!!,
            "resolveAnswerAdParam",
            Context::class.java,
            AnswerListAd,
            Boolean::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_answerlistad", true)) {
                        param.result = false
                    }
                }
            },
        )
        if (AnswerListWrapper != null) {
            XposedBridge.hookAllMethods(AnswerListWrapper!!, "insertAdBrandToList", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_answerlistad", true)) {
                        param.result = null
                    }
                }
            })
        }
    }

    companion object {
        @JvmField
        var AnswerListWrapper: Class<*>? = null

        @JvmField
        var AnswerListAd: Class<*>? = null
    }
}
