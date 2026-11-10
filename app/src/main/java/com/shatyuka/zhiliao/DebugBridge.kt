package com.shatyuka.zhiliao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.util.Collections
import java.util.HashMap

/**
 * 调试入口：在知乎进程里挂一个广播接收器，用 adb 命令直接改开关，不用点界面。
 *
 * <pre>
 * adb shell am broadcast -a com.shatyuka.zhiliao.DEBUG --es cmd list
 * adb shell am broadcast -a com.shatyuka.zhiliao.DEBUG --es cmd set --es key switch_video --es value true
 * adb shell am broadcast -a com.shatyuka.zhiliao.DEBUG --es cmd restart
 * </pre>
 *
 * 覆盖值写进知乎本地的一份小配置（zhiliao_debug），读取时优先于远端偏好——
 * 不碰用户真正的设置，clear 一下就能回到原样。
 *
 * 接收器要求发送方持有 android.permission.DUMP：shell 和 root 天然有，
 * 普通 App 没有，所以这扇门只对 adb 开。
 */
object DebugBridge {
    const val ACTION = "com.shatyuka.zhiliao.DEBUG"

    private var overrides: SharedPreferences? = null

    @Volatile
    private var snapshot: Map<String, Any?> = Collections.emptyMap()

    @JvmStatic
    fun install(context: Context) {
        try {
            overrides = context.getSharedPreferences("zhiliao_debug", Context.MODE_PRIVATE)
            reloadSnapshot()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    handle(receiverContext, intent)
                }
            }
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    receiver,
                    filter,
                    "android.permission.DUMP",
                    null,
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                context.registerReceiver(receiver, filter, "android.permission.DUMP", null)
            }
            XposedBridge.log("[Zhiliao][调试] 命令行开关已就绪：$ACTION")
        } catch (e: Throwable) {
            XposedBridge.log("[Zhiliao][调试] 命令行开关不可用: $e")
        }
    }

    /** 调试覆盖值；没有则返回 null。读的是内存快照，热路径不做任何分配。 */
    @JvmStatic
    fun override(key: String?): Any? {
        return if (key == null) null else snapshot[key]
    }

    /** 全部覆盖值（只读视图）。 */
    @JvmStatic
    fun overrides(): Map<String, Any?> {
        return Collections.unmodifiableMap(snapshot)
    }

    private fun reloadSnapshot() {
        val current = overrides
        if (current == null) {
            snapshot = Collections.emptyMap()
            return
        }
        snapshot = HashMap(current.all)
    }

    private fun handle(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")
        try {
            if ("set" == cmd) {
                val key = intent.getStringExtra("key")
                if (key == null) {
                    XposedBridge.log("[Zhiliao][调试] set 缺少 key")
                    return
                }
                val raw = if (intent.hasExtra("value")) java.lang.String.valueOf(intent.extras!!.get("value")) else null
                if (raw == null) {
                    XposedBridge.log("[Zhiliao][调试] set 缺少 value")
                    return
                }
                val value = coerce(raw)
                val editor = overrides!!.edit()
                if (value is Boolean) {
                    editor.putBoolean(key, value)
                } else if (value is Int) {
                    editor.putInt(key, value)
                } else {
                    editor.putString(key, value as String)
                }
                editor.apply()
                reloadSnapshot()
                XposedBridge.log("[Zhiliao][调试] set $key = $raw（已生效于下一次读取）")
            } else if ("get" == cmd) {
                val key = intent.getStringExtra("key")
                XposedBridge.log("[Zhiliao][调试] get $key = " + Helper.prefs!!.all[key])
            } else if ("list" == cmd) {
                val all = snapshot
                XposedBridge.log("[Zhiliao][调试] 覆盖项 " + all.size + " 条:")
                for ((entryKey, entryValue) in all) {
                    XposedBridge.log("[Zhiliao][调试]   $entryKey = $entryValue")
                }
            } else if ("clear" == cmd) {
                overrides!!.edit().clear().apply()
                reloadSnapshot()
                XposedBridge.log("[Zhiliao][调试] 覆盖项已清空")
            } else if ("restart" == cmd) {
                XposedBridge.log("[Zhiliao][调试] 重启知乎进程")
                Process.killProcess(Process.myPid())
            } else if ("unsupported" == cmd) {
                // 预演"当前版本不支持"的界面：把某个功能记进置灰账本，设置页刷新后对应开关会变灰
                val feature = intent.getStringExtra("feature")
                if (feature == null || "clear" == feature) {
                    Hooks.status.reset()
                    XposedBridge.log("[Zhiliao][调试] 置灰账本已重置")
                } else {
                    Hooks.status.markUnsupported(feature)
                    XposedBridge.log("[Zhiliao][调试] 标记不支持：$feature → " + Hooks.status.failedKeysLine())
                }
            } else if ("dex" == cmd) {
                // 探针：验证 DexKit 兜底能不能在这台机器上跑起来，以及条件松紧是否合适
                val anchor = intent.getStringExtra("str")
                val max = intent.getIntExtra("max", 3)
                if (anchor == null || anchor.isEmpty()) {
                    XposedBridge.log("[Zhiliao][调试] dex 探测：" + DexKitFallback.probe())
                    return
                }
                val hits = DexKitFallback.findClasses(anchor, max)
                XposedBridge.log(
                    "[Zhiliao][调试] dex 查询 $anchor 上限=$max"
                        + " 命中=" + hits.size + " 状态=" + DexKitFallback.status(),
                )
                for (name in hits) {
                    XposedBridge.log("[Zhiliao][调试]   $name")
                }
            } else {
                XposedBridge.log("[Zhiliao][调试] 未知命令 $cmd（可用 set/get/list/clear/restart/unsupported/dex）")
            }
        } catch (e: Throwable) {
            XposedBridge.log("[Zhiliao][调试] $cmd 失败: $e")
        }
    }

    private fun coerce(raw: String): Any {
        if ("true".equals(raw, ignoreCase = true)) {
            return true
        }
        if ("false".equals(raw, ignoreCase = true)) {
            return false
        }
        try {
            return Integer.parseInt(raw)
        } catch (ignored: NumberFormatException) {
            return raw
        }
    }
}
