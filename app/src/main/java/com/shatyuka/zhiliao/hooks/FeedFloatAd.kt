package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.ArrayList

/**
 * 去浮层广告：首屏浮层、二屏浮层、搜索浮层、视频浮层、首页彩蛋，知乎把它们统称「特形广告」。
 *
 * 数据只拉一次 app_float_layer，再按 float_layer / second_float_layer / feed_egg /
 * search_middle_page 四个字段各存一份（ad.special.b 包里的单例配置）。展示前每个 presenter
 * 都要先问同一个继承来的 initData(String)「这个类型有没有数据」——没有数据就什么都不画，
 * 所以这里不碰布局、不碰图片，直接让它一律回答"没有"。
 *
 * 父类的类名在渠道包里是混淆的，presenter 名却是公开的，所以顺着 presenter 找继承来的公开方法；
 * 父类里还有个同签名的私有 isImageCached(String)，按签名瞎猜会挑错，必须认名字。
 *
 * 推荐页那个彩蛋（helper.AdFeedEggManager）走另一条线：自己往容器里塞视图，全是无参方法，
 * 靠"返回 void 且没有参数"认出来，全部拦掉等于这个彩蛋再也不会显形。
 */
class FeedFloatAd : IHook {
    override fun getName(): String {
        return "去浮层广告"
    }

    override fun init(classLoader: ClassLoader) {
        initDataMethods.clear()
        eggHides.clear()
        for (name in PRESENTERS) {
            val presenter = try {
                classLoader.loadClass(name)
            } catch (ignore: Throwable) {
                continue
            }
            val method = try {
                presenter.getMethod("initData", String::class.java)
            } catch (ignore: Throwable) {
                continue
            }
            if (!initDataMethods.contains(method)) {
                initDataMethods.add(method)
            }
        }
        if (initDataMethods.isEmpty()) {
            XposedBridge.log("[Zhiliao][去浮层广告] 没有找到特形广告的 initData(String)")
        }

        try {
            val eggManager = classLoader.loadClass("com.zhihu.android.app.feed.ui.fragment.helper.AdFeedEggManager")
            for (method in eggManager.declaredMethods) {
                if (method.returnType == Void.TYPE && method.parameterTypes.isEmpty()) {
                    eggHides.add(method)
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("[Zhiliao][去浮层广告] 没有推荐页彩蛋管理器: $e")
        }

        if (initDataMethods.isEmpty() && eggHides.isEmpty()) {
            throw NoSuchMethodException("浮层广告的两个入口都没找到")
        }
    }

    override fun hook() {
        for (method in initDataMethods) {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_feedfloatad", true)) {
                        param.result = false
                    }
                }
            })
        }
        for (method in eggHides) {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_feedfloatad", true)) {
                        param.result = null
                    }
                }
            })
        }
    }

    companion object {
        /** 已知的特形广告 presenter，渠道包里名字都保留着。 */
        private val PRESENTERS = arrayOf(
            "com.zhihu.android.ad.special.AdEgg",
            "com.zhihu.android.ad.special.AdFloat",
            "com.zhihu.android.ad.special.AdFloatSecond",
            "com.zhihu.android.ad.special.AdFloatVideo",
            "com.zhihu.android.ad.special.AdSearchFloatVideo",
        )

        private val initDataMethods: MutableList<Method> = ArrayList()
        private val eggHides: MutableList<Method> = ArrayList()

        /** 单测用：挂到的必须是 initData，被同签名的 isImageCached 顶掉就说明解析退化了。 */
        @JvmStatic
        fun initDataName(): String? {
            return if (initDataMethods.isEmpty()) null else initDataMethods[0].name
        }
    }
}
