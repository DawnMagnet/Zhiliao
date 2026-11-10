package com.shatyuka.zhiliao

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import com.shatyuka.zhiliao.hooks.Cleaner
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XposedBridge
import com.shatyuka.zhiliao.xposed.XposedHelpers
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File

open class MainHook : XposedModule() {
    private external fun initNative()

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun tryLoadNative(showLog: Boolean) {
        val pathValue = modulePath
        if (pathValue == null) {
            if (showLog) {
                XposedBridge.log("[Zhiliao] 未获取到模块路径，无法加载native模块")
            }
            return
        }
        val path = pathValue.substring(0, pathValue.lastIndexOf('/'))
        val libs = arrayOf(
            "$path/lib/arm64/libzhiliao.so",
            "$path/lib/arm/libzhiliao.so",
            "$pathValue!/lib/arm64-v8a/libzhiliao.so",
            "$pathValue!/lib/armeabi-v7a/libzhiliao.so",
        )

        for (lib in libs) {
            try {
                System.load(lib)
                initNative()
                return
            } catch (ignored: Throwable) {
            }
        }

        if (showLog) {
            XposedBridge.log("[Zhiliao] 知了native模块加载失败")
        }
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XposedBridge.attach(this)
        processName = param.processName

        val moduleInfo = getModuleApplicationInfo()
        if (moduleInfo != null) {
            modulePath = if (moduleInfo.sourceDir != null) moduleInfo.sourceDir else moduleInfo.publicSourceDir
        }
        val loadedPath = modulePath
        if (loadedPath != null) {
            try {
                Helper.modRes = Helper.getModuleRes(loadedPath)
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (Helper.hookPackage != param.packageName) {
            return
        }

        val isMainProcess = Helper.hookPackage == processName
        val classLoader = param.defaultClassLoader
        val systemContext = XposedHelpers.callMethod(
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", classLoader),
                "currentActivityThread",
            ),
            "getSystemContext",
        ) as Context?
        if (!Helper.checkSignature(systemContext)) {
            tryLoadNative(isMainProcess)
            Helper.officialZhihu = false
        }

        if (!isMainProcess) {
            return
        }

        try {
            XposedBridge.hookAllConstructors(
                classLoader.loadClass("com.tencent.tinker.loader.app.TinkerApplication"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = 0
                    }
                },
            )
        } catch (ignored: ClassNotFoundException) {
        }

        XposedHelpers.findAndHookMethod(
            android.app.Instrumentation::class.java,
            "callApplicationOnCreate",
            Application::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args[0] is Application) {
                        Helper.context = (param.args[0] as Application).applicationContext

                        if (!Helper.init(classLoader, getRemotePreferences(Helper.REMOTE_PREFS_GROUP))) {
                            Helper.toast("知了初始化失败，可能不支持当前版本知乎: " + Helper.packageInfo!!.versionName, Toast.LENGTH_SHORT)
                        } else {
                            Hooks.init(classLoader)
                            registerModuleBroadcasts(classLoader)
                            if (!Helper.prefs!!.getBoolean("switch_mainswitch", false)) {
                                Helper.toast("知了加载成功，请打开知了App开启功能。", Toast.LENGTH_LONG)
                            }
                        }
                    }
                }
            },
        )

        XposedHelpers.findAndHookMethod(
            File::class.java,
            "exists",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val file = param.thisObject as File
                    if (file.name == ".allowXposed") {
                        param.result = true
                    }
                }
            },
        )
    }

    /** 模块 App → 知乎进程的单向指令通道：重启知乎、清理缓存。需模块持有对应签名权限。 */
    @Suppress("UNUSED_PARAMETER")
    private fun registerModuleBroadcasts(classLoader: ClassLoader) {
        try {
            val filter = IntentFilter()
            filter.addAction(Helper.ACTION_RESTART_ZHIHU)
            filter.addAction(Helper.ACTION_CLEAN_CACHE)
            filter.addAction(Helper.ACTION_STATUS)
            val context = Helper.context
            // Android 13+（targetSdk 33+）注册非系统广播必须声明 exported 与否。
            // 必须 EXPORTED 才收得到模块 App 发来的跨应用广播；安全性由 RESTART_PERMISSION 保证，
            // 只有同签名、持有该权限的发送方才能进来。
            val flags = Context.RECEIVER_EXPORTED
            context!!.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val action = intent.action
                        if (Helper.ACTION_RESTART_ZHIHU == action) {
                            Helper.doRestart(ctx)
                        } else if (Helper.ACTION_CLEAN_CACHE == action) {
                            val size = Cleaner.doClean()
                            XposedBridge.log("[Zhiliao][临时文件清理] App触发，清理 $size 字节")
                        } else if (Helper.ACTION_STATUS == action) {
                            // 有序广播回执：自检结果塞回 result extras，App 侧在 resultReceiver 里取
                            val extras = Bundle()
                            extras.putString("status", Hooks.statusLine())
                            extras.putInt("ok", Hooks.okCount())
                            extras.putInt("total", Hooks.totalCount())
                            extras.putString("failedKeys", Hooks.failedKeysLine())
                            extras.putString("zhihu", Helper.packageInfo!!.versionName + "/" + Helper.versionCode)
                            extras.putString("fingerprint", ResolutionCache.fingerprint())
                            setResultExtras(extras)
                            XposedBridge.log("[Zhiliao][自检] 已应答 App 问询：" + Hooks.statusLine())
                        }
                    }
                },
                filter,
                Helper.RESTART_PERMISSION,
                null,
                flags,
            )
        } catch (e: Throwable) {
            XposedBridge.log("[Zhiliao] 注册模块广播失败: $e")
        }
    }

    companion object {
        @JvmField
        var modulePath: String? = null

        @JvmField
        var processName: String? = null
    }
}
