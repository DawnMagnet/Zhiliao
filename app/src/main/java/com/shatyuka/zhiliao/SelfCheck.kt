package com.shatyuka.zhiliao

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行期自证：记录"装了哪些 Hook 目标"和"哪些真的被调用过"。
 *
 * 只做记账与文案拼装，不碰 Android、不打印日志（日志由调用方给），
 * 这样 JVM 单测里能直接验证去重与统计，不用起设备。
 *
 * 治的是"签名全对、语义已变"的静默失败：装了不等于拦到了，
 * 只有真正命中过才算数。
 */
object SelfCheck {
    private val installed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val fired: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 把"功能|目标"拼成记账用的 key。Hook 安装时拼一次即可：
     * 命中路径只传 key，不再每次调用都做字符串拼接。
     */
    @JvmStatic
    fun keyOf(feature: String?, target: String?): String {
        return (if (feature == null || feature.isEmpty()) "?" else feature) + "|" + target
    }

    /** 记录一个已安装的目标（Hook 安装成功时调用）。 */
    @JvmStatic
    fun installed(feature: String?, target: String?) {
        installedKey(keyOf(feature, target))
    }

    /** 记录一次命中；首次返回 true，调用方据此只打一条日志。 */
    @JvmStatic
    fun fire(feature: String?, target: String?): Boolean {
        return fireKey(keyOf(feature, target))
    }

    /** 同 [installed]，但 key 已由 [keyOf] 预先拼好。 */
    @JvmStatic
    fun installedKey(key: String) {
        installed.add(key)
    }

    /** 同 [fire]，但 key 已由 [keyOf] 预先拼好——命中路径的常态。 */
    @JvmStatic
    fun fireKey(key: String): Boolean {
        return fired.add(key)
    }

    @JvmStatic
    fun installedCount(): Int = installed.size

    @JvmStatic
    fun firedCount(): Int = fired.size

    /** 已安装但从未触发的目标清单（按功能+目标排序）。 */
    @JvmStatic
    fun neverFired(): List<String> {
        val pending = LinkedHashSet(installed)
        pending.removeAll(fired)
        val result = ArrayList<String>(pending.size)
        for (entry in pending) {
            result.add(entry.replace('|', ' '))
        }
        Collections.sort(result)
        return result
    }

    /** 一行摘要，供延迟日志输出。 */
    @JvmStatic
    fun summary(): String {
        if (installed.isEmpty()) {
            return "尚未安装任何目标"
        }
        val pending = neverFired()
        if (pending.isEmpty()) {
            return "已装 " + installed.size + " 个目标，全部触发过"
        }
        return "已装 " + installed.size + " 个目标，未触发 " + pending.size + " 个：" + pending.joinToString(separator = "; ")
    }

    /** 单测复位。 */
    @JvmStatic
    fun reset() {
        installed.clear()
        fired.clear()
    }
}
