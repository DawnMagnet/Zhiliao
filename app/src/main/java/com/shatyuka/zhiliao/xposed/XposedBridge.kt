package com.shatyuka.zhiliao.xposed

import android.util.Log
import com.shatyuka.zhiliao.SelfCheck
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Entry point for hook installation on top of libxposed API 102.
 * [attach] must be called with the module instance before any hook is installed.
 */
object XposedBridge {
    private const val TAG = "Zhiliao"

    private var framework: XposedInterface? = null

    private val hookCount = AtomicInteger()

    /** 当前正在安装 Hook 的功能名；安装时被截取进 lambda，运行期不再依赖它。 */
    @Volatile
    private var currentFeature: String? = null

    @JvmStatic
    fun attach(xposedInterface: XposedInterface?) {
        framework = xposedInterface
    }

    @JvmStatic
    fun getHookCount(): Int = hookCount.get()

    /** 由 Hooks 依次设置，让每条 Hook 归到自己功能名下，日志才读得懂。 */
    @JvmStatic
    fun setCurrentFeature(feature: String?) {
        currentFeature = feature
    }

    @JvmStatic
    fun log(text: String?) {
        val current = framework
        if (current != null) {
            current.log(Log.INFO, TAG, text!!)
        } else {
            System.err.println("[$TAG] $text")
        }
    }

    @JvmStatic
    fun log(throwable: Throwable?) {
        log(Log.getStackTraceString(throwable))
    }

    @JvmStatic
    fun hookMethod(hookMethod: Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
        return hookMethod(hookMethod, callback, currentFeature)
    }

    /**
     * 指定功能名的安装重载：后台线程补装 Hook 时 [currentFeature] 早已被清空，
     * 不能再靠那个全局变量，否则自检会记到"?"名下。
     */
    @JvmStatic
    fun hookMethod(hookMethod: Member, callback: XC_MethodHook, feature: String?): XC_MethodHook.Unhook {
        if (hookMethod !is Method && hookMethod !is Constructor<*>) {
            throw IllegalArgumentException("Only methods and constructors can be hooked: $hookMethod")
        }
        val current = framework ?: throw IllegalStateException("XposedBridge is not attached to the framework")
        val target = signatureOf(hookMethod)
        // 命中路径很热：记账用的 key 和"首次命中"日志都在安装时拼好，intercept 里只做一次 set.add
        val selfCheckKey = SelfCheck.keyOf(feature, target)
        val firedMessage = "[自检][命中] " + (if (feature == null) "" else "$feature ") + target
        SelfCheck.installedKey(selfCheckKey)
        val handle = current.hook(hookMethod as Executable)
            .intercept { chain -> intercept(callback, chain, selfCheckKey, firedMessage) }
        hookCount.incrementAndGet()
        return XC_MethodHook.Unhook(hookMethod, handle)
    }

    private fun signatureOf(member: Member): String {
        return member.declaringClass.name + "#" + member.name
    }

    @JvmStatic
    fun hookAllMethods(hookClass: Class<*>, methodName: String, callback: XC_MethodHook): Set<XC_MethodHook.Unhook> {
        val unhooks = LinkedHashSet<XC_MethodHook.Unhook>()
        for (method in hookClass.declaredMethods) {
            if (method.name == methodName) {
                makeAccessible(method)
                unhooks.add(hookMethod(method, callback))
            }
        }
        return unhooks
    }

    @JvmStatic
    fun hookAllConstructors(hookClass: Class<*>, callback: XC_MethodHook): Set<XC_MethodHook.Unhook> {
        val unhooks = LinkedHashSet<XC_MethodHook.Unhook>()
        for (constructor in hookClass.declaredConstructors) {
            makeAccessible(constructor)
            unhooks.add(hookMethod(constructor, callback))
        }
        return unhooks
    }

    @JvmStatic
    internal fun makeAccessible(executable: Executable) {
        try {
            executable.isAccessible = true
        } catch (ignored: Throwable) {
        }
    }

    @Throws(Throwable::class)
    private fun intercept(
        callback: XC_MethodHook,
        chain: XposedInterface.Chain,
        selfCheckKey: String,
        firedMessage: String,
    ): Any? {
        val param = XC_MethodHook.MethodHookParam(
            chain.executable,
            chain.thisObject,
            chain.args.toTypedArray(),
        )
        if (SelfCheck.fireKey(selfCheckKey)) {
            // 每个目标只记第一条：这是"确实拦到了"的证据，也是"装了却没进这里"的对照
            log(firedMessage)
        }

        try {
            callback.dispatchBefore(param)
        } catch (t: Throwable) {
            log(t)
        }

        if (!param.isReturnEarly()) {
            try {
                val result = chain.proceed(param.args)
                // A constructor has no return value, its chain result is the instance being created.
                param.result = if (chain.executable is Constructor<*>) null else result
            } catch (t: Throwable) {
                param.throwable = t
            }
        }

        try {
            callback.dispatchAfter(param)
        } catch (t: Throwable) {
            log(t)
        }

        return param.getResultOrThrowable()
    }
}
