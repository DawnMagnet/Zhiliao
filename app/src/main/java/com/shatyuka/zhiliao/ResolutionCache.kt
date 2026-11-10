package com.shatyuka.zhiliao

import java.util.LinkedHashSet

/**
 * 解析结果缓存（路线图第二阶段）。
 *
 * 缓存的是"上次哪个候选命中了"，下次冷启动先试它，省掉前面的空转；
 * 名单本身永远完整走一遍验证，缓存只改顺序、不跳过验证，也不改变"全落空才算失败"。
 *
 * 失效条件靠指纹：知乎 versionCode + 候选名单 revision + APK 的 size^mtime 任一变化，
 * 旧缓存整份作废——不需要任何清理逻辑，也不需要版本迁移代码。
 *
 * 存储是可插拔的：真机接 SharedPreferences（写在知乎自己的数据目录），
 * JVM 单测用内存表，默认空实现保证单测里不会碰盘。
 */
object ResolutionCache {
    interface Store {
        fun get(key: String?): String?

        fun put(key: String?, value: String?)

        fun clear()
    }

    private val NO_OP: Store = object : Store {
        override fun get(key: String?): String? = null

        override fun put(key: String?, value: String?) {
        }

        override fun clear() {
        }
    }

    @Volatile
    private var store: Store = NO_OP

    @Volatile
    private var fingerprintValue: String = ""

    private const val KEY_FINGERPRINT = "fingerprint"

    /**
     * 接入持久化存储。指纹与上次不同则清空旧缓存。
     * 返回是否沿用（true=同一份知乎版本+名单，缓存有效）。
     */
    @JvmStatic
    @Synchronized
    fun attach(newStore: Store?, newFingerprint: String?): Boolean {
        store = newStore ?: NO_OP
        val previous = store.get(KEY_FINGERPRINT)
        val reusable = newFingerprint != null && newFingerprint == previous
        if (!reusable) {
            store.clear()
            store.put(KEY_FINGERPRINT, newFingerprint)
        }
        fingerprintValue = newFingerprint ?: ""
        return reusable
    }

    /** 把缓存命中的候选挪到最前面；没有缓存则原样返回。 */
    @JvmStatic
    fun prioritize(registryKey: String?, candidates: Array<String>?): Array<String>? {
        val cached = store.get(registryKey)
        if (cached == null || candidates == null || candidates.size < 2) {
            return candidates
        }
        if (cached != candidates[0] && contains(candidates, cached)) {
            val ordered = arrayOfNulls<String>(candidates.size)
            ordered[0] = cached
            var index = 1
            for (candidate in candidates) {
                if (cached != candidate) {
                    ordered[index++] = candidate
                }
            }
            @Suppress("UNCHECKED_CAST")
            return ordered as Array<String>
        }
        return candidates
    }

    /** 记录某个 key 这次命中的候选。 */
    @JvmStatic
    fun remember(registryKey: String?, candidate: String?) {
        if (registryKey == null || candidate == null) {
            return
        }
        store.put(registryKey, candidate)
    }

    /** 当前缓存里记着的候选（单测与排障用）。 */
    @JvmStatic
    fun peek(registryKey: String?): String? = store.get(registryKey)

    @JvmStatic
    fun fingerprint(): String = fingerprintValue

    private fun contains(candidates: Array<String>, value: String): Boolean {
        val set = LinkedHashSet<String>()
        for (candidate in candidates) {
            set.add(candidate)
        }
        return set.contains(value)
    }

    /** 单测：换回空实现并清空指纹。 */
    @JvmStatic
    fun reset() {
        store = NO_OP
        fingerprintValue = ""
    }
}
