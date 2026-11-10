package com.shatyuka.zhiliao

import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Hook 失败 → 设置项 的映射账本。
 *
 * 某个功能在当前知乎版本上定位不到目标时，它驱动的那些开关就该在设置页里置灰；
 * 否则用户会对着一个永远不生效的开关反复尝试。这份映射是纯数据，不碰 Android API，
 * 所以能在 JVM 单测里直接把"哪条 Hook 该灰哪些键"钉死。
 */
class HookStatus {
    private val failedKeys = LinkedHashSet<String>()

    fun reset() {
        failedKeys.clear()
    }

    /** 记下这条功能挂了，它管的开关都要灰。未知功能（或没有独立开关的）静默跳过。 */
    fun markUnsupported(feature: String?) {
        val keys = FEATURE_KEYS[feature]
        if (keys != null) {
            failedKeys.addAll(keys.asList())
        }
    }

    /** 逗号分隔的键列表，塞进广播 extras 传给设置 App。 */
    fun failedKeysLine(): String {
        return failedKeys.joinToString(separator = ",")
    }

    fun size(): Int = failedKeys.size

    companion object {
        /** 功能名 → 它负责的设置项（第一个通常是主开关）。 */
        private val FEATURE_KEYS: LinkedHashMap<String, Array<String>> = LinkedHashMap<String, Array<String>>().apply {
            put("去启动页广告", arrayOf("switch_launchad"))
            put("自定义过滤", arrayOf("switch_video", "switch_removearticle", "switch_pin", "switch_marketcard"))
            put("去信息流广告", arrayOf("switch_feedad"))
            put("去浮层广告", arrayOf("switch_feedfloatad"))
            put("去回答列表广告", arrayOf("switch_answerlistad"))
            put("去评论广告", arrayOf("switch_commentad"))
            put("去回答底部广告", arrayOf("switch_answerad"))
            put("去分享广告", arrayOf("switch_sharead"))
            put("移除下一个回答按钮", arrayOf("switch_nextanswer"))
            put("不显示小红点", arrayOf("switch_reddot"))
            put("直接打开外部链接", arrayOf("switch_externlink", "switch_externlinkex"))
            put("隐藏会员卡片", arrayOf("switch_vipbanner"))
            put("隐藏导航栏按钮", arrayOf("switch_homenav", "switch_findnav", "switch_messagenav", "switch_profilenav", "switch_panelnav"))
            put("隐藏热点通知", arrayOf("switch_hotbanner"))
            put("禁止切换色彩模式", arrayOf("switch_colormode"))
            put("精简文章页面", arrayOf("switch_article"))
            put("显示卡片类别", arrayOf("switch_tag"))
            put("去搜索广告", arrayOf("switch_searchad"))
            put("状态栏沉浸", arrayOf("switch_statusbar"))
            put("第三方登录", arrayOf("switch_thirdpartylogin"))
            put("导航栏样式", arrayOf("switch_navres"))
            put("WebView修改", arrayOf("switch_webview_debug"))
            put("临时文件清理", arrayOf("switch_autoclean", "switch_silenceclean"))
            put("隐藏推荐页置顶热门", arrayOf("switch_feedtophot"))
            put("隐藏「我的」底部混合卡片", arrayOf("switch_minehybrid"))
            put("去关注按钮", arrayOf("switch_subscribe"))
            put("禁止进入全屏模式", arrayOf("switch_fullscreen"))
            put("禁止自动刷新", arrayOf("switch_autorefresh"))
            put("图片换源去水印", arrayOf("switch_imagesource"))
            put("移除水印", arrayOf("switch_watermark"))
            put("反追踪", arrayOf("switch_antitracking", "switch_antitracking_link", "switch_antitracking_qr"))
        }

        /** 只有总开关、没有独立开关的功能：失败时无处可灰，也不该在单测里要求它有映射。 */
        private val NO_SWITCH_FEATURES: LinkedHashSet<String> = LinkedHashSet(listOf("隐藏热榜顶部置顶"))

        @JvmStatic
        fun hasSwitch(feature: String?): Boolean = FEATURE_KEYS.containsKey(feature)

        @JvmStatic
        fun hasNoSwitch(feature: String?): Boolean = NO_SWITCH_FEATURES.contains(feature)
    }
}
