package com.shatyuka.zhiliao.hooks

import android.content.Context
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.TargetResolver
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import com.shatyuka.zhiliao.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Arrays

class FeedAd : IHook {
    override fun getName(): String {
        return "去信息流广告"
    }

    override fun init(classLoader: ClassLoader) {
        Helper.requireTarget(Helper.MorphAdHelper, "com.zhihu.android.morph.ad.utils.MorphAdHelper")
        BasePagingFragment = classLoader.loadClass("com.zhihu.android.app.ui.fragment.paging.BasePagingFragment")
        // 广告模型的包名渠道间不同（api.model / adbase.model），由候选名单给顺序，
        // 命中一个就按同包补齐另外三个——同一份渠道包里这四个类总在同一个包
        val feedAdvert = TargetResolver.findClassByQualifiedCandidates(
            classLoader,
            "feedAd.feedAdvert",
        ) { clazz -> looksLikeAdModel(clazz) }
        FeedAdvert = feedAdvert
        if (feedAdvert != null) {
            val pkg = feedAdvert.getPackage().name
            ListAd = classLoader.loadClass("$pkg.ListAd")
            Advert = classLoader.loadClass("$pkg.Advert")
            Ad = classLoader.loadClass("$pkg.Ad")
        } else {
            try {
                FeedAdvert = classLoader.loadClass("com.zhihu.android.api.model.FeedAdvert")
                ListAd = classLoader.loadClass("com.zhihu.android.api.model.ListAd")
                Advert = classLoader.loadClass("com.zhihu.android.api.model.Advert")
                Ad = classLoader.loadClass("com.zhihu.android.api.model.Ad")
            } catch (e: ClassNotFoundException) {
                FeedAdvert = classLoader.loadClass("com.zhihu.android.adbase.model.FeedAdvert")
                ListAd = classLoader.loadClass("com.zhihu.android.adbase.model.ListAd")
                Advert = classLoader.loadClass("com.zhihu.android.adbase.model.Advert")
                Ad = classLoader.loadClass("com.zhihu.android.adbase.model.Ad")
            }
        }

        FeedList_data = classLoader.loadClass("com.zhihu.android.api.model.FeedList").getField("data")
        val advertClass = FeedAdvert
        resolveFeedAdParam = TargetResolver.findMethod(Helper.MorphAdHelper, false, 0) { method ->
            method.returnType == Boolean::class.javaPrimitiveType &&
                Arrays.equals(
                    method.parameterTypes,
                    arrayOf(
                        Context::class.java,
                        advertClass,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.java,
                    ),
                )
        }

        // 推荐页顶部那条横贯整屏的焦点广告栏不来自信息流数据：feed 找 ad_card 模块要视图，
        // 拿回来自己塞在列表最前面。所以按条目删广告删不到它，只能在"要视图"这一步拦。
        // 入口是接口方法 getAdCardView(回调, int cardType)，实现类里同名；参数类型才是稳的锚。
        adCardView = null
        var adCardError: Throwable? = null
        try {
            val adCardImpl = classLoader.loadClass("com.zhihu.android.ad_card.AdCardViewInterfaceImpl")
            for (method in adCardImpl.declaredMethods) {
                val types = method.parameterTypes
                if (method.returnType != Void.TYPE || types.size != 2
                    || types[1] !== Int::class.javaPrimitiveType || !types[0].isInterface
                ) {
                    continue
                }
                if (method.name == "getAdCardView") {
                    adCardView = method
                    break
                }
                if (adCardView == null) {
                    adCardView = method
                }
            }
        } catch (e: Throwable) {
            adCardError = e
        }
        if (adCardView == null) {
            XposedBridge.log(
                "[Zhiliao][去信息流广告] 没有找到 ad_card 的 getAdCardView(回调,int)，顶部广告栏会漏" +
                    if (adCardError == null) "" else "：$adCardError",
            )
        }
    }

    override fun hook() {
        XposedBridge.hookAllMethods(BasePagingFragment!!, "postRefreshSucceed", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_feedad", true)) {
                    if (param.args[0] == null || !FeedList_data!!.declaringClass.isInstance(param.args[0])) {
                        return
                    }
                    val data = FeedList_data!!.get(param.args[0])
                    if (data !is List<*>) {
                        return
                    }
                    if (data.isEmpty()) {
                        return
                    }
                    removeFeedAdverts(data)
                }
            }
        })
        XposedHelpers.findAndHookMethod(
            BasePagingFragment!!,
            "insertDataRangeToList",
            Int::class.javaPrimitiveType,
            java.util.List::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_feedad", true)) {
                        val data = param.args[1]
                        if (data !is List<*>) {
                            return
                        }
                        if (data.isEmpty()) {
                            return
                        }
                        removeFeedAdverts(data)
                    }
                }
            },
        )
        if (resolveFeedAdParam != null) {
            XposedBridge.hookMethod(resolveFeedAdParam!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_feedad", true)) {
                        param.result = false
                    }
                }
            })
        }
        try {
            XposedHelpers.findAndHookMethod(
                Helper.MorphAdHelper!!,
                "resolve",
                Context::class.java,
                ListAd,
                Boolean::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_feedad", true)) {
                            param.result = false
                        }
                    }
                },
            )
        } catch (ignore: NoSuchMethodError) {
        }
        XposedHelpers.findAndHookMethod(Advert!!, "isSlidingWindow", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_feedad", true)) {
                    param.result = false
                }
            }
        })
        XposedHelpers.findAndHookMethod(Ad!!, "isFloatAdCard", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_feedad", true)) {
                    param.result = false
                }
            }
        })
        if (adCardView != null) {
            XposedBridge.hookMethod(adCardView!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!Helper.prefs!!.getBoolean("switch_mainswitch", false) || !Helper.prefs!!.getBoolean("switch_feedad", true)) {
                        return
                    }
                    // 只拦焦点图那一种（FOCUS_VIEW = 1）：话题红包卡走 getAdCardView(String,int) 另一个重载，
                    // 将来这个重载若也支持别的类型，别顺手一起误伤。
                    val cardType = param.args[1]
                    if (cardType !is Int || cardType != 1) {
                        return
                    }
                    // 直接不给视图：feed 拿到 null 就跳过插入（setResult 之后回调也不会被调用）
                    param.result = null
                }
            })
        }
    }

    private fun removeFeedAdverts(data: List<*>) {
        @Suppress("UNCHECKED_CAST")
        val list = data as MutableList<Any?>
        for (i in list.size - 1 downTo 0) {
            if (isFeedAdvert(list[i])) {
                list.removeAt(i)
            }
        }
    }

    private fun isFeedAdvert(item: Any?): Boolean {
        val advert = FeedAdvert
        return item != null && advert != null && advert.isInstance(item)
    }

    companion object {
        @JvmField
        var BasePagingFragment: Class<*>? = null

        @JvmField
        var FeedAdvert: Class<*>? = null

        @JvmField
        var ListAd: Class<*>? = null

        @JvmField
        var Advert: Class<*>? = null

        @JvmField
        var Ad: Class<*>? = null

        @JvmField
        var FeedList_data: Field? = null

        @JvmField
        var resolveFeedAdParam: Method? = null

        /** ad_card 模块的视图入口：void 方法 + (回调接口, int 卡片类型)。 */
        @JvmField
        var adCardView: Method? = null

        /** 广告模型的结构特征：公开、非抽象、类名对得上。 */
        private fun looksLikeAdModel(clazz: Class<*>): Boolean {
            return clazz.simpleName == "FeedAdvert"
                && Modifier.isPublic(clazz.modifiers)
                && !Modifier.isAbstract(clazz.modifiers)
        }
    }
}
