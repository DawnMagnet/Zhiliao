package com.shatyuka.zhiliao.hooks

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XC_MethodReplacement
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.EnumMap
import java.util.HashSet
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 分享链路的追踪点（11.10.0 静态审计，docs/tracking-audit-11.10.0.md），共六路：
 * 1. 分享链接拼参：sharecore.v.o 的三个静态拼参方法往 URL 追加 utm_medium=social、
 *    utm_content=select_word/longpic 等参数 —— 拦下后改为剥离全部 utm_* 与 share_code；
 * 2. 分享点击上报：分享面板每个按钮 onClick 都把按钮名 + getZaData() 交给埋点器；
 * 3. 分享回执：收到分享回跳后取 share_code/utm_psn 回传分享上报接口；
 * 4. 图片分享二维码：QR 码内容指向 api.zhihu.com 的 image_share/qrcode，服务端可把扫码与分享者关联；
 * 5. 打开他人分享链接：RouterPortalLegacyInterceptor 检测 utm_oi 后伪造一次虚拟分享页浏览；
 * 6. 总闸（双保险）：所有 Za 埋点经 LogRecorderImpl.record 入库、由 ZaNewUploadManager 批量上传，
 *    record 置空即全站埋点不再落库；ZaLogHandlerImpl.inQueue 是入库任务的直接入口，作为第二道闸。
 * 7. 分享卡二维码：图片来自 api.zhihu.com/image_share/qrcode?content=…，服务端会把 content
 *    缩成 s.zhihu.com 短链再编码（见 docs/tracking-audit-11.10.0.md §八），改参数无效；
 *    所以在 ZHDraweeView#setImageURI 处把图换成按干净链接本地编码的二维码（installLocalQrRenderer）。
 * 8. 分享链接兜底：拦剪贴板写入与分享 Intent 的文本，按正则认知乎链接再剥参数（installTextCleaners）。
 */
class AntiTracking : IHook {
    override fun getName(): String {
        return "反追踪"
    }

    override fun init(classLoader: ClassLoader) {
        Za = load(classLoader, "com.zhihu.android.za.Za")
        LogRecorderImpl = load(classLoader, "com.zhihu.android.za.model.LogRecorderImpl")
        ZaLogHandlerImpl = load(classLoader, "com.zhihu.android.za.model.loghandler.ZaLogHandlerImpl")
        ShareCoreVO = load(classLoader, "com.zhihu.android.library.sharecore.v.o")
        AbsSharable = load(classLoader, "com.zhihu.android.library.sharecore.AbsSharable")
        ShareCoreUD = load(classLoader, "com.zhihu.android.library.sharecore.u.d")
        SharePortalHandlerImpl = load(classLoader, "com.zhihu.android.library.sharecore.SharePortalHandlerImpl")
        ZABean = load(classLoader, "com.zhihu.android.library.sharecore.model.ZABean")
        RxObservable = load(classLoader, "io.reactivex.Observable")

        if (Za == null && ShareCoreVO == null) {
            throw ClassNotFoundException("com.zhihu.android.za.Za")
        }

        if (RxObservable != null) {
            Observable_empty = RxObservable!!.getMethod("empty")
        }

        if (LogRecorderImpl != null) {
            LogRecorderImpl_record = findMethod(LogRecorderImpl, "record", 1, Void.TYPE)
        }

        // 第二道闸：入库任务（ZaEnqueueTask）的直接入口，防上层漏斗被绕过
        if (ZaLogHandlerImpl != null) {
            ZaLogHandlerImpl_inQueue = findMethod(ZaLogHandlerImpl, "inQueue", 1, Void.TYPE)
        }

        if (ShareCoreVO != null) {
            VO_utmAdders = findStringAppenders(ShareCoreVO!!)
            VO_reportSenders = findObservableSenders(ShareCoreVO!!)
            VO_receiveReport = findMethod(ShareCoreVO, "h", 1, Void.TYPE)
        }

        if (ShareCoreUD != null) {
            UD_reportSenders = findObservableSenders(ShareCoreUD!!)
        }

        if (AbsSharable != null && ZABean != null) {
            for (method in AbsSharable!!.declaredMethods) {
                if (method.parameterCount == 0 && method.returnType === ZABean) {
                    method.isAccessible = true
                    AbsSharable_getZaData = method
                    break
                }
            }
        }

        if (SharePortalHandlerImpl != null) {
            for (method in SharePortalHandlerImpl!!.declaredMethods) {
                val types = method.parameterTypes
                if (types.size == 2 && types[0] === Intent::class.java && types[1] === Uri::class.java
                    && method.returnType === Void.TYPE
                ) {
                    method.isAccessible = true
                    SharePortalHandlerImpl_handleReport = method
                    break
                }
            }
        }

        // 文本级落点：系统类，名字不会随渠道包变。分享链接无论由谁拼出来，最后都要经过这里。
        val clipboard = load(classLoader, "android.content.ClipboardManager")
        if (clipboard != null) {
            Clipboard_setPrimaryClip = findMethod(clipboard, "setPrimaryClip", 1, Void.TYPE)
        }
        val intentCls = load(classLoader, "android.content.Intent")
        if (intentCls != null) {
            val putters = ArrayList<Method>()
            for (method in intentCls.declaredMethods) {
                val types = method.parameterTypes
                if ("putExtra" != method.name || types.size != 2 || types[0] !== String::class.java) {
                    continue
                }
                if (types[1] === String::class.java || types[1] === CharSequence::class.java) {
                    method.isAccessible = true
                    putters.add(method)
                }
            }
            Intent_textPutters = putters.toTypedArray()
            // putExtras 有两份重载（Bundle 和 Intent），返回类型一样，必须按参数类型点明是 Bundle 那份。
            for (method in intentCls.declaredMethods) {
                if ("putExtras" == method.name && method.parameterCount == 1
                    && method.parameterTypes[0] === Bundle::class.java
                ) {
                    method.isAccessible = true
                    Intent_putExtras = method
                    break
                }
            }
        }

        // 二维码图片控件：知乎用的是自己的 ZHDraweeView，二维码走 setImageURI(String)。
        // String 那个重载可能定义在父类（Fresco 的 SimpleDraweeView）上，所以要顺着继承链找。
        ZHDraweeCls = load(classLoader, "com.zhihu.android.base.widget.ZHDraweeView")
        if (ZHDraweeCls != null) {
            val list = ArrayList<Method>()
            var clazz: Class<*>? = ZHDraweeCls
            while (clazz != null && clazz !== Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if ("setImageURI" != method.name || method.parameterCount != 1
                        || method.parameterTypes[0] !== String::class.java
                    ) {
                        continue
                    }
                    method.isAccessible = true
                    list.add(method)
                }
                clazz = clazz.superclass
            }
            ZHDrawee_setImageURI = list.toTypedArray()
        }

        // 可用性按「任何一项能干活」算：埋点、拼参、剪贴板、分享文本、本地二维码，
        // 只要有一项定位成功就继续装，不能因为分享相关类名漂移把还能用的功能一起废掉。
        val anyTarget = LogRecorderImpl_record != null
            || (VO_utmAdders != null && VO_utmAdders!!.isNotEmpty())
            || AbsSharable_getZaData != null
            || Clipboard_setPrimaryClip != null
            || Intent_textPutters.isNotEmpty()
            || ZHDrawee_setImageURI.isNotEmpty()
        if (!anyTarget) {
            throw NoSuchMethodException("AntiTracking: 无可用定位目标")
        }

        XposedBridge.log(
            "[Zhiliao][自检] 反追踪 埋点=" + (LogRecorderImpl_record != null)
                + " 拼参=" + (if (VO_utmAdders == null) 0 else VO_utmAdders!!.size)
                + " 上报=" + (if (VO_reportSenders == null) 0 else VO_reportSenders!!.size)
                + " 剪贴板=" + (Clipboard_setPrimaryClip != null)
                + " 分享文本=" + Intent_textPutters.size
                + " 本地二维码=" + ZHDrawee_setImageURI.size,
        )
    }

    override fun hook() {
        if (!Helper.prefs!!.getBoolean("switch_mainswitch", false) || !Helper.prefs!!.getBoolean("switch_antitracking", false)) {
            return
        }

        if (LogRecorderImpl_record != null) {
            XposedBridge.hookMethod(LogRecorderImpl_record!!, XC_MethodReplacement.returnConstant(null))
        }

        if (ZaLogHandlerImpl_inQueue != null) {
            XposedBridge.hookMethod(ZaLogHandlerImpl_inQueue!!, XC_MethodReplacement.returnConstant(null))
        }

        if (Za != null) {
            XposedBridge.hookAllMethods(Za!!, "pageShow", XC_MethodReplacement.returnConstant(null))
        }

        // 两个子开关默认跟随总开关打开，用户可以单独关掉其中一项。
        val cleanLink = Helper.prefs!!.getBoolean("switch_antitracking_link", true)
        val cleanQr = Helper.prefs!!.getBoolean("switch_antitracking_qr", true)

        replaceWithEmptyObservable(VO_reportSenders)
        replaceWithEmptyObservable(UD_reportSenders)

        if (cleanLink) {
            if (VO_utmAdders != null) {
                for (method in VO_utmAdders!!) {
                    installParamStripper(method)
                }
            }
        }

        if (VO_receiveReport != null) {
            XposedBridge.hookMethod(VO_receiveReport!!, XC_MethodReplacement.returnConstant(null))
        }

        if (SharePortalHandlerImpl_handleReport != null) {
            XposedBridge.hookMethod(SharePortalHandlerImpl_handleReport!!, XC_MethodReplacement.returnConstant(null))
        }

        if (AbsSharable_getZaData != null && ZABean != null) {
            XposedBridge.hookMethod(AbsSharable_getZaData!!, object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    return ZABean!!.getDeclaredConstructor().newInstance()
                }
            })
        }

        // 文本级净化：不认类名、不认方法名，只认"这串文本里有一条带追踪参数的知乎链接"。
        // 剪贴板和分享 Intent 是系统 API，渠道包怎么改名都绕不开。
        if (cleanLink) {
            installTextCleaners()
        }

        if (cleanQr) {
            installLocalQrRenderer()
        }
    }

    companion object {
        @JvmField
        var Za: Class<*>? = null

        @JvmField
        var LogRecorderImpl: Class<*>? = null

        @JvmField
        var ZaLogHandlerImpl: Class<*>? = null

        @JvmField
        var ShareCoreVO: Class<*>? = null

        @JvmField
        var AbsSharable: Class<*>? = null

        @JvmField
        var ShareCoreUD: Class<*>? = null

        @JvmField
        var SharePortalHandlerImpl: Class<*>? = null

        @JvmField
        var ZABean: Class<*>? = null

        @JvmField
        var RxObservable: Class<*>? = null

        @JvmField
        var LogRecorderImpl_record: Method? = null

        @JvmField
        var ZaLogHandlerImpl_inQueue: Method? = null

        @JvmField
        var VO_utmAdders: Array<Method>? = null

        @JvmField
        var VO_reportSenders: Array<Method>? = null

        @JvmField
        var VO_receiveReport: Method? = null

        @JvmField
        var UD_reportSenders: Array<Method>? = null

        @JvmField
        var AbsSharable_getZaData: Method? = null

        @JvmField
        var SharePortalHandlerImpl_handleReport: Method? = null

        @JvmField
        var Observable_empty: Method? = null

        /** 文本级净化的落点：剪贴板写入、分享 Intent 的文本 extra —— 都是系统类，不受混淆影响 */
        @JvmField
        var Clipboard_setPrimaryClip: Method? = null

        @JvmField
        var Intent_textPutters: Array<Method> = emptyArray()

        @JvmField
        var Intent_putExtras: Method? = null

        /** 二维码最终是画在图片控件上的，这里直接把本地生成的码塞进去，绕开服务端的短链 */
        @JvmField
        var ZHDraweeCls: Class<*>? = null

        @JvmField
        var ZHDrawee_setImageURI: Array<Method> = emptyArray()

        /** 分享文本里能认出来的知乎链接；不含空格与常见中英文标点，够用且不会贪婪吞掉后文 */
        private val ZHIHU_URL: Pattern = Pattern.compile(
            "(?i)https?://(?:[a-z0-9-]+\\.)*zhihu\\.com/[^\\s\"'<>()（）【】\\u3000，。；！？、]*",
        )

        /** 转发型短链：解出 target 参数，还原成真正的目标地址 */
        private val REDIRECT_URL: Pattern = Pattern.compile(
            "(?i)^https?://(?:link|s)\\.zhihu\\.com/[^#]*?[?&]target=([^&#]+)",
        )

        private val TRACKING_PARAMS: Set<String> = HashSet(
            listOf(
                "utm_source", "utm_medium", "utm_term", "utm_content", "utm_campaign", "utm_id",
                "utm_oi", "utm_psn", "utm_suid", "share_code", "share_from", "s_s_i", "s_r",
            ),
        )

        private val qrLogs = java.util.concurrent.atomic.AtomicInteger()
        private val qrFailLogs = java.util.concurrent.atomic.AtomicInteger()
        private val voLogs = java.util.concurrent.atomic.AtomicInteger()

        /** 被我们画过二维码的控件：卡片列表复用控件时要把背景擦掉，不然码会跟着列表跑。 */
        private val qrPainted: MutableSet<View> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

        /**
         * 服务端把 content 缩成 s.zhihu.com 短链再编码进二维码，所以在 URL 上怎么改都没用。
         * 这里等知乎把二维码地址交给图片控件时拦下来，本地编码一张干净的码直接塞进控件，
         * 既不发请求，也不经过知乎的图片地址处理（data: URI 那条路会崩）。
         */
        private fun installLocalQrRenderer() {
            for (method in ZHDrawee_setImageURI) {
                XposedBridge.hookMethod(method!!, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[0] !is String) {
                            return
                        }
                        val url = param.args[0] as String
                        if (!url.contains("image_share") || !url.contains("content=")) {
                            // 卡片列表会复用控件：上一张卡片画的码要擦掉，否则会跟着列表滚到别处。
                            val view = param.thisObject
                            if (view is View && qrPainted.remove(view)) {
                                view.setBackground(null)
                            }
                            return
                        }
                        try {
                            if (renderLocalQr(param, url)) {
                                param.result = null
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("[Zhiliao][反追踪] 本地二维码失败：" + e)
                        }
                    }
                })
            }
        }

        private fun renderLocalQr(param: MethodHookParam, url: String): Boolean {
            val uri = Uri.parse(url)
            val content = uri.getQueryParameter("content")
            if (content == null || content.isEmpty()) {
                qrFail("地址里没有 content")
                return false
            }
            val view = param.thisObject
            if (view !is View) {
                qrFail("不是 View：" + if (view == null) "null" else view.javaClass.name)
                return false
            }
            val clean = cleanUrl(content)!!
            val size = parseInt(uri.getQueryParameter("size"), 138)
            val fill = parseColor(uri.getQueryParameter("fill_color"), Color.BLACK)
            val back = parseColor(uri.getQueryParameter("back_color"), Color.WHITE)
            val density = view.resources.displayMetrics.density
            // 服务端给的 size 是 dp，换算成像素后再夹一层：二维码控件通常不到 200dp，
            // 万一哪天给了个离谱的值，也绝不能在这条线程上分配几百 MB。
            val px = java.lang.Math.min(java.lang.Math.max(java.lang.Math.round(size * density), 256), 768)
            val bitmap = qrBitmap(clean, px, fill, back) ?: return false
            val drawable: Drawable = BitmapDrawable(view.resources, bitmap)
            // 两条路一起走：hierarchy 上的方法名被 R8 改成 a/b/c/d，只能靠签名猜，
            // 猜错（设成了 placeholder 之类）时背景这层还能兜住，不会出现一片空白。
            val hierarchy = invokeNoArg(view, "getHierarchy")
            val applied = hierarchy != null && applyDrawable(hierarchy, drawable)
            view.setBackground(drawable)
            qrPainted.add(view)
            view.invalidate()
            if (qrLogs.getAndIncrement() < 5) {
                XposedBridge.log(
                    "[Zhiliao][反追踪] 本地二维码：" + shortUrl(clean)
                        + "（" + (if (applied) "hierarchy+背景" else "背景") + "）",
                )
            }
            return true
        }

        /**
         * Fresco 的 setImage/setPlaceholderImage 这些方法在 R8 之后全变成了 a/b/c/d，
         * 但签名骗不了人：一个 Drawable 参数、返回 void。这类方法全调一遍，
         * 谁是真的 setImage 都无所谓 —— 关键在于调用方还会额外铺一层背景，
         * 所以就算这里猜错（比如设成了 placeholder），码也不会消失。
         */
        private fun applyDrawable(hierarchy: Any, drawable: Drawable): Boolean {
            var applied = false
            for (method in hierarchy.javaClass.methods) {
                if (method.parameterCount != 1 || method.returnType !== Void.TYPE) {
                    continue
                }
                if (!method.parameterTypes[0].isAssignableFrom(BitmapDrawable::class.java)) {
                    continue
                }
                try {
                    method.isAccessible = true
                    method.invoke(hierarchy, drawable)
                    applied = true
                } catch (_: Throwable) {
                }
            }
            return applied
        }

        private fun qrFail(reason: String) {
            if (qrFailLogs.getAndIncrement() < 5) {
                XposedBridge.log("[Zhiliao][反追踪] 本地二维码失败：" + reason)
            }
        }

        private fun qrBitmap(content: String?, px: Int, fill: Int, back: Int): Bitmap? {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 1
            try {
                val matrix: BitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, hints)
                val width = matrix.width
                val height = matrix.height
                val pixels = IntArray(width * height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        pixels[y * width + x] = if (matrix.get(x, y)) fill else back
                    }
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                return bitmap
            } catch (e: Throwable) {
                XposedBridge.log("[Zhiliao][反追踪] 二维码编码失败：" + e)
                return null
            }
        }

        /** 调无参方法（getHierarchy 这类本身就是 0 参，别拿 1 参的查找器去找）。 */
        private fun invokeNoArg(target: Any, name: String): Any? {
            for (candidate in target.javaClass.methods) {
                if (name != candidate.name || candidate.parameterCount != 0) {
                    continue
                }
                try {
                    candidate.isAccessible = true
                    return candidate.invoke(target)
                } catch (e: Throwable) {
                    qrFail("调 " + name + " 失败：" + e.javaClass.simpleName)
                    return null
                }
            }
            return null
        }

        private fun parseInt(value: String?, fallback: Int): Int {
            return try {
                if (value == null) fallback else Integer.parseInt(value)
            } catch (_: Throwable) {
                fallback
            }
        }

        private fun parseColor(value: String?, fallback: Int): Int {
            return try {
                if (value == null || value.isEmpty()) fallback else Color.parseColor(value)
            } catch (_: Throwable) {
                fallback
            }
        }

        private fun installTextCleaners() {
            if (Clipboard_setPrimaryClip != null) {
                XposedBridge.hookMethod(Clipboard_setPrimaryClip!!, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[0] !is ClipData) {
                            return
                        }
                        val cleaned = cleanClip(param.args[0] as ClipData)
                        if (cleaned != null) {
                            param.args[0] = cleaned
                            logTextClean("剪贴板")
                        }
                    }
                })
            }

            for (putter in Intent_textPutters) {
                XposedBridge.hookMethod(putter!!, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (Intent.EXTRA_TEXT != param.args[0] || param.args[1] !is CharSequence) {
                            return
                        }
                        val original = param.args[1] as CharSequence
                        val cleaned = cleanShareText(original.toString())
                        if (cleaned != null && !cleaned.contentEquals(original)) {
                            param.args[1] = cleaned
                            logTextClean("分享文本")
                        }
                    }
                })
            }

            if (Intent_putExtras != null) {
                XposedBridge.hookMethod(Intent_putExtras!!, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[0] !is Bundle) {
                            return
                        }
                        val bundle = param.args[0] as Bundle
                        val text = bundle.getCharSequence(Intent.EXTRA_TEXT) ?: return
                        val cleaned = cleanShareText(text.toString())
                        if (cleaned != null && !cleaned.contentEquals(text)) {
                            bundle.putCharSequence(Intent.EXTRA_TEXT, cleaned)
                            logTextClean("分享文本")
                        }
                    }
                })
            }
        }

        private fun logTextClean(where: String) {
            if (voLogs.getAndIncrement() < 12) {
                XposedBridge.log("[Zhiliao][反追踪] " + where + " 已剥离追踪参数")
            }
        }

        /** 剪贴板里每条文本都过一遍；没有任何改动就返回 null，别去动原对象。 */
        private fun cleanClip(data: ClipData): ClipData? {
            var changed = false
            val texts = ArrayList<CharSequence?>()
            for (i in 0 until data.itemCount) {
                val item = data.getItemAt(i)
                // 图片、文件 URI、复制的 Intent 都不能重建（ClipData.Item 是只读的），
                // 一见这类条目就整个放手，宁可这条剪贴板不清洗。
                if (item.uri != null || item.intent != null) {
                    return null
                }
                val text = item.text
                if (text == null) {
                    texts.add(null)
                    continue
                }
                val cleaned = cleanShareText(text.toString())
                if (cleaned != null && !cleaned.contentEquals(text)) {
                    changed = true
                    texts.add(cleaned)
                } else {
                    texts.add(text)
                }
            }
            if (!changed || texts.isEmpty()) {
                return null
            }
            val description = data.description
            val label = description?.label
            val out = ClipData.newPlainText(label ?: "", texts[0])
            for (i in 1 until texts.size) {
                val text = texts[i]
                out.addItem(if (text == null) ClipData.Item("") else ClipData.Item(text))
            }
            return out
        }

        /**
         * 一段文本里可能夹着标题和链接（分享到微信常见"标题 - 知乎 https://…"），
         * 所以按正则逐条替换链接，其余原样保留。
         */
        @JvmStatic
        fun cleanShareText(text: String?): String? {
            if (text == null || text.isEmpty()) {
                return text
            }
            val matcher = ZHIHU_URL.matcher(text)
            val sb = StringBuilder()
            var changed = false
            while (matcher.find()) {
                val url = matcher.group()!!
                val cleaned = cleanUrl(url)!!
                if (cleaned != url) {
                    changed = true
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(cleaned))
            }
            if (!changed) {
                return text
            }
            matcher.appendTail(sb)
            return sb.toString()
        }

        /** 一条 URL：先解转发短链，再剥追踪参数。纯字符串处理，方便单测里直接跑。 */
        @JvmStatic
        fun cleanUrl(url: String?): String? {
            return stripTracking(unwrapRedirect(url))
        }

        /** link.zhihu.com / s.zhihu.com 这种转发跳板：把 target 解出来，换成真正的地址。 */
        private fun unwrapRedirect(url: String?): String? {
            if (url == null) {
                return null
            }
            val matcher = REDIRECT_URL.matcher(url)
            if (!matcher.find()) {
                return url
            }
            return try {
                val target = java.net.URLDecoder.decode(matcher.group(1), "UTF-8")
                if (target.startsWith("http")) target else url
            } catch (_: Throwable) {
                url
            }
        }

        private fun isTrackingParam(name: String?): Boolean {
            if (name == null) {
                return false
            }
            val lower = name.lowercase(Locale.ROOT)
            return TRACKING_PARAMS.contains(lower) || lower.startsWith("utm_")
        }

        @JvmStatic
        fun stripTracking(url: String?): String? {
            if (url == null || url.isEmpty()) {
                return url
            }
            val start = url.indexOf('?')
            if (start < 0 || start == url.length - 1) {
                return url
            }
            val head = url.substring(0, start)
            var rest = url.substring(start + 1)
            var fragment = ""
            val hash = rest.indexOf('#')
            if (hash >= 0) {
                fragment = rest.substring(hash)
                rest = rest.substring(0, hash)
            }
            val kept = StringBuilder()
            var dirty = false
            for (pair in rest.split("&")) {
                if (pair.isEmpty()) {
                    continue
                }
                val eq = pair.indexOf('=')
                val name = if (eq >= 0) pair.substring(0, eq) else pair
                if (isTrackingParam(percentDecode(name))) {
                    dirty = true
                    continue
                }
                if (kept.isNotEmpty()) {
                    kept.append('&')
                }
                kept.append(pair)
            }
            if (!dirty) {
                return url
            }
            return if (kept.isEmpty()) head + fragment else head + "?" + kept + fragment
        }

        private fun percentDecode(value: String): String {
            return try {
                java.net.URLDecoder.decode(value, "UTF-8")
            } catch (_: Throwable) {
                value
            }
        }

        /** 拼参方法：入参里的分享 URL 剥掉 utm_* 与 share_code 再返回。 */
        private fun installParamStripper(method: Method) {
            XposedBridge.hookMethod(method!!, object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    val result = stripTracking(param.args[0] as String?)
                    if (voLogs.getAndIncrement() < 12) {
                        XposedBridge.log(
                            "[Zhiliao][反追踪] 拼参 " + param.method.name
                                + " 输入=" + param.args[0] + " 输出=" + result,
                        )
                    }
                    return result
                }
            })
        }

        private fun shortUrl(url: String?): String {
            if (url == null) {
                return "null"
            }
            return if (url.length <= 200) url else url.substring(0, 200) + "…"
        }

        private fun replaceWithEmptyObservable(methods: Array<Method>?) {
            if (methods == null || Observable_empty == null) {
                return
            }
            for (method in methods) {
                XposedBridge.hookMethod(method!!, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? {
                        return Observable_empty!!.invoke(null)
                    }
                })
            }
        }

        /**
         * v.o 的三个拼参方法都是「静态、入参全为 String、返回 String」，按这个结构找，
         * 不依赖 b/c/d 这类会被渠道包改掉的名字。
         */
        private fun findStringAppenders(clazz: Class<*>): Array<Method> {
            val out = ArrayList<Method>()
            for (method in clazz.declaredMethods) {
                if (!java.lang.reflect.Modifier.isStatic(method.modifiers) || method.isSynthetic) {
                    continue
                }
                if (method.returnType !== String::class.java) {
                    continue
                }
                val types = method.parameterTypes
                // 只认单参：拼参方法的第一个参数就是要发出去的 URL，多参时返回值不一定是那个 URL，
                // 硬替换会把别的东西丢掉。
                if (types.size != 1 || types[0] !== String::class.java) {
                    continue
                }
                method.isAccessible = true
                out.add(method)
            }
            return out.toTypedArray()
        }

        /**
         * 上报方法的共同形态：入参一个 String 或 HashMap、返回 RxJava Observable。
         * 同类里的 0 参 Observable 方法是分享渠道配置拉取，不属于上报，不在此列。
         */
        private fun findObservableSenders(clazz: Class<*>): Array<Method>? {
            if (RxObservable == null) {
                return null
            }
            val out = ArrayList<Method>()
            for (method in clazz.declaredMethods) {
                if (method.returnType !== RxObservable) {
                    continue
                }
                val types = method.parameterTypes
                if (types.size == 1 && (types[0] === String::class.java || types[0] === java.util.HashMap::class.java)) {
                    method.isAccessible = true
                    out.add(method)
                }
            }
            return out.toTypedArray()
        }

        private fun findMethod(clazz: Class<*>?, name: String, paramCount: Int, returnType: Class<*>?): Method? {
            if (clazz == null) {
                return null
            }
            for (method in clazz.declaredMethods) {
                if (name == method.name && method.parameterCount == paramCount
                    && (returnType == null || method.returnType === returnType)
                ) {
                    method.isAccessible = true
                    return method
                }
            }
            return null
        }

        private fun load(classLoader: ClassLoader, name: String): Class<*>? {
            return try {
                classLoader.loadClass(name)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
