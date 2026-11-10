package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 图片换源：端内有两条路径会主动挑服务端下发的水印变体——
 * 回答/文章正文默认取 ZRichImageBean.urls（水印变体），想法/动态取 PinContent.watermarkUrl。
 * 这里把展示 URL 换成无水印原图（originalUrls / originalUrl），只在原图存在时才替换，缺失即回退。
 * 仅对"服务端另发原图"的图片水印有效；像素级烘焙水印与真·盲水印无效，故默认关闭。
 */
class ImageSource : IHook {
    override fun getName(): String {
        return "图片换源去水印"
    }

    override fun init(classLoader: ClassLoader) {
        val zrichBean = loadClassOrNull(classLoader, "com.zhihu.android.zrichCore.model.bean.ZRichImageBean")
        val zrichExt = loadClassOrNull(classLoader, "com.zhihu.android.zrichCore.model.bean.ZRichImageBeanExtKt")
        if (zrichBean != null && zrichExt != null) {
            ZRichImageBean_originalUrls = listField(zrichBean, "originalUrls")
            if (ZRichImageBean_originalUrls != null) {
                for (method in zrichExt.declaredMethods) {
                    if (method.returnType === String::class.java && method.parameterCount == 1
                        && method.parameterTypes[0] === zrichBean && "getImageUrl" == method.name
                    ) {
                        method.isAccessible = true
                        getImageUrl = method
                        break
                    }
                }
            }
        }

        pinContentDeserializer = loadClassOrNull(classLoader, "com.zhihu.android.api.model.PinContentAutoJacksonDeserializer")
        if (pinContentDeserializer != null) {
            val pinContent = loadClassOrNull(classLoader, "com.zhihu.android.api.model.PinContent")
            if (pinContent != null) {
                PinContent_isWatermark = fieldOfType(pinContent, "isWatermark", Boolean::class.javaPrimitiveType!!)
                PinContent_watermarkUrl = fieldOfType(pinContent, "watermarkUrl", String::class.java)
            }
            // deserialize 声明在父类 BaseObjectStdDeserializer 上，本类只有构造器与 processMember
            val superclass = pinContentDeserializer!!.superclass
            if (superclass != null) {
                for (method in superclass.declaredMethods) {
                    if ("deserialize" == method.name && method.parameterCount == 2) {
                        method.isAccessible = true
                        pinContentDeserialize = method
                        break
                    }
                }
            }
        }

        if (getImageUrl == null && pinContentDeserialize == null) {
            throw ClassNotFoundException("ZRichImageBeanExtKt.getImageUrl / PinContentAutoJacksonDeserializer")
        }
    }

    override fun hook() {
        if (!Helper.prefs!!.getBoolean("switch_mainswitch", false) || !Helper.prefs!!.getBoolean("switch_imagesource", false)) {
            return
        }

        if (getImageUrl != null) {
            XposedBridge.hookMethod(getImageUrl!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args == null || param.args.isEmpty() || param.args[0] == null) {
                        return
                    }
                    val original = firstUrl(ZRichImageBean_originalUrls!!.get(param.args[0]))
                    if (original != null) {
                        param.result = original
                    }
                }
            })
        }

        if (pinContentDeserialize != null) {
            XposedBridge.hookMethod(pinContentDeserialize!!, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // 父类方法被所有 AutoJackson 反序列化复用，必须限定目标类型
                    val owner = param.thisObject
                    if (owner == null || owner.javaClass !== pinContentDeserializer) {
                        return
                    }
                    val content = param.result ?: return
                    try {
                        if (PinContent_isWatermark != null) {
                            PinContent_isWatermark!!.setBoolean(content, false)
                        }
                        if (PinContent_watermarkUrl != null) {
                            PinContent_watermarkUrl!!.set(content, null)
                        }
                    } catch (_: Throwable) {
                    }
                }
            })
        }
    }

    companion object {
        @JvmField
        var getImageUrl: Method? = null

        @JvmField
        var ZRichImageBean_originalUrls: Field? = null

        @JvmField
        var pinContentDeserializer: Class<*>? = null

        @JvmField
        var pinContentDeserialize: Method? = null

        @JvmField
        var PinContent_isWatermark: Field? = null

        @JvmField
        var PinContent_watermarkUrl: Field? = null

        private fun firstUrl(value: Any?): String? {
            if (value !is List<*>) {
                return null
            }
            for (item in value) {
                if (item is String && item.isNotEmpty()) {
                    return item
                }
            }
            return null
        }

        private fun listField(clazz: Class<*>, name: String): Field? {
            for (field in clazz.declaredFields) {
                if (List::class.java.isAssignableFrom(field.type) && field.name == name) {
                    field.isAccessible = true
                    return field
                }
            }
            return null
        }

        private fun fieldOfType(clazz: Class<*>, name: String, type: Class<*>): Field? {
            for (field in clazz.declaredFields) {
                if (field.type === type && field.name == name) {
                    field.isAccessible = true
                    return field
                }
            }
            return null
        }

        private fun loadClassOrNull(classLoader: ClassLoader, name: String): Class<*>? {
            return try {
                classLoader.loadClass(name)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
