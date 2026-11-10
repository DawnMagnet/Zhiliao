package com.shatyuka.zhiliao

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.webkit.WebViewClient
import android.widget.Toast
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.Arrays
import java.util.Comparator
import java.util.HashMap

class Helper {
    fun interface IClassCheck {
        @Throws(Exception::class)
        fun check(clazz: Class<*>): Boolean
    }

    companion object {
        @JvmField
        var MorphAdHelper: Class<*>? = null

        @JvmField
        var AnswerPagerFragment: Class<*>? = null

        @JvmField
        var IZhihuWebView: Class<*>? = null

        @JvmField
        var WebViewClientWrapper: Class<*>? = null

        @JvmField
        var DataUnique_type: Field? = null

        @JvmField
        var regex_title: java.util.regex.Pattern? = null

        @JvmField
        var regex_author: java.util.regex.Pattern? = null

        @JvmField
        var regex_content: java.util.regex.Pattern? = null

        @JvmField
        var scale: Float = 0f

        @SuppressLint("StaticFieldLeak")
        @JvmField
        var context: Context? = null

        @JvmField
        var prefs: SharedPreferences? = null

        @JvmField
        var modRes: Resources? = null

        @JvmField
        var packageInfo: PackageInfo? = null

        @JvmField
        var versionCode: Int = 0

        @JvmField
        var officialZhihu: Boolean = true

        /** 渠道：Play 包有 split APK（splitSourceDirs 非空），国内包为整体 APK。混淆名差异主要由此产生。 */
        @JvmField
        var channel: String = "domestic"

        const val hookPackage = "com.zhihu.android"
        const val REMOTE_PREFS_GROUP = "zhiliao_preferences"
        const val ACTION_RESTART_ZHIHU = "com.shatyuka.zhiliao.action.RESTART_ZHIHU"
        const val ACTION_CLEAN_CACHE = "com.shatyuka.zhiliao.action.CLEAN_CACHE"

        /** App 发起的自检问询；知乎侧以有序广播回执的形式把状态塞回 result extras。 */
        const val ACTION_STATUS = "com.shatyuka.zhiliao.action.STATUS"
        const val RESTART_PERMISSION = "com.shatyuka.zhiliao.permission.RESTART_ZHIHU"

        private val signature = byteArrayOf(
            0xB6.toByte(), 0xF9.toByte(), 0x97.toByte(), 0xE3.toByte(), 0x82.toByte(),
            0x7B.toByte(), 0xE1.toByte(), 0x1A.toByte(), 0xF2.toByte(), 0xFA.toByte(),
            0x4A.toByte(), 0x15.toByte(), 0x3F.toByte(), 0xEA.toByte(), 0x3F.toByte(), 0xE6.toByte(),
            0x27.toByte(), 0x68.toByte(), 0x66.toByte(), 0x02.toByte(),
        )

        /** 自定义过滤的正则上限：超过这个长度直接判无效，不交给 Pattern 去赌。 */
        const val MAX_REGEX_LENGTH = 512

        /** @noinspection RedundantSuppression */
        @Suppress("DEPRECATION")
        @JvmStatic
        internal fun init(classLoader: ClassLoader, remotePreferences: SharedPreferences?): Boolean {
            try {
                // 远端偏好是框架侧给的，服务没就绪时会拿到 null。这时候不能带病往下走：
                // withDebugOverrides 的包装类会把 NPE 推迟到第一次读配置，最后整条初始化白跑，
                // 用户只看到「初始化失败」。直接退回只读调试覆盖的空偏好，功能照旧按默认值工作。
                val remote = if (remotePreferences == null) {
                    XposedBridge.log("[Zhiliao][自检] 远端偏好不可用，本次退回默认配置（开关读写都不生效）")
                    // 空文件即可：没人往里写，读到的一律是各 Hook 自己的默认值。
                    context!!.getSharedPreferences("zhiliao_unavailable", Context.MODE_PRIVATE)
                } else {
                    remotePreferences
                }
                // 候选名单（第一阶段）：内置 asset 永远兜底
                CandidateRegistry.initialize(if (modRes != null) modRes!!.assets else null)
                // 云端配置（第五阶段）：App 侧验签通过后写进远端偏好，只有比内置新才采纳。
                // Hook 侧不重做验签——这份值只有本模块 App（同签名）能写，写进来的已经是校验过的 payload。
                applyCloudCompatibility(remote)

                initSharedClasses(classLoader)

                // 配置通道：LSPosed 远端偏好（模块 App 写、Hook 侧只读）。
                // 旧版设置文件不再参与读取——只提示一次，让用户在新设置页重新确认。
                detectLegacyPreferences()
                // 命令行调试口：adb 发广播改开关，覆盖值优先于远端偏好
                DebugBridge.install(context!!)
                prefs = withDebugOverrides(remote)

                packageInfo = context!!.packageManager.getPackageInfo("com.zhihu.android", 0)
                versionCode = packageInfo!!.versionCode
                channel = if (packageInfo!!.applicationInfo != null && packageInfo!!.applicationInfo!!.splitSourceDirs != null) {
                    "play"
                } else {
                    "domestic"
                }

                attachResolutionCache()

                regex_title = compileRegex(prefs!!.getString("edit_title", ""))
                regex_author = compileRegex(prefs!!.getString("edit_author", ""))
                regex_content = compileRegex(prefs!!.getString("edit_content", ""))

                scale = context!!.resources.displayMetrics.density

                return true
            } catch (e: Exception) {
                XposedBridge.log("[Zhiliao] $e")
                XposedBridge.log(e)
                return false
            }
        }

        /** 读取顺序：命令行覆盖 > 远端偏好。Hook 侧不写配置，写入直接转给远端（远端本身也是只读的）。 */
        private fun withDebugOverrides(remote: SharedPreferences): SharedPreferences {
            return object : SharedPreferences {
                override fun getAll(): Map<String, *> {
                    val merged = HashMap<String, Any?>(remote.all)
                    merged.putAll(DebugBridge.overrides())
                    return merged
                }

                override fun getString(key: String?, defValue: String?): String? {
                    val value = DebugBridge.override(key)
                    return if (value is String) value else remote.getString(key, defValue)
                }

                override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? {
                    val value = DebugBridge.override(key)
                    if (value is Set<*>) {
                        @Suppress("UNCHECKED_CAST")
                        return value as Set<String>
                    }
                    return remote.getStringSet(key, defValues)
                }

                override fun getInt(key: String?, defValue: Int): Int {
                    val value = DebugBridge.override(key)
                    return if (value is Int) value else remote.getInt(key, defValue)
                }

                override fun getLong(key: String?, defValue: Long): Long {
                    val value = DebugBridge.override(key)
                    return if (value is Long) value else remote.getLong(key, defValue)
                }

                override fun getFloat(key: String?, defValue: Float): Float {
                    val value = DebugBridge.override(key)
                    return if (value is Float) value else remote.getFloat(key, defValue)
                }

                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    val value = DebugBridge.override(key)
                    return if (value is Boolean) value else remote.getBoolean(key, defValue)
                }

                override fun contains(key: String?): Boolean {
                    return DebugBridge.override(key) != null || remote.contains(key)
                }

                override fun edit(): SharedPreferences.Editor = remote.edit()

                override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
                    remote.registerOnSharedPreferenceChangeListener(listener)
                }

                override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
                    remote.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }
        }

        /** 云端兼容性配置：payload 与 revision 由设置 App 验签通过后写入远端偏好，只有比内置新才采纳。 */
        private fun applyCloudCompatibility(remote: SharedPreferences?) {
            try {
                if (remote == null) {
                    return
                }
                val payload = remote.getString("cloud_compatibility", null)
                val revision = remote.getInt("cloud_revision", 0)
                val builtin = CandidateRegistry.getRevision()
                if (payload == null || payload.isEmpty() || !CompatibilityEnvelope.isNewer(builtin, revision)) {
                    return
                }
                if (CandidateRegistry.install(payload)) {
                    XposedBridge.log(
                        "[Zhiliao][云端] 采纳兼容性配置 revision=" + CandidateRegistry.getRevision() +
                            "（内置 $builtin）",
                    )
                }
            } catch (e: Throwable) {
                XposedBridge.log("[Zhiliao][云端] 配置采纳失败，继续用内置名单: $e")
            }
        }

        /**
         * 旧版设置只做检测，不再默默生效。
         *
         * 老版本的开关写在知乎自己的数据目录（zhiliao_preferences.xml），Hook 侧是唯一读得到它的地方；
         * 现在两侧只认远端偏好，第一次发现旧文件时提示一次，请用户在新设置页重新确认。
         *
         * 之前是"远端缺键就回落旧文件"，结果是 App 按默认值显示全关、Hook 按旧文件里的开关干活
         * （旧配置里开着的过滤视频/过滤文章/禁止自动刷新就够让首页空着），
         * 用户看到的是一个"设置全关但首页空白"的知乎。
         */
        private fun detectLegacyPreferences() {
            try {
                val legacy = context!!.getSharedPreferences("zhiliao_preferences", Context.MODE_PRIVATE)
                val count = legacy.all.size
                if (count == 0) {
                    return
                }
                val local = context!!.getSharedPreferences("zhiliao_local", Context.MODE_PRIVATE)
                if (local.getBoolean("legacy_notice_shown", false)) {
                    return
                }
                local.edit().putBoolean("legacy_notice_shown", true).apply()
                XposedBridge.log("[Zhiliao][自检] 发现旧版设置 $count 项，读穿透已停用，以新设置页为准")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        context!!,
                        "知了：检测到旧版设置 $count 项，开关以新设置页为准，请重新确认",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Throwable) {
                XposedBridge.log("[Zhiliao] 旧版设置检测失败: $e")
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun initSharedClasses(classLoader: ClassLoader) {
            MorphAdHelper = loadClassOrNull(classLoader, "com.zhihu.android.morph.ad.utils.MorphAdHelper")
            AnswerPagerFragment = loadClassOrNull(classLoader, "com.zhihu.android.answer.module.pager.AnswerPagerFragment")
            try {
                IZhihuWebView = classLoader.loadClass("com.zhihu.android.app.mercury.api.IZhihuWebView")
            } catch (ignore: ReflectiveOperationException) {
                resolveWebViewFromSearch(classLoader)
            } catch (ignore: LinkageError) {
                resolveWebViewFromSearch(classLoader)
            }
            WebViewClientWrapper = findClass(
                classLoader,
                "com.zhihu.android.app.mercury.web.",
                0,
                3,
                IClassCheck { clazz -> clazz.superclass === WebViewClient::class.java },
            )
            try {
                val dataUnique = classLoader.loadClass("com.zhihu.android.api.model.template.DataUnique")
                DataUnique_type = dataUnique.getField("type")
            } catch (ignored: ReflectiveOperationException) {
                DataUnique_type = null
            } catch (ignored: LinkageError) {
                DataUnique_type = null
            }
        }

        private fun resolveWebViewFromSearch(classLoader: ClassLoader) {
            try {
                IZhihuWebView = classLoader.loadClass("com.zhihu.android.app.search.ui.widget.SearchResultLayout")
                    .getDeclaredField("c").type
            } catch (ignored: ReflectiveOperationException) {
                IZhihuWebView = null
            } catch (ignored: LinkageError) {
                IZhihuWebView = null
            }
        }

        private fun loadClassOrNull(classLoader: ClassLoader, name: String): Class<*>? {
            try {
                return classLoader.loadClass(name)
            } catch (ignored: ClassNotFoundException) {
                return null
            } catch (ignored: LinkageError) {
                return null
            }
        }

        @JvmStatic
        @Throws(ClassNotFoundException::class)
        fun <T> requireTarget(target: T?, description: String): T {
            if (target == null) {
                throw ClassNotFoundException(description)
            }
            return target
        }

        @JvmStatic
        fun compileRegex(regex: String?): java.util.regex.Pattern? {
            if (regex == null || regex.isEmpty()) {
                return null
            }
            if (regex.length > MAX_REGEX_LENGTH) {
                XposedBridge.log("[Zhiliao][自定义过滤] 正则长度 " + regex.length + " 超过上限 " + MAX_REGEX_LENGTH + "，本条已停用")
                return null
            }
            try {
                return java.util.regex.Pattern.compile(regex)
            } catch (ignore: java.util.regex.PatternSyntaxException) {
                return null
            }
        }

        /** @noinspection RedundantSuppression */
        @Suppress("DEPRECATION")
        @SuppressLint("DiscouragedPrivateApi")
        @Throws(Throwable::class)
        @JvmStatic
        internal fun getModuleRes(path: String): Resources {
            val assetManager = AssetManager::class.java.newInstance()
            AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).invoke(assetManager, path)
            return Resources(assetManager, null, null)
        }

        @JvmStatic
        fun getDarkMode(): Boolean {
            return (context!!.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

        @JvmStatic
        fun doRestart(context: Context) {
            try {
                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    val restartIntent = Intent.makeRestartActivityTask(launchIntent.component)
                    restartIntent.setPackage(context.packageName)
                    context.startActivity(restartIntent)
                    System.exit(0)
                }
            } catch (ignored: Exception) {
            }
        }

        @JvmStatic
        fun deleteDirectory(dirFile: File): Boolean {
            if (!dirFile.exists() || !dirFile.isDirectory) {
                return false
            }
            val files = dirFile.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isFile) {
                        val result = File(file.absolutePath).delete()
                        if (!result) return false
                    } else if (file.isDirectory) {
                        val result = deleteDirectory(file.absolutePath)
                        if (!result) return false
                    }
                }
            }
            return dirFile.delete()
        }

        @JvmStatic
        fun deleteDirectory(filePath: String): Boolean {
            return deleteDirectory(File(filePath))
        }

        @JvmStatic
        fun toast(text: CharSequence?, duration: Int) {
            val toast = Toast.makeText(context!!, "", duration)
            toast.setText("知了：$text")
            toast.show()
        }

        @JvmStatic
        fun findClass(classLoader: ClassLoader, beginWith: String, check: IClassCheck): Class<*>? {
            return findClass(classLoader, beginWith, 0, 1, check)
        }

        @JvmStatic
        fun findClass(
            classLoader: ClassLoader,
            beginWith: String,
            cycleStart: Int,
            cycleRound: Int,
            check: IClassCheck,
        ): Class<*>? {
            return TargetResolver.findObfuscatedClass(classLoader, beginWith, cycleStart, cycleRound) { clazz ->
                check.check(clazz)
            }
        }

        @JvmStatic
        fun getMethodByParameterTypes(clazz: Class<*>?, vararg parameterTypes: Class<*>): Method? {
            if (clazz == null) return null
            for (method in clazz.declaredMethods) {
                val types = method.parameterTypes
                if (Arrays.equals(types, parameterTypes)) {
                    return method
                }
            }
            return null
        }

        @JvmStatic
        fun getMethodByParameterTypes(clazz: Class<*>?, skip: Int, vararg parameterTypes: Class<*>): Method? {
            if (clazz == null) return null
            var count = 0
            val methods = clazz.declaredMethods
            Arrays.sort(methods, Comparator.comparing { method: Method -> method.name })
            for (method in methods) {
                val types = method.parameterTypes
                if (Arrays.equals(types, parameterTypes)) {
                    if (count < skip) {
                        count++
                        continue
                    }
                    return method
                }
            }
            return null
        }

        @JvmStatic
        fun findFieldByType(clazz: Class<*>, type: Class<*>): Field? {
            for (field in clazz.declaredFields) {
                if (field.type === type) {
                    field.isAccessible = true
                    return field
                }
            }
            return null
        }

        /** @noinspection RedundantSuppression */
        @Suppress("DEPRECATION")
        @Throws(PackageManager.NameNotFoundException::class)
        private fun getSignatures(context: Context): Array<Signature> {
            val pm = context.packageManager
            val sig: Array<Signature> = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val sign = pm.getPackageInfo(hookPackage, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                if (sign!!.hasMultipleSigners()) {
                    sign.apkContentsSigners
                } else {
                    sign.signingCertificateHistory
                }
            } else {
                pm.getPackageInfo(hookPackage, PackageManager.GET_SIGNATURES).signatures!!
            }
            return sig
        }

        /**
         * 解析缓存接盘：缓存写在知乎自己的数据目录（Hook 侧唯一的可写位置，远端偏好只读）。
         * 指纹 = 知乎 versionCode + 名单 revision + APK 的 size^mtime，任一变化整份作废。
         */
        private fun attachResolutionCache() {
            try {
                val fingerprint = versionCode.toString() + ":" + CandidateRegistry.getRevision() + ":" + apkStamp()
                val reused = ResolutionCache.attach(
                    PrefsResolutionStore(context!!.getSharedPreferences("zhiliao_resolution", Context.MODE_PRIVATE)),
                    fingerprint,
                )
                XposedBridge.log("[Zhiliao][自检] 解析缓存 指纹=$fingerprint 沿用上次=$reused")
            } catch (e: Throwable) {
                XposedBridge.log("[Zhiliao] 解析缓存不可用: $e")
            }
        }

        private fun apkStamp(): Long {
            try {
                val apk = File(packageInfo!!.applicationInfo!!.sourceDir!!)
                return apk.length() xor apk.lastModified()
            } catch (e: Throwable) {
                return 0L
            }
        }

        private class PrefsResolutionStore(private val target: SharedPreferences) : ResolutionCache.Store {
            override fun get(key: String?): String? = target.getString(key, null)

            override fun put(key: String?, value: String?) {
                target.edit().putString(key, value).apply()
            }

            override fun clear() {
                target.edit().clear().apply()
            }
        }

        @JvmStatic
        fun checkSignature(context: Context?): Boolean {
            try {
                val sig = getSignatures(context!!)
                val md = MessageDigest.getInstance("SHA1")
                for (s in sig) {
                    val dig = md.digest(s.toByteArray())
                    if (Arrays.equals(dig, signature)) {
                        return true
                    }
                }
                return false
            } catch (ignored: Throwable) {
                return false
            }
        }
    }
}
