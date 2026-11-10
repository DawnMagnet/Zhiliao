package com.shatyuka.zhiliao.hooks

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.TargetResolver
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Method
import java.nio.charset.Charset
import java.util.Arrays

class AnswerAd : IHook {
    override fun getName(): String {
        return "去回答底部广告"
    }

    override fun init(classLoader: ClassLoader) {
        Helper.requireTarget(Helper.IZhihuWebView, "com.zhihu.android.app.mercury.api.IZhihuWebView")
        // 真正要挂的是所有 AppView 子类，AnswerAppView 只是拿到这个父类的入口；
        // 入口类本身也会改名，所以先过名单，落空再用老名字
        var answerAppView = TargetResolver.findClassByQualifiedCandidates(
            classLoader,
            "answerAd.appView",
        ) { clazz ->
            val superclass = clazz.superclass
            superclass != null && superclass !== Any::class.java
        }
        if (answerAppView == null) {
            answerAppView = classLoader.loadClass("com.zhihu.android.answer.module.content.appview.AnswerAppView")
        }
        val appView = answerAppView.superclass
            ?: throw ClassNotFoundException("com.zhihu.android.appview.AppView")
        Helper.findClass(classLoader, appView.name + '$', 0, 32) { zhihuWebViewClient ->
            shouldInterceptRequest = TargetResolver.findMethod(zhihuWebViewClient, false, 0) { method ->
                method.returnType == WebResourceResponse::class.java &&
                    Arrays.equals(
                        method.parameterTypes,
                        arrayOf(Helper.IZhihuWebView, WebResourceRequest::class.java),
                    )
            }
            shouldInterceptRequest != null
        }
        if (shouldInterceptRequest == null) {
            throw NoSuchMethodException("com.zhihu.android.appview.AppView\$ZhihuWebViewClient(IZhihuWebView, WebResourceRequest): WebResourceResponse")
        }
    }

    override fun hook() {
        XposedBridge.hookMethod(shouldInterceptRequest!!, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!Helper.prefs!!.getBoolean("switch_mainswitch", false)) {
                    return
                }
                val request = param.args[1] as WebResourceRequest?
                if (request == null || request.url == null) {
                    return
                }
                val segments = request.url.pathSegments
                if (segments.size > 2 && request.method == "GET"
                    && ((Helper.prefs!!.getBoolean("switch_answerad", true) && (segments[segments.size - 1] == "recommendations"
                    || (segments[2] == "brand" && segments[segments.size - 1] == "card"))
                    || segments[segments.size - 2] == "hotmodule")
                    || (Helper.prefs!!.getBoolean("switch_club", false) && segments[segments.size - 1] == "bind_club")
                    || (Helper.prefs!!.getBoolean("switch_goods", false) && segments[segments.size - 2] == "goods")
                    || (Helper.prefs!!.getBoolean("switch_article", false) && segments[segments.size - 1] == "recommendation")
                    || (Helper.prefs!!.getBoolean("switch_related", false) && segments[segments.size - 3] == "related_queries"))
                    || (Helper.prefs!!.getBoolean("switch_searchwords", false) && segments[segments.size - 1] == "entity_word")
                ) {
                    val response = WebResourceResponse(
                        "application/json",
                        "UTF-8",
                        ByteArrayInputStream("{}".toByteArray(Charset.defaultCharset())),
                    )
                    response.setStatusCodeAndReasonPhrase(200, "OK")
                    param.result = response
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (!Helper.prefs!!.getBoolean("switch_mainswitch", false)) {
                    return
                }
                val request = param.args[1] as WebResourceRequest?
                if (request == null || request.url == null) {
                    return
                }
                val segments = request.url.pathSegments
                if (segments.size > 2 && request.method == "GET") {
                    if (Helper.prefs!!.getBoolean("switch_searchwords", false) && segments[0] == "appview" && segments[segments.size - 2] == "answer") {
                        val response = param.result as WebResourceResponse?
                        if (response == null || response.data == null) {
                            return
                        }
                        try {
                            val input = response.data
                            val buffer = ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            var read: Int
                            while (true) {
                                read = input.read(chunk)
                                if (read == -1) {
                                    break
                                }
                                buffer.write(chunk, 0, read)
                            }
                            var content = String(buffer.toByteArray(), Charset.defaultCharset())
                            content = content.replace("\"searchWords\"", "\"searchWords_bak\"")
                            content = content.replace("\"search_words\"", "\"search_words_bak\"")
                            response.data = ByteArrayInputStream(content.toByteArray(Charset.defaultCharset()))
                            param.result = response
                        } catch (ignored: IOException) {
                        }
                    }
                }
            }
        })
    }

    companion object {
        @JvmField
        var shouldInterceptRequest: Method? = null
    }
}
