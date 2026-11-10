package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Method

class FeedTopHotBanner : IHook {
    override fun getName(): String {
        return "隐藏推荐页置顶热门"
    }

    override fun init(classLoader: ClassLoader) {
        try {
            feedTopHotAutoJacksonDeserializer = classLoader.loadClass("com.zhihu.android.api.model.FeedTopHotAutoJacksonDeserializer")
        } catch (ignore: ClassNotFoundException) {
        }
        if (feedTopHotAutoJacksonDeserializer == null) {
            return
        }

        // deserialize 已上移到父类 BaseObjectStdDeserializer，本类只声明构造器与 processMember
        for (method in feedTopHotAutoJacksonDeserializer!!.superclass!!.declaredMethods) {
            if (method.name == "deserialize" && method.parameterTypes.size == 2) {
                method.setAccessible(true)
                deserialize = method
                break
            }
        }
        if (deserialize == null) {
            throw NoSuchMethodException("com.zhihu.android.autojackson.BaseObjectStdDeserializer.deserialize")
        }
    }

    override fun hook() {
        if (deserialize == null) {
            return
        }
        XposedBridge.hookMethod(deserialize!!, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 父类方法被所有 AutoJackson 反序列化复用，必须限定目标类型，否则会清空全站数据
                val thisObject = param.thisObject
                if (thisObject == null || thisObject.javaClass !== feedTopHotAutoJacksonDeserializer) {
                    return
                }
                if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_feedtophot", false)) {
                    param.result = null
                }
            }
        })
    }

    companion object {
        @JvmField
        var feedTopHotAutoJacksonDeserializer: Class<*>? = null

        @JvmField
        var deserialize: Method? = null
    }
}
