package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.TargetResolver
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.LinkedList

class SearchAd : IHook {
    override fun getName(): String {
        return "去搜索广告"
    }

    override fun init(classLoader: ClassLoader) {
        // 转换器类随渠道包改名：顺序交给候选名单，命中几个挂几个
        for (converter in TargetResolver.findClassesByQualifiedCandidates(
            classLoader,
            "searchAd.converters",
        ) { clazz -> looksLikeConverter(clazz) }) {
            try {
                converts.add(converter.getMethod("convert", Any::class.java))
            } catch (_: NoSuchMethodException) {
            }
        }
        if (converts.isEmpty()) {
            for (className in LEGACY_CONVERTERS) {
                try {
                    val converter = classLoader.loadClass(className)
                    converts.add(converter.getMethod("convert", Any::class.java))
                } catch (_: ClassNotFoundException) {
                } catch (_: NoSuchMethodException) {
                }
            }
        }
        if (converts.isEmpty()) {
            throw ClassNotFoundException("retrofit2.converter.jackson.JacksonResponseBodyConverter")
        }

        try {
            val searchTopTabsItemList = classLoader.loadClass("com.zhihu.android.api.model.SearchTopTabsItemList")
            SearchTopTabsItemList_commercialData = searchTopTabsItemList.getField("commercialData")
        } catch (_: ClassNotFoundException) {
        } catch (_: NoSuchFieldException) {
        }

        try {
            val searchRecommendQuery = classLoader.loadClass("com.zhihu.android.api.model.SearchRecommendQuery")
            try {
                SearchRecommendQuery_content = searchRecommendQuery.getField("content")
            } catch (_: NoSuchFieldException) {
            }
            try {
                SearchRecommendQuery_recommendQueries = searchRecommendQuery.getField("recommendQueries")
            } catch (_: NoSuchFieldException) {
            }
            try {
                val commercialData = searchRecommendQuery.getField("commercialData")
                if (commercialData.type === List::class.java) {
                    SearchRecommendQuery_commercialData = commercialData
                }
            } catch (_: NoSuchFieldException) {
            }
        } catch (_: ClassNotFoundException) {
        }

        val presetWords = classLoader.loadClass("com.zhihu.android.api.model.PresetWords")
        PresetWords_preset = presetWords.getField("preset")

        try {
            val hotSearchBean = classLoader.loadClass("com.zhihu.android.api.model.HotSearchBean")
            HotSearchBean_searchHotList = hotSearchBean.getField("searchHotList")
        } catch (_: ClassNotFoundException) {
        } catch (_: NoSuchFieldException) {
        }
    }

    override fun hook() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_searchad", true)) {
                    val result = param.result
                    if (result != null) {
                        when (result.javaClass.name) {
                            "com.zhihu.android.api.model.SearchTopTabsItemList" -> {
                                if (SearchTopTabsItemList_commercialData != null) {
                                    SearchTopTabsItemList_commercialData!!.set(result, null)
                                }
                            }
                            "com.zhihu.android.api.model.PresetWords" -> {
                                if (PresetWords_preset != null) {
                                    PresetWords_preset!!.set(result, null)
                                }
                            }
                            "com.zhihu.android.api.model.SearchRecommendQuery" -> {
                                if (SearchRecommendQuery_content != null) {
                                    SearchRecommendQuery_content!!.set(result, null)
                                }
                                if (SearchRecommendQuery_recommendQueries != null) {
                                    SearchRecommendQuery_recommendQueries!!.set(result, null)
                                }
                                if (SearchRecommendQuery_commercialData != null) {
                                    SearchRecommendQuery_commercialData!!.set(result, Collections.emptyList<Any>())
                                }
                            }
                            "com.zhihu.android.api.model.HotSearchBean" -> {
                                if (HotSearchBean_searchHotList != null) {
                                    HotSearchBean_searchHotList!!.set(result, null)
                                }
                            }
                        }
                        param.result = result
                    }
                }
            }
        }
        for (convert in converts) {
            XposedBridge.hookMethod(convert!!, hook)
        }
    }

    companion object {
        /** 名单全落空时的兜底顺序（历史上各版本出现过的转换器类名）。 */
        private val LEGACY_CONVERTERS = arrayOf(
            "com.zhihu.android.net.b.b",
            "com.zhihu.android.net.c.b",
            "com.zhihu.android.net.d.b",
            "com.zhihu.android.c3.f.c",
            "com.zhihu.android.e3.f.b",
            "retrofit2.b.a.c",
            "retrofit2.p.a.c",
            "j.b.a.c",
        )

        @JvmField
        var converts: LinkedList<Method> = LinkedList()

        @JvmField
        var SearchTopTabsItemList_commercialData: Field? = null

        @JvmField
        var PresetWords_preset: Field? = null

        @JvmField
        var SearchRecommendQuery_content: Field? = null

        @JvmField
        var SearchRecommendQuery_commercialData: Field? = null

        @JvmField
        var SearchRecommendQuery_recommendQueries: Field? = null

        @JvmField
        var HotSearchBean_searchHotList: Field? = null

        /** 转换器的结构特征：有一个 convert(Object) 返回 Object。 */
        private fun looksLikeConverter(clazz: Class<*>): Boolean {
            return try {
                clazz.getMethod("convert", Any::class.java).returnType === Any::class.java
            } catch (_: Throwable) {
                false
            }
        }
    }
}
