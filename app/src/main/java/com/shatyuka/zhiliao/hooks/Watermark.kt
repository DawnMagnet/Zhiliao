package com.shatyuka.zhiliao.hooks

import android.view.View
import android.widget.ImageView
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XC_MethodReplacement
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 端内水印分布在多个子系统，这里只处理"客户端绘制"的部分：
 * 视频水印（VodWatermarkPlugin）与混合容器水印（tornado TDataWaterMark）的水印图都取自数据模型，
 * 把模型置空即从源头去掉；页面 WebView 的背景水印由 [WebView] 的 CSS 注入处理。
 * 服务端烘焙进图片字节的水印（watermark_src 换源问题）与真·盲水印不在可移除范围内。
 */
class Watermark : IHook {
    override fun getName(): String {
        return "移除水印"
    }

    override fun init(classLoader: ClassLoader) {
        // 这两个模型的成员名是 JSON 契约（logo / nick_name_url / logo_image / nick_image），
        // R8 不混淆它们，所以按名字定位在这里是安全的；任一缺失都只降级，不整条失败。
        WatermarkInfo = loadClassOrNull(classLoader, "com.zhihu.android.api.model.plugin.WatermarkInfo")
        if (WatermarkInfo != null) {
            WatermarkInfo_getLogoUrl = methodOrNull(WatermarkInfo!!, "getLogoUrl")
            WatermarkInfo_getNicknameUrl = methodOrNull(WatermarkInfo!!, "getNicknameUrl")
            WatermarkInfo_getWatermarked = methodOrNull(WatermarkInfo!!, "getWatermarked")
        }

        TDataWaterMark = loadClassOrNull(classLoader, "com.zhihu.android.tornado.data.TDataWaterMark")
        if (TDataWaterMark != null) {
            TDataWaterMark_setLogoImage = methodOrNull(TDataWaterMark!!, "setLogoImage", String::class.java)
            TDataWaterMark_setNickImage = methodOrNull(TDataWaterMark!!, "setNickImage", String::class.java)
        }

        VodWatermarkPlugin = loadClassOrNull(classLoader, "com.zhihu.android.media.plugin.VodWatermarkPlugin")
        if (VodWatermarkPlugin != null) {
            VodWatermarkPlugin_layoutWatermarkUI = methodOrNull(
                VodWatermarkPlugin!!,
                "layoutWatermarkUI",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
        }

        if (WatermarkInfo_getLogoUrl == null && TDataWaterMark_setNickImage == null && VodWatermarkPlugin_layoutWatermarkUI == null) {
            throw ClassNotFoundException("com.zhihu.android.api.model.plugin.WatermarkInfo")
        }
    }

    override fun hook() {
        if (!Helper.prefs!!.getBoolean("switch_mainswitch", false) || !Helper.prefs!!.getBoolean("switch_watermark", false)) {
            return
        }

        if (WatermarkInfo_getLogoUrl != null) {
            XposedBridge.hookMethod(WatermarkInfo_getLogoUrl!!, XC_MethodReplacement.returnConstant(null))
        }
        if (WatermarkInfo_getNicknameUrl != null) {
            XposedBridge.hookMethod(WatermarkInfo_getNicknameUrl!!, XC_MethodReplacement.returnConstant(null))
        }
        if (WatermarkInfo_getWatermarked != null) {
            XposedBridge.hookMethod(WatermarkInfo_getWatermarked!!, XC_MethodReplacement.returnConstant(false))
        }

        if (TDataWaterMark_setLogoImage != null) {
            XposedBridge.hookMethod(TDataWaterMark_setLogoImage!!, XC_MethodReplacement.returnConstant(null))
        }
        if (TDataWaterMark_setNickImage != null) {
            XposedBridge.hookMethod(TDataWaterMark_setNickImage!!, XC_MethodReplacement.returnConstant(null))
        }

        if (VodWatermarkPlugin_layoutWatermarkUI != null) {
            XposedBridge.hookMethod(VodWatermarkPlugin_layoutWatermarkUI!!, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    hideWatermarkImages(param.thisObject!!)
                }
            })
        }
    }

    companion object {
        @JvmField
        var WatermarkInfo: Class<*>? = null

        @JvmField
        var TDataWaterMark: Class<*>? = null

        @JvmField
        var VodWatermarkPlugin: Class<*>? = null

        @JvmField
        var WatermarkInfo_getLogoUrl: Method? = null

        @JvmField
        var WatermarkInfo_getNicknameUrl: Method? = null

        @JvmField
        var WatermarkInfo_getWatermarked: Method? = null

        @JvmField
        var TDataWaterMark_setLogoImage: Method? = null

        @JvmField
        var TDataWaterMark_setNickImage: Method? = null

        @JvmField
        var VodWatermarkPlugin_layoutWatermarkUI: Method? = null

        /**
         * 视图层兜底：数据层被服务端换字段绕过后，水印图仍可能被 inflate 出来。
         * 只隐藏 ImageView 子类（水印本身就是 ZHDraweeView），避免误伤同插件里的 rootView。
         */
        private fun hideWatermarkImages(plugin: Any) {
            for (field in plugin.javaClass.declaredFields) {
                if (!ImageView::class.java.isAssignableFrom(field.type)) {
                    continue
                }
                try {
                    field.isAccessible = true
                    val view = field.get(plugin) as View?
                    if (view != null && view.visibility != View.GONE) {
                        view.setVisibility(View.GONE)
                    }
                } catch (_: Throwable) {
                }
            }
        }

        private fun loadClassOrNull(classLoader: ClassLoader, name: String): Class<*>? {
            return try {
                classLoader.loadClass(name)
            } catch (_: Throwable) {
                null
            }
        }

        private fun methodOrNull(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? {
            return try {
                val method = clazz.getDeclaredMethod(name, *parameterTypes)
                method.isAccessible = true
                method
            } catch (_: Throwable) {
                null
            }
        }
    }
}
