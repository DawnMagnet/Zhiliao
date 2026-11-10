package com.shatyuka.zhiliao

import com.shatyuka.zhiliao.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.LinkedHashSet

/**
 * DexKit 兜底：候选名单和结构特征都没找到目标时，才轮到它直接扫 dex。
 *
 * 规则从严，宁可找不到也不猜：
 * - 必须限定搜索包名（只允许 com.zhihu.android 下），绝不整包乱扫；
 * - 必须至少给一个运行期字符串作为锚点（usingStrings），纯结构查询一律拒绝；
 * - 结果数超过上限直接判失败（命中一堆说明条件太松，这时候用哪个都是赌博）。
 *
 * 扫描完立刻 close，不让它挂在知乎的整个生命周期里。
 */
object DexKitFallback {
    /** 一次查询最多接受几个候选，超过就认为条件太松。 */
    private const val MAX_RESULTS = 8

    /** 精确签名查询允许多一点候选（调用方还要按类结构再筛），但仍有上限。 */
    private const val MAX_SIGNATURE_CANDIDATES = 64

    private val ALLOWED_PACKAGE_PREFIX = arrayOf("com.zhihu.android")

    private var loadAttempted = false
    private var loaded = false
    private var loadError = ""

    /**
     * 把 dexkit 的 .so 从模块 APK 里加载进来。DexKit 2.x 不自动加载原生库，
     * 而模块跑在知乎进程里，知乎自己的 nativeLibraryDirectories 里没有它，只能按绝对路径 load。
     */
    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loadAttempted) {
            return loaded
        }
        loadAttempted = true
        val modulePath = MainHook.modulePath
        if (modulePath == null) {
            loadError = "拿不到模块路径"
            return false
        }
        val dir = modulePath.substring(0, modulePath.lastIndexOf('/'))
        val candidates = arrayOf(
            "$modulePath!/lib/arm64-v8a/libdexkit.so",
            "$modulePath!/lib/armeabi-v7a/libdexkit.so",
            "$dir/lib/arm64/libdexkit.so",
            "$dir/lib/arm/libdexkit.so",
        )
        for (path in candidates) {
            try {
                System.load(path)
                loaded = true
                XposedBridge.log("[Zhiliao][兜底] DexKit 原生库已加载：$path")
                return true
            } catch (ignored: Throwable) {
            }
        }
        loadError = "libdexkit.so 加载失败"
        XposedBridge.log("[Zhiliao][兜底] $loadError，本条兜底路径不可用")
        return false
    }

    /**
     * 按"包名 + 运行期字符串"找类。找不到、超界、原生库没起来，一律返回空列表。
     *
     * @param usingStrings 至少一个运行期字符串；为空则拒绝执行
     * @param maxResults   期望的最大命中数，超过即放弃（上限被 MAX_RESULTS 再压一道）
     */
    @JvmStatic
    fun findClasses(usingStrings: String?, maxResults: Int): List<String> {
        if (usingStrings == null || usingStrings.trim { it <= ' ' }.isEmpty()) {
            XposedBridge.log("[Zhiliao][兜底] 拒绝无字符串锚点的查询")
            return ArrayList()
        }
        if (!ensureLoaded()) {
            return ArrayList()
        }
        val cap = minOf(if (maxResults <= 0) 1 else maxResults, MAX_RESULTS)
        var bridge: DexKitBridge? = null
        try {
            // 扫的是宿主（知乎）的 APK，不是模块自己的 APK——扫错了自然一个都命中不了
            val hostApk = Helper.context!!.applicationInfo.sourceDir
            bridge = DexKitBridge.create(hostApk)
            val query = FindClass.create().searchPackages(*ALLOWED_PACKAGE_PREFIX)
            query.matcher(ClassMatcher.create().usingStrings(usingStrings))
            val list = bridge.findClass(query)
            val names = LinkedHashSet<String>()
            for (data in list) {
                names.add(data.name)
                if (names.size > cap) {
                    XposedBridge.log("[Zhiliao][兜底] 命中超过 $cap 个，条件太松，放弃：$usingStrings")
                    return ArrayList()
                }
            }
            return ArrayList(names)
        } catch (e: Throwable) {
            XposedBridge.log("[Zhiliao][兜底] 查询失败 $usingStrings: $e")
            return ArrayList()
        } finally {
            if (bridge != null) {
                try {
                    bridge.close()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    /**
     * 按"精确签名"找方法。这里破例不要求字符串锚点：调用方已经把范围收窄到
     * "参数恰好是某个模型类、返回 void、限定在知乎自己的包"，结果还要再过一遍结构验证、
     * 并且必须唯一命中。用在那种类名每个渠道都不同、又没有共享明文字符串可用的数据层目标上
     * （FeedLocalDataSource 的缓存写入就是这一类：官网包的字符串是加密的，小米包是明文）。
     *
     * @param maxCandidates 还原成 Method 之前最多接受多少个候选项；超过直接放弃
     */
    @JvmStatic
    fun findMethodsBySignature(
        packages: Array<String>?,
        paramTypes: Array<Class<*>>?,
        returnType: Class<*>?,
        maxCandidates: Int,
        classLoader: ClassLoader?,
    ): List<Method> {
        if (paramTypes == null || paramTypes.isEmpty() || returnType == null) {
            XposedBridge.log("[Zhiliao][兜底] 拒绝空签名查询")
            return ArrayList()
        }
        if (!ensureLoaded()) {
            return ArrayList()
        }
        val cap = minOf(if (maxCandidates <= 0) 1 else maxCandidates, MAX_SIGNATURE_CANDIDATES)
        var bridge: DexKitBridge? = null
        val out = ArrayList<Method>()
        try {
            bridge = DexKitBridge.create(Helper.context!!.applicationInfo.sourceDir)
            val search = if (packages == null || packages.isEmpty()) ALLOWED_PACKAGE_PREFIX else packages
            val query = FindMethod.create().searchPackages(*search)
            val matcher = MethodMatcher()
            matcher.paramTypes(*paramTypes).paramCount(paramTypes.size).returnType(returnType)
            query.matcher(matcher)
            val list = bridge.findMethod(query)
            for (data in list) {
                val method = resolve(data, classLoader)
                if (method == null) {
                    continue
                }
                out.add(method)
                if (out.size > cap) {
                    XposedBridge.log("[Zhiliao][兜底] 签名查询命中超过 $cap 个，条件太松，放弃")
                    return ArrayList()
                }
            }
            return out
        } catch (e: Throwable) {
            XposedBridge.log("[Zhiliao][兜底] 签名查询失败: $e")
            return ArrayList()
        } finally {
            if (bridge != null) {
                try {
                    bridge.close()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    /** 把 dex 里查到的方法还原成运行期 Method；类还没装载就 loadClass，装不上就放弃。 */
    private fun resolve(data: MethodData, classLoader: ClassLoader?): Method? {
        try {
            val clazz = Class.forName(data.declaredClassName, false, classLoader)
            val paramNames = data.paramTypeNames
            val types = arrayOfNulls<Class<*>>(paramNames.size)
            for (i in types.indices) {
                types[i] = resolveType(paramNames[i], classLoader)
                if (types[i] == null) {
                    return null
                }
            }
            @Suppress("UNCHECKED_CAST")
            val method = clazz.getDeclaredMethod(data.methodName, *(types as Array<Class<*>>))
            method.isAccessible = true
            return method
        } catch (e: Throwable) {
            return null
        }
    }

    private fun resolveType(name: String, classLoader: ClassLoader?): Class<*>? {
        when (name) {
            "void" -> return Void.TYPE
            "boolean" -> return java.lang.Boolean.TYPE
            "byte" -> return java.lang.Byte.TYPE
            "char" -> return Character.TYPE
            "short" -> return java.lang.Short.TYPE
            "int" -> return Integer.TYPE
            "long" -> return java.lang.Long.TYPE
            "float" -> return java.lang.Float.TYPE
            "double" -> return java.lang.Double.TYPE
        }
        if (name.endsWith("[]")) {
            val component = resolveType(name.substring(0, name.length - 2), classLoader)
            return if (component == null) null else java.lang.reflect.Array.newInstance(component, 0).javaClass
        }
        try {
            return Class.forName(name, false, classLoader)
        } catch (e: Throwable) {
            return null
        }
    }

    /** 诊断用：确认 APK 到底有没有被解析、包名过滤能不能命中。 */
    @JvmStatic
    fun probe(): String {
        if (!ensureLoaded()) {
            return "原生库不可用：$loadError"
        }
        var bridge: DexKitBridge? = null
        val base: String
        try {
            bridge = DexKitBridge.create(Helper.context!!.applicationInfo.sourceDir)
            val dexNum = bridge.getDexNum()
            val byPackage = FindClass.create().searchPackages(*ALLOWED_PACKAGE_PREFIX)
            val total = bridge.findClass(byPackage).size
            val byName = FindClass.create()
            byName.matcher(ClassMatcher.create().className("com.zhihu.android.adbase.model.FeedAdvert"))
            val named = bridge.findClass(byName).size
            val bySuper = FindClass.create().searchPackages(*ALLOWED_PACKAGE_PREFIX)
            bySuper.matcher(ClassMatcher.create().superClass("androidx.recyclerview.widget.RecyclerView\$Adapter"))
            val adapters = bridge.findClass(bySuper)
            val byStrings = FindClass.create().searchPackages(*ALLOWED_PACKAGE_PREFIX)
            byStrings.matcher(ClassMatcher.create().usingStrings("Pull"))
            val pulls = bridge.findClass(byStrings).size
            base = "dex=" + dexNum + " 包内类=" + total + " 按全名命中=" + named +
                " RecyclerView.Adapter子类=" + adapters.size + " 含\"Pull\"=" + pulls
        } catch (e: Throwable) {
            return "探测失败：$e"
        } finally {
            if (bridge != null) {
                try {
                    bridge.close()
                } catch (ignored: Throwable) {
                }
            }
        }
        return base
    }

    @JvmStatic
    fun available(): Boolean = loaded

    @JvmStatic
    fun status(): String {
        return if (loaded) "可用" else if (loadError.isEmpty()) "未尝试" else loadError
    }
}
