package com.shatyuka.zhiliao.hooks

interface IHook {
    fun getName(): String

    @Throws(Throwable::class)
    fun init(classLoader: ClassLoader)

    @Throws(Throwable::class)
    fun hook()
}
