package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.TargetResolver
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method

class Article : IHook {
    override fun getName(): String {
        return "精简文章页面"
    }

    override fun init(classLoader: ClassLoader) {
        if (Helper.versionCode > 2614) {
            // 11.10.0 只剩 mix.adapter 包，mix.a.a / mix.b.a 已不存在
            val adapter = TargetResolver.findClassByQualifiedCandidates(
                classLoader,
                KEY_ADAPTER,
            ) { clazz -> looksLikeContentMixAdapter(clazz) }
            if (adapter != null) {
                ContentMixAdapter = adapter
                getItemCount = adapter.getMethod("getItemCount")
                XposedBridge.log("[Zhiliao][自检] 精简文章页面 目标适配器=${adapter.name} 来源=候选名单")
            } else {
                // DexKit 那条兜底已明确弃用：这个适配器的 getItemCount 和 Fragment 字段都是继承来的，
                // dex 层面看不到（真机实测 0 命中），留着只会给人"有兜底"的错觉。实测记录见 ROADMAP 第三阶段。
                resolveLegacyAdapter(classLoader)
                XposedBridge.log("[Zhiliao][自检] 精简文章页面 目标适配器=${ContentMixAdapter!!.name} 来源=结构化兜底")
            }

            val pager = classLoader.loadClass("com.zhihu.android.mix.fragment.ContentMixPagerFragment")
            ContentMixPagerFragment = pager
            val fragmentClass = classLoader.loadClass("androidx.fragment.app.Fragment")
            val fragmentField = Helper.findFieldByType(ContentMixAdapter!!, fragmentClass)
            if (fragmentField == null) {
                throw NoSuchFieldException("fragment")
            }
            ContentMixAdapter_fragment = fragmentField
            fragmentField.setAccessible(true)
            try {
                val typeField = pager.getField("c")
                ContentMixPagerFragment_type = typeField
                if (typeField.type != String::class.java) {
                    throw NoSuchFieldException("type")
                }
            } catch (e: NoSuchFieldException) {
                val typeField = pager.getField("t")
                ContentMixPagerFragment_type = typeField
                if (typeField.type != String::class.java) {
                    throw NoSuchFieldException("type")
                }
            }
        }
    }

    override fun hook() {
        if (Helper.versionCode > 2614) { // after 6.61.0
            if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_article", false)) {
                XposedBridge.hookMethod(getItemCount!!, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val thisObject = param.thisObject
                        if (thisObject == null || thisObject.javaClass !== ContentMixAdapter) {
                            return
                        }
                        val fragment = ContentMixAdapter_fragment!!.get(thisObject)
                        if (fragment == null) {
                            return
                        }
                        val type = ContentMixPagerFragment_type!!.get(fragment)
                        if ("article" == type) {
                            param.result = 1
                        }
                    }
                })
            }
        }
    }

    companion object {
        private const val KEY_ADAPTER = "article.contentMixAdapter"

        /** 名单全落空时的结构化兜底：按历史上出现过的包名顺序试。 */
        private val LEGACY_ADAPTERS = arrayOf(
            "com.zhihu.android.mix.a.a",
            "com.zhihu.android.mix.b.a",
            "com.zhihu.android.mix.adapter.a",
            "com.zhihu.android.mix.adapter.ContentMixAdapter",
        )

        @JvmField
        var ContentMixAdapter: Class<*>? = null

        @JvmField
        var ContentMixPagerFragment: Class<*>? = null

        @JvmField
        var getItemCount: Method? = null

        @JvmField
        var ContentMixAdapter_fragment: Field? = null

        @JvmField
        var ContentMixPagerFragment_type: Field? = null

        /** 适配器的结构特征：无参 getItemCount() 返回 int，且持有一个 Fragment 字段。 */
        @JvmStatic
        fun looksLikeContentMixAdapter(clazz: Class<*>): Boolean {
            try {
                val method = clazz.getMethod("getItemCount")
                if (method.returnType != Int::class.javaPrimitiveType || method.parameterCount != 0) {
                    return false
                }
                val fragment = Class.forName("androidx.fragment.app.Fragment", false, clazz.classLoader)
                return Helper.findFieldByType(clazz, fragment) != null
            } catch (e: Throwable) {
                return false
            }
        }

        private fun resolveLegacyAdapter(classLoader: ClassLoader) {
            var last: Throwable? = null
            for (name in LEGACY_ADAPTERS) {
                try {
                    val clazz = classLoader.loadClass(name)
                    val method = clazz.getMethod("getItemCount")
                    ContentMixAdapter = clazz
                    getItemCount = method
                    return
                } catch (e: Throwable) {
                    last = e
                }
            }
            throw last ?: ClassNotFoundException("ContentMixAdapter")
        }
    }
}
