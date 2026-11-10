package com.shatyuka.zhiliao.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Member

/**
 * Legacy style hook callback, bridged onto the libxposed API 102 interceptor chain by [XposedBridge].
 * The before/after callback model is kept so the hook implementations stay free of chain plumbing.
 */
abstract class XC_MethodHook {
    class MethodHookParam internal constructor(
        @JvmField var method: Member,
        @JvmField var thisObject: Any?,
        @JvmField var args: Array<Any?>,
    ) {
        private var resultValue: Any? = null
        private var throwableValue: Throwable? = null
        private var returnEarly: Boolean = false

        var result: Any?
            get() = resultValue
            set(value) {
                resultValue = value
                throwableValue = null
                returnEarly = true
            }

        var throwable: Throwable?
            get() = throwableValue
            set(value) {
                throwableValue = value
                resultValue = null
                returnEarly = true
            }

        fun hasThrowable(): Boolean = throwableValue != null

        @Throws(Throwable::class)
        fun getResultOrThrowable(): Any? {
            val thrown = throwableValue
            if (thrown != null) {
                throw thrown
            }
            return resultValue
        }

        internal fun isReturnEarly(): Boolean = returnEarly
    }

    @Throws(Throwable::class)
    protected open fun beforeHookedMethod(param: MethodHookParam) {
    }

    @Throws(Throwable::class)
    protected open fun afterHookedMethod(param: MethodHookParam) {
    }

    /**
     * Kotlin `protected` 对同包的非子类不可见，而 Java 的 protected 可见。
     * 安装逻辑在 [XposedBridge]，必须经这两个入口才能调到子类覆写的 before/after。
     */
    internal fun dispatchBefore(param: MethodHookParam) {
        beforeHookedMethod(param)
    }

    internal fun dispatchAfter(param: MethodHookParam) {
        afterHookedMethod(param)
    }

    class Unhook internal constructor(
        private val hookedMethod: Member,
        private val handle: XposedInterface.HookHandle,
    ) {
        fun getHookedMethod(): Member = hookedMethod

        fun unhook() {
            handle.unhook()
        }
    }
}
