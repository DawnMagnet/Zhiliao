package com.shatyuka.zhiliao.xposed

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * The subset of the legacy reflection helpers this module needs, rebuilt on plain reflection.
 */
object XposedHelpers {
    @JvmStatic
    fun findClass(className: String, classLoader: ClassLoader): Class<*> {
        try {
            return classLoader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            throw NoClassDefFoundError(className)
        }
    }

    @JvmStatic
    fun findMethodExact(clazz: Class<*>?, methodName: String, vararg parameterTypes: Class<*>): Method {
        if (clazz == null) {
            throw IllegalArgumentException("clazz must not be null")
        }
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val method = current.getDeclaredMethod(methodName, *parameterTypes)
                XposedBridge.makeAccessible(method)
                return method
            } catch (ignored: NoSuchMethodException) {
            }
            current = current.superclass
        }
        throw NoSuchMethodError(clazz.name + "#" + methodName + "(" + describe(parameterTypes) + ")")
    }

    @JvmStatic
    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg parameterTypesAndCallback: Any?) {
        val callback = callbackOf(parameterTypesAndCallback)
        val method = findMethodExact(clazz, methodName, *parameterTypesOf(parameterTypesAndCallback))
        XposedBridge.hookMethod(method, callback)
    }

    @JvmStatic
    fun findAndHookConstructor(clazz: Class<*>?, vararg parameterTypesAndCallback: Any?) {
        val callback = callbackOf(parameterTypesAndCallback)
        val parameterTypes = parameterTypesOf(parameterTypesAndCallback)
        var current = clazz
        while (current != null) {
            try {
                val constructor = current.getDeclaredConstructor(*parameterTypes)
                XposedBridge.makeAccessible(constructor)
                XposedBridge.hookMethod(constructor, callback)
                return
            } catch (ignored: NoSuchMethodException) {
            }
            current = current.superclass
        }
        throw NoSuchMethodError(clazz!!.name + "(" + describe(parameterTypes) + ")")
    }

    @JvmStatic
    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        return invoke(findMethodBestMatch(obj!!.javaClass, methodName, args), obj, *args)
    }

    @JvmStatic
    fun callStaticMethod(clazz: Class<*>?, methodName: String, vararg args: Any?): Any? {
        return invoke(findMethodBestMatch(clazz, methodName, args), null, *args)
    }

    private fun callbackOf(parameterTypesAndCallback: Array<out Any?>): XC_MethodHook {
        val last = if (parameterTypesAndCallback.isEmpty()) {
            null
        } else {
            parameterTypesAndCallback[parameterTypesAndCallback.size - 1]
        }
        if (last !is XC_MethodHook) {
            throw IllegalArgumentException("no XC_MethodHook callback defined")
        }
        return last
    }

    private fun parameterTypesOf(parameterTypesAndCallback: Array<out Any?>): Array<Class<*>> {
        val parameterTypes = arrayOfNulls<Class<*>>(parameterTypesAndCallback.size - 1)
        for (i in parameterTypes.indices) {
            val type = parameterTypesAndCallback[i]
            parameterTypes[i] = when (type) {
                is Class<*> -> type
                is String -> throw IllegalArgumentException("class names are not supported, use Class objects: $type")
                else -> throw IllegalArgumentException("invalid parameter type: $type")
            }
        }
        @Suppress("UNCHECKED_CAST")
        return parameterTypes as Array<Class<*>>
    }

    private fun findMethodBestMatch(clazz: Class<*>?, methodName: String, args: Array<out Any?>): Method {
        var current = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name == methodName && isAssignable(method.parameterTypes, args)) {
                    XposedBridge.makeAccessible(method)
                    return method
                }
            }
            current = current.superclass
        }
        throw NoSuchMethodError(clazz!!.name + "#" + methodName + "(" + describe(args) + ")")
    }

    private fun isAssignable(parameterTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (parameterTypes.size != args.size) {
            return false
        }
        for (i in parameterTypes.indices) {
            val arg = args[i]
            if (if (arg == null) parameterTypes[i].isPrimitive else !box(parameterTypes[i]).isInstance(arg)) {
                return false
            }
        }
        return true
    }

    private fun box(type: Class<*>): Class<*> {
        if (!type.isPrimitive) {
            return type
        }
        if (type === java.lang.Boolean.TYPE) return java.lang.Boolean::class.java
        if (type === java.lang.Byte.TYPE) return java.lang.Byte::class.java
        if (type === java.lang.Character.TYPE) return java.lang.Character::class.java
        if (type === java.lang.Short.TYPE) return java.lang.Short::class.java
        if (type === Integer.TYPE) return java.lang.Integer::class.java
        if (type === java.lang.Long.TYPE) return java.lang.Long::class.java
        if (type === java.lang.Float.TYPE) return java.lang.Float::class.java
        if (type === java.lang.Double.TYPE) return java.lang.Double::class.java
        return type
    }

    private fun describe(types: Array<out Class<*>>): String {
        val builder = StringBuilder()
        for (type in types) {
            if (builder.isNotEmpty()) {
                builder.append(", ")
            }
            builder.append(type.name)
        }
        return builder.toString()
    }

    private fun describe(args: Array<out Any?>): String {
        val builder = StringBuilder()
        for (arg in args) {
            if (builder.isNotEmpty()) {
                builder.append(", ")
            }
            builder.append(if (arg == null) "null" else arg.javaClass.name)
        }
        return builder.toString()
    }

    private fun invoke(method: Method, obj: Any?, vararg args: Any?): Any? {
        try {
            return method.invoke(obj, *args)
        } catch (e: IllegalAccessException) {
            throw IllegalAccessError(e.message)
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            if (cause is RuntimeException) {
                throw cause
            }
            if (cause is Error) {
                throw cause
            }
            throw IllegalStateException(cause)
        }
    }
}
