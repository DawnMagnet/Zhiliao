package com.shatyuka.zhiliao.xposed

/**
 * Replaces the hooked method instead of wrapping it: [replaceHookedMethod] runs before,
 * and its return value becomes the result, so the original method is never invoked.
 */
abstract class XC_MethodReplacement : XC_MethodHook() {
    @Throws(Throwable::class)
    protected abstract fun replaceHookedMethod(param: MethodHookParam): Any?

    @Throws(Throwable::class)
    final override fun beforeHookedMethod(param: MethodHookParam) {
        param.result = replaceHookedMethod(param)
    }

    companion object {
        @JvmStatic
        fun returnConstant(result: Any?): XC_MethodReplacement {
            return object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    return result
                }
            }
        }
    }
}
