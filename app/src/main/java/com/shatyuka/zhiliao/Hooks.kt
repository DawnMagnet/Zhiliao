package com.shatyuka.zhiliao

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.shatyuka.zhiliao.hooks.AnswerAd
import com.shatyuka.zhiliao.hooks.AnswerListAd
import com.shatyuka.zhiliao.hooks.AntiTracking
import com.shatyuka.zhiliao.hooks.Article
import com.shatyuka.zhiliao.hooks.AutoRefresh
import com.shatyuka.zhiliao.hooks.Cleaner
import com.shatyuka.zhiliao.hooks.ColorMode
import com.shatyuka.zhiliao.hooks.CommentAd
import com.shatyuka.zhiliao.hooks.CustomFilter
import com.shatyuka.zhiliao.hooks.ExternLink
import com.shatyuka.zhiliao.hooks.FeedAd
import com.shatyuka.zhiliao.hooks.FeedFloatAd
import com.shatyuka.zhiliao.hooks.FeedTopHotBanner
import com.shatyuka.zhiliao.hooks.FollowButton
import com.shatyuka.zhiliao.hooks.FullScreen
import com.shatyuka.zhiliao.hooks.HeadZoneBanner
import com.shatyuka.zhiliao.hooks.HotBanner
import com.shatyuka.zhiliao.hooks.IHook
import com.shatyuka.zhiliao.hooks.ImageSource
import com.shatyuka.zhiliao.hooks.LaunchAd
import com.shatyuka.zhiliao.hooks.MineHybridView
import com.shatyuka.zhiliao.hooks.NavButton
import com.shatyuka.zhiliao.hooks.NavRes
import com.shatyuka.zhiliao.hooks.NextAnswer
import com.shatyuka.zhiliao.hooks.RedDot
import com.shatyuka.zhiliao.hooks.SearchAd
import com.shatyuka.zhiliao.hooks.ShareAd
import com.shatyuka.zhiliao.hooks.StatusBar
import com.shatyuka.zhiliao.hooks.Tag
import com.shatyuka.zhiliao.hooks.ThirdPartyLogin
import com.shatyuka.zhiliao.hooks.VIPBanner
import com.shatyuka.zhiliao.hooks.Watermark
import com.shatyuka.zhiliao.hooks.WebView
import com.shatyuka.zhiliao.xposed.XposedBridge

object Hooks {
    /** 失败 → 设置项 的账本，见 HookStatus。 */
    @JvmField
    val status: HookStatus = HookStatus()

    @JvmField
    val hooks: Array<IHook> = arrayOf(
        LaunchAd(),
        CustomFilter(),
        FeedAd(),
        AnswerListAd(),
        CommentAd(),
        AnswerAd(),
        ShareAd(),
        NextAnswer(),
        RedDot(),
        ExternLink(),
        VIPBanner(),
        NavButton(),
        HotBanner(),
        ColorMode(),
        Article(),
        Tag(),
        SearchAd(),
        StatusBar(),
        ThirdPartyLogin(),
        NavRes(),
        WebView(),
        Cleaner(),
        FeedTopHotBanner(),
        HeadZoneBanner(),
        MineHybridView(),
        FollowButton(),
        FullScreen(),
        AutoRefresh(),
        ImageSource(),
        Watermark(),
        AntiTracking(),
        FeedFloatAd(),
    )

    @Volatile
    private var okCountValue: Int = 0

    @Volatile
    private var failedFeatures: String = ""

    private const val SELF_CHECK_DELAY_MS = 120_000L

    @JvmStatic
    fun init(classLoader: ClassLoader) {
        val verboseSelfCheck = Helper.prefs!!.getBoolean("switch_hooktrace", false)
        status.reset()
        val failed = StringBuilder()
        var ok = 0
        for (hook in hooks) {
            val before = XposedBridge.getHookCount()
            // 每条 Hook 归到自己功能名下，运行期"命中"日志才看得出是谁拦的
            XposedBridge.setCurrentFeature(hook.getName())
            try {
                hook.init(classLoader)
            } catch (e: Throwable) {
                reportFailure(hook, "目标定位", e)
                failed.append(hook.getName()).append("(定位) ")
                status.markUnsupported(hook.getName())
                continue
            }
            try {
                hook.hook()
                ok++
            } catch (e: Throwable) {
                reportFailure(hook, "Hook安装", e)
                failed.append(hook.getName()).append("(安装) ")
                status.markUnsupported(hook.getName())
            }
            XposedBridge.log("[Zhiliao][自检] 安装 " + hook.getName() + " 句柄=+" + (XposedBridge.getHookCount() - before))
        }
        XposedBridge.setCurrentFeature(null)
        okCountValue = ok
        failedFeatures = failed.toString().trim { it <= ' ' }
        XposedBridge.log(
            "[Zhiliao][自检] " + statusLine()
                + " Hook句柄=" + XposedBridge.getHookCount()
                + if (failed.isEmpty()) " 全部可用" else " 失败=$failed",
        )
        if (verboseSelfCheck) {
            scheduleSelfCheckSummary()
        }
    }

    /** 一行自检结论，日志与 App 回执共用。 */
    @JvmStatic
    fun statusLine(): String {
        return "知乎 " + Helper.packageInfo!!.versionName + "/" + Helper.versionCode +
            " 初始化成功=" + okCountValue + "/" + hooks.size +
            if (failedFeatures.isEmpty()) "" else " 失败=$failedFeatures"
    }

    @JvmStatic
    fun okCount(): Int = okCountValue

    @JvmStatic
    fun totalCount(): Int = hooks.size

    /** 当前知乎版本上装不起来的功能，它们负责的设置项（逗号分隔），供设置 App 置灰。 */
    @JvmStatic
    fun failedKeysLine(): String = status.failedKeysLine()

    /** 装完两分钟后回头看：哪些目标一次都没进过——"签名对、语义变了"就露在这里。 */
    private fun scheduleSelfCheckSummary() {
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                XposedBridge.log("[Zhiliao][自检] 运行 2 分钟回顾：" + SelfCheck.summary())
            }, SELF_CHECK_DELAY_MS)
        } catch (e: Throwable) {
            XposedBridge.log("[Zhiliao][自检] 延迟回顾不可用: $e")
        }
    }

    private fun reportFailure(hook: IHook, phase: String, error: Throwable) {
        if (!Helper.prefs!!.getBoolean("switch_hidetoast", false)) {
            Helper.toast(
                hook.getName() + "功能加载失败，可能不支持当前版本知乎: " + Helper.packageInfo!!.versionName,
                Toast.LENGTH_LONG,
            )
        }
        XposedBridge.log(
            "[Zhiliao][兼容性][" + Helper.packageInfo!!.versionName + "/" +
                Helper.versionCode + "][" + hook.getName() + "][" + phase + "] " + error,
        )
        XposedBridge.log(error)
    }
}
