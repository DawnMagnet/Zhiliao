package com.shatyuka.zhiliao.hooks

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.TargetResolver
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Arrays
import org.json.JSONObject

class ExternLink : IHook {
    override fun getName(): String {
        return "直接打开外部链接"
    }

    override fun init(classLoader: ClassLoader) {
        shouldOverrideUrlLoading = Helper.WebViewClientWrapper!!.getMethod(
            "shouldOverrideUrlLoading",
            WebView::class.java,
            WebResourceRequest::class.java,
        )

        // H5Event 的形状：自己声明了一个 JSONObject 字段（就是传给插件的那份参数）
        var h5Event = TargetResolver.findClassByQualifiedCandidates(
            classLoader,
            "externLink.h5Event",
        ) { clazz -> looksLikeH5Event(clazz) }
        if (h5Event == null) {
            try {
                h5Event = classLoader.loadClass("com.zhihu.android.app.mercury.api.a")
            } catch (e: ClassNotFoundException) {
                h5Event = classLoader.loadClass("com.zhihu.android.app.mercury.a.a")
            }
        }
        H5Event = h5Event

        val basePlugin2 = classLoader.loadClass("com.zhihu.android.app.mercury.plugin.BasePlugin2")
        var openUrlMethod = TargetResolver.findMethodByCandidates(
            basePlugin2,
            "externLink.openUrlMethod",
            false,
        ) { method ->
            method.returnType == Void.TYPE &&
                Arrays.equals(method.parameterTypes, arrayOf<Any?>(h5Event))
        }
        if (openUrlMethod == null) {
            openUrlMethod = basePlugin2.getMethod("openUrl", h5Event)
        }
        openUrl = openUrlMethod

        var intentUtils = TargetResolver.findClassByQualifiedCandidates(
            classLoader,
            "externLink.intentUtils",
        ) { clazz -> looksLikeIntentUtils(clazz) }
        if (intentUtils == null) {
            try {
                intentUtils = classLoader.loadClass("com.zhihu.android.app.router.IntentUtils")
            } catch (e: ClassNotFoundException) {
                intentUtils = classLoader.loadClass("com.zhihu.android.app.router.c")
            }
        }
        var openUrlMethod2 = findOpenUrlMethod(intentUtils)
        if (openUrlMethod2 == null) {
            openUrlMethod2 = intentUtils.getMethod(
                "openUrl",
                Context::class.java,
                Uri::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        }
        openUrl2 = openUrlMethod2

        var paramsField = TargetResolver.findFieldByCandidates(
            h5Event,
            "externLink.h5Params",
        ) { field ->
            JSONObject::class.java.isAssignableFrom(field.type)
        }
        if (paramsField == null) {
            paramsField = h5Event.getDeclaredField("i")
            paramsField.setAccessible(true)
        }
        H5Event_params = paramsField
    }

    override fun hook() {
        XposedBridge.hookMethod(shouldOverrideUrlLoading!!, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val request = param.args[1] as WebResourceRequest?
                val uri = request!!.url
                if ("link.zhihu.com" == uri.host) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && (Helper.prefs!!.getBoolean("switch_externlink", false) || Helper.prefs!!.getBoolean("switch_externlinkex", false))) {
                        val url = Uri.parse(uri.getQueryParameter("target"))
                        if (Helper.prefs!!.getBoolean("switch_externlinkex", false)) {
                            val intent = Intent(Intent.ACTION_VIEW, url)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            Helper.context!!.startActivity(intent)
                            param.result = true
                        } else {
                            val request2 = WebResourceRequestImpl(request)
                            request2.url = url
                            param.args[1] = request2
                        }
                    }
                }
            }
        })

        XposedBridge.hookMethod(openUrl!!, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val params = H5Event_params!!.get(param.args[0]) as JSONObject?
                val uri = Uri.parse(params!!.optString("url"))
                if ("link.zhihu.com" == uri.host) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && (Helper.prefs!!.getBoolean("switch_externlink", false) || Helper.prefs!!.getBoolean("switch_externlinkex", false))) {
                        val url = uri.getQueryParameter("target")
                        if (Helper.prefs!!.getBoolean("switch_externlinkex", false)) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            Helper.context!!.startActivity(intent)
                            param.result = true
                        } else {
                            params.put("url", url)
                        }
                    }
                }
            }
        })

        XposedBridge.hookMethod(openUrl2!!, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val uri = param.args[1] as Uri?
                if ("link.zhihu.com" == uri!!.host) {
                    if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && (Helper.prefs!!.getBoolean("switch_externlink", false) || Helper.prefs!!.getBoolean("switch_externlinkex", false))) {
                        val url = uri.getQueryParameter("target")
                        if (Helper.prefs!!.getBoolean("switch_externlinkex", false)) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            Helper.context!!.startActivity(intent)
                            param.result = true
                        } else {
                            param.args[1] = Uri.parse(url)
                        }
                    }
                }
            }
        })
    }

    private class WebResourceRequestImpl(request: WebResourceRequest) : WebResourceRequest {
        // @JvmField keeps this a field. A Kotlin property would generate getUrl() and clash with the interface.
        @JvmField
        var url: Uri = request.url
        private val forMainFrame: Boolean = request.isForMainFrame
        private val redirect: Boolean = request.isRedirect
        private val userGesture: Boolean = request.hasGesture()
        private val httpMethod: String = request.method
        private val headers: Map<String, String> = request.requestHeaders

        override fun getUrl(): Uri = url

        override fun isForMainFrame(): Boolean = forMainFrame

        override fun isRedirect(): Boolean = redirect

        override fun hasGesture(): Boolean = userGesture

        override fun getMethod(): String = httpMethod

        override fun getRequestHeaders(): Map<String, String> = headers
    }

    companion object {
        @JvmField
        var H5Event: Class<*>? = null

        @JvmField
        var shouldOverrideUrlLoading: Method? = null

        @JvmField
        var openUrl: Method? = null

        @JvmField
        var openUrl2: Method? = null

        @JvmField
        var H5Event_params: Field? = null

        private fun looksLikeH5Event(clazz: Class<*>): Boolean {
            for (field in clazz.declaredFields) {
                if (JSONObject::class.java.isAssignableFrom(field.type)) {
                    return true
                }
            }
            return false
        }

        /** IntentUtils 的形状：有一个 (Context, Uri, boolean, boolean) 的静态方法，不管它叫什么。 */
        private fun looksLikeIntentUtils(clazz: Class<*>): Boolean {
            return findOpenUrlMethod(clazz) != null
        }

        private fun findOpenUrlMethod(clazz: Class<*>): Method? {
            for (method in clazz.methods) {
                if (Modifier.isStatic(method.modifiers)
                    && method.returnType == Void.TYPE
                    && Arrays.equals(
                        method.parameterTypes,
                        arrayOf(
                            Context::class.java,
                            Uri::class.java,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                        ),
                    )
                ) {
                    return method
                }
            }
            return null
        }
    }
}
