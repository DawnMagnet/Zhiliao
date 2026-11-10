package com.shatyuka.zhiliao.hooks

import android.graphics.Bitmap
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XC_MethodReplacement
import com.shatyuka.zhiliao.xposed.XposedBridge
import com.shatyuka.zhiliao.xposed.XposedHelpers
import java.lang.reflect.Method

class WebView : IHook {
    override fun getName(): String {
        return "WebView修改"
    }

    override fun init(classLoader: ClassLoader) {
        Helper.requireTarget(Helper.WebViewClientWrapper, "com.zhihu.android.app.mercury.web.WebViewClientWrapper")
        answerAppView = Helper.requireTarget(
            classLoader.loadClass("com.zhihu.android.answer.module.content.appview.AnswerAppView"),
            "com.zhihu.android.answer.module.content.appview.AnswerAppView",
        )
        canPreRender = getDeclaredMethodOrNull(answerAppView!!, "canPreRender")
        if (canPreRender != null && canPreRender!!.returnType !== Boolean::class.javaPrimitiveType!!) {
            canPreRender = null
        }
        onPageCommitVisible = getDeclaredMethodOrNull(
            Helper.WebViewClientWrapper!!,
            "onPageCommitVisible",
            android.webkit.WebView::class.java,
            String::class.java,
        )
    }

    override fun hook() {
        // 预渲染直接用缓存页面, 注入的 js 会被丢弃, 去水印与去订阅都会失效
        if (canPreRender != null && (Helper.prefs!!.getBoolean("switch_watermark", false) || Helper.prefs!!.getBoolean("switch_subscribe", false))) {
            XposedBridge.hookMethod(canPreRender!!, XC_MethodReplacement.returnConstant(false))
        }

        XposedBridge.hookAllConstructors(android.webkit.WebView::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (Helper.prefs!!.getBoolean("switch_webview_debug", false)) {
                    XposedHelpers.callStaticMethod(android.webkit.WebView::class.java, "setWebContentsDebuggingEnabled", true)
                }
            }
        })

        XposedHelpers.findAndHookMethod(
            Helper.WebViewClientWrapper!!,
            "onPageStarted",
            android.webkit.WebView::class.java,
            String::class.java,
            Bitmap::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    injectScript(param.args[0])
                }
            },
        )

        if (onPageCommitVisible != null) {
            XposedBridge.hookMethod(onPageCommitVisible!!, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    injectScript(param.args[0])
                }
            })
        }

        XposedHelpers.findAndHookMethod(
            Helper.WebViewClientWrapper!!,
            "onPageFinished",
            android.webkit.WebView::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    injectScript(param.args[0])
                    if (param.args[0] !is android.webkit.WebView) {
                        return
                    }
                    val js = Helper.prefs!!.getString("edit_js", null)
                    if (js != null) {
                        (param.args[0] as android.webkit.WebView).evaluateJavascript(js, null)
                    }
                }
            },
        )
    }

    companion object {
        /**
         * 水印由服务端页面 CSS 提供, 注入时 document 可能尚未就绪或即将被替换, 故立即执行 + DOMContentLoaded 兜底 + MutationObserver 重复应用
         */
        private const val script_hide_water_mark = "(function () {\n" +
            "    if (window.zhiliaoHideWaterMark) return;\n" +
            "    window.zhiliaoHideWaterMark = true;\n" +
            "    var css = '.App,html,body{background-image:none!important}' +\n" +
            "            '[class*=watermark],[class*=Watermark],[class*=waterMark],[class*=water-mark],[id*=watermark],[id*=Watermark],[id*=waterMark],[id*=water-mark]{background-image:none!important;background:none!important}';\n" +
            "    var apply = function () {\n" +
            "        var style = document.getElementById('zhiliaoHideWaterMark');\n" +
            "        if (!style) {\n" +
            "            style = document.createElement('style');\n" +
            "            style.id = 'zhiliaoHideWaterMark';\n" +
            "            style.textContent = css;\n" +
            "        }\n" +
            "        var parent = document.body || document.head || document.documentElement;\n" +
            "        if (parent && style.parentNode !== parent) parent.appendChild(style);\n" +
            "    };\n" +
            "    var observe = function () {\n" +
            "        if (!document.body) return;\n" +
            "        apply();\n" +
            "        new MutationObserver(apply).observe(document.body, {childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style']});\n" +
            "    };\n" +
            "    if (document.body) observe();\n" +
            "    else document.addEventListener('DOMContentLoaded', observe);\n" +
            "})();"

        private const val script_hide_subscribe = "let toolbar = document.getElementsByClassName('Toolbar-functionButtons')[0];\n" +
            "if (toolbar) {\n" +
            "    let subscribe = toolbar.lastChild;\n" +
            "    if (subscribe && subscribe.children) {\n" +
            "        for (let i = 0; i < subscribe.children.length; i++) {\n" +
            "            if (subscribe.children[i].classList.contains('Avatar')) {\n" +
            "                let styleSubscribe = document.createElement('style');\n" +
            "                styleSubscribe.innerHTML = '.' + subscribe.className + '{display:none!important}';\n" +
            "                document.body.append(styleSubscribe);\n" +
            "                break\n" +
            "            }\n" +
            "        };\n" +
            "    }\n" +
            "}\n" +
            "let authorCard = document.getElementsByClassName('UserLine AuthorCard')[0];\n" +
            "if (authorCard) {\n" +
            "    let subscribe2 = authorCard.lastChild;\n" +
            "    if (subscribe2 && subscribe2.tagName === 'BUTTON') {\n" +
            "        let styleSubscribe = document.createElement('style');\n" +
            "        styleSubscribe.innerHTML = '.' + subscribe2.className + '{display:none!important}';\n" +
            "        document.body.append(styleSubscribe);\n" +
            "    }\n" +
            "}"

        @JvmField
        var answerAppView: Class<*>? = null

        @JvmField
        var canPreRender: Method? = null

        @JvmField
        var onPageCommitVisible: Method? = null

        private fun getDeclaredMethodOrNull(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? {
            return try {
                val method = clazz.getDeclaredMethod(name, *parameterTypes)
                method.isAccessible = true
                method
            } catch (_: NoSuchMethodException) {
                null
            }
        }

        private fun injectScript(webview: Any?) {
            if (webview !is android.webkit.WebView) {
                return
            }
            val script = getScript(
                Helper.prefs!!.getBoolean("switch_watermark", false),
                Helper.prefs!!.getBoolean("switch_subscribe", false),
            )
            if (script.isEmpty()) {
                return
            }
            try {
                webview.evaluateJavascript(script, null)
            } catch (e: Throwable) {
                XposedBridge.log("[Zhiliao] 注入水印去除脚本失败: " + e)
            }
        }

        private fun getScript(hideWaterMark: Boolean, hideSubscribe: Boolean): String {
            val sb = StringBuilder()
            if (hideWaterMark) {
                sb.append(script_hide_water_mark)
                sb.append("\n")
            }
            if (hideSubscribe) {
                sb.append("document.addEventListener('DOMContentLoaded',()=>{\n")
                sb.append("if (window.zhiliaoHideSubscribe) return;\n")
                sb.append("window.zhiliaoHideSubscribe = true;\n")
                sb.append(script_hide_subscribe)
                sb.append("\n});")
            }
            return sb.toString()
        }
    }
}
