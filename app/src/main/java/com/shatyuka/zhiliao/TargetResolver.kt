package com.shatyuka.zhiliao

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Arrays
import java.util.Comparator
import java.util.LinkedHashSet

object TargetResolver {
    fun interface ClassMatcher {
        @Throws(Exception::class)
        fun matches(clazz: Class<*>): Boolean
    }

    fun interface FieldMatcher {
        fun matches(field: Field): Boolean
    }

    fun interface MethodMatcher {
        fun matches(method: Method): Boolean
    }

    /**
     * 按候选名单解析方法名：依序尝试每个候选，必须同时通过签名验证器才算命中；
     * 全部落空返回 null（调用方再走结构化解析）。名单来自 CandidateRegistry。
     *
     * 与"第一个同名方法就用"的旧习惯不同，这里名字只是入场券，验证才是准绳——
     * 渠道包把混淆名挪给别人用时不会误绑。
     */
    @JvmStatic
    fun findMethodByCandidates(
        clazz: Class<*>?,
        registryKey: String,
        includeInherited: Boolean,
        verifier: MethodMatcher,
    ): Method? {
        val candidates = CandidateRegistry.candidates(registryKey)
        if (candidates == null || clazz == null) {
            return null
        }
        val methods = if (includeInherited) clazz.methods else clazz.declaredMethods
        for (candidate in ResolutionCache.prioritize(registryKey, candidates)!!) {
            for (method in methods) {
                if (candidate == method.name && verifier.matches(method)) {
                    method.isAccessible = true
                    ResolutionCache.remember(registryKey, candidate)
                    return method
                }
            }
        }
        return null
    }

    /**
     * 按候选名单收集**所有**通过的类：有些目标不是"选一个"，而是一串同构实现都要挂
     * （比如好几个版本的 Retrofit 转换器类同时存在于包里）。
     */
    @JvmStatic
    fun findClassesByQualifiedCandidates(
        classLoader: ClassLoader?,
        registryKey: String,
        verifier: ClassMatcher,
    ): List<Class<*>> {
        val result = ArrayList<Class<*>>()
        val candidates = CandidateRegistry.candidates(registryKey)
        if (candidates == null || classLoader == null) {
            return result
        }
        for (candidate in ResolutionCache.prioritize(registryKey, candidates)!!) {
            if (candidate.indexOf('.') < 0) {
                continue
            }
            try {
                val clazz = Class.forName(candidate, false, classLoader)
                if (verifier.matches(clazz)) {
                    result.add(clazz)
                }
            } catch (ignored: ClassNotFoundException) {
            } catch (ignored: LinkageError) {
            } catch (ignored: Exception) {
            }
        }
        return result
    }

    /** 按候选名单解析类名（全限定名的最后一段）。 */
    @JvmStatic
    fun findClassByCandidates(
        classLoader: ClassLoader,
        packageName: String,
        registryKey: String,
        verifier: ClassMatcher,
    ): Class<*>? {
        val candidates = CandidateRegistry.candidates(registryKey) ?: return null
        for (candidate in candidates) {
            try {
                val clazz = classLoader.loadClass("$packageName.$candidate")
                if (verifier.matches(clazz)) {
                    return clazz
                }
            } catch (ignored: ClassNotFoundException) {
            } catch (ignored: LinkageError) {
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    /**
     * 按候选名单解析全限定类名：混淆后同一个类在不同渠道包里会落在不同包，
     * 只有全名能表达这种差异。名单里不带点号的候选在这里直接跳过。
     */
    @JvmStatic
    fun findClassByQualifiedCandidates(
        classLoader: ClassLoader?,
        registryKey: String,
        verifier: ClassMatcher,
    ): Class<*>? {
        val candidates = CandidateRegistry.candidates(registryKey)
        if (candidates == null || classLoader == null) {
            return null
        }
        for (candidate in ResolutionCache.prioritize(registryKey, candidates)!!) {
            if (candidate.indexOf('.') < 0) {
                continue
            }
            try {
                val clazz = Class.forName(candidate, false, classLoader)
                if (verifier.matches(clazz)) {
                    ResolutionCache.remember(registryKey, candidate)
                    return clazz
                }
            } catch (ignored: ClassNotFoundException) {
            } catch (ignored: LinkageError) {
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    /**
     * 按名单找字段。字段名和类名一样随版本变（H5Event 里那个 params 就叫过 i / j / k），
     * 所以候选列表 + 类型验证，一个都不能少。
     */
    @JvmStatic
    fun findFieldByCandidates(clazz: Class<*>?, registryKey: String, verifier: FieldMatcher): Field? {
        val candidates = CandidateRegistry.candidates(registryKey)
        if (candidates == null || clazz == null) {
            return null
        }
        for (candidate in ResolutionCache.prioritize(registryKey, candidates)!!) {
            try {
                val field = clazz.getDeclaredField(candidate)
                if (verifier.matches(field)) {
                    field.isAccessible = true
                    ResolutionCache.remember(registryKey, candidate)
                    return field
                }
            } catch (ignored: NoSuchFieldException) {
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    @JvmStatic
    fun findObfuscatedClass(
        classLoader: ClassLoader,
        prefix: String,
        cycleStart: Int,
        cycleRound: Int,
        matcher: ClassMatcher,
    ): Class<*>? {
        val start = maxOf(0, cycleStart * 26)
        val end = maxOf(start, (cycleStart + cycleRound) * 26)
        for (i in start until end) {
            val names = LinkedHashSet<String>()
            names.add(prefix + legacyNameAt(i))
            if (i > 26) {
                names.add(prefix + compactNameAt(i))
            }
            for (name in names) {
                try {
                    val clazz = classLoader.loadClass(name)
                    if (matcher.matches(clazz)) {
                        return clazz
                    }
                } catch (ignored: ClassNotFoundException) {
                } catch (ignored: LinkageError) {
                } catch (ignored: Exception) {
                }
            }
        }
        return null
    }

    @JvmStatic
    @Throws(NoSuchMethodException::class)
    fun requireMethod(
        clazz: Class<*>?,
        includeInherited: Boolean,
        skip: Int,
        description: String,
        matcher: MethodMatcher,
    ): Method {
        val method = findMethod(clazz, includeInherited, skip, matcher)
        if (method == null) {
            throw NoSuchMethodException(clazz!!.name + "." + description)
        }
        return method
    }

    @JvmStatic
    fun findMethod(
        clazz: Class<*>?,
        includeInherited: Boolean,
        skip: Int,
        matcher: MethodMatcher,
    ): Method? {
        if (clazz == null) {
            return null
        }
        val methods = if (includeInherited) clazz.methods else clazz.declaredMethods
        Arrays.sort(
            methods,
            Comparator.comparing<Method, String> { it.name }
                .thenComparing { method: Method -> Arrays.toString(method.parameterTypes) },
        )
        var matched = 0
        for (method in methods) {
            if (!matcher.matches(method)) {
                continue
            }
            if (matched++ < skip) {
                continue
            }
            method.isAccessible = true
            return method
        }
        return null
    }

    @JvmStatic
    fun legacyNameAt(index: Int): String {
        if (index <= 0) {
            return ""
        }
        val result = StringBuilder()
        var value = index
        while (value > 0) {
            value--
            result.insert(0, ('a'.code + value % 26).toChar())
            value /= 26
        }
        return result.toString()
    }

    @JvmStatic
    fun compactNameAt(index: Int): String {
        if (index <= 0) {
            return ""
        }
        val value = index - 1
        val letter = ('a'.code + value % 26).toChar()
        val round = value / 26
        return if (round == 0) letter.toString() else letter.toString() + (round - 1)
    }
}
