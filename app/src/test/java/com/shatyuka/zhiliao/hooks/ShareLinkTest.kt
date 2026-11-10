package com.shatyuka.zhiliao.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

/** 分享链接的文本级净化：纯字符串逻辑，不依赖设备也不需要知乎在手。 */
class ShareLinkTest {

    @Test
    fun stripsShareCodeAndUtmPsn() {
        assertEquals(
            "https://www.zhihu.com/question/7223131762/answer/2093295030349910613",
            AntiTracking.stripTracking(
                "https://www.zhihu.com/question/7223131762/answer/2093295030349910613" +
                    "?share_code=11J19ZFeEtqjW&utm_psn=2095186356855124716"
            )
        )
    }

    @Test
    fun stripsAnyUtmPrefix() {
        assertEquals(
            "https://www.zhihu.com/hot",
            AntiTracking.stripTracking("https://www.zhihu.com/hot?utm_source=wechat&utm_whatever=1")
        )
    }

    @Test
    fun keepsOtherParams() {
        assertEquals(
            "https://www.zhihu.com/search?q=%E5%A4%96%E5%8D%96&type=content",
            AntiTracking.stripTracking(
                "https://www.zhihu.com/search?q=%E5%A4%96%E5%8D%96&type=content" +
                    "&utm_medium=social"
            )
        )
    }

    @Test
    fun leavesCleanUrlUntouched() {
        val url = "https://www.zhihu.com/question/1/answer/2"
        assertEquals(url, AntiTracking.stripTracking(url))
    }

    @Test
    fun keepsFragment() {
        assertEquals(
            "https://www.zhihu.com/question/1/answer/2#comments",
            AntiTracking.stripTracking("https://www.zhihu.com/question/1/answer/2?share_code=abc#comments")
        )
    }

    @Test
    fun unwrapsRedirect() {
        assertEquals(
            "https://www.zhihu.com/question/1/answer/2",
            AntiTracking.cleanUrl(
                "https://link.zhihu.com/?target=https%3A%2F%2Fwww.zhihu.com%2Fquestion" +
                    "%2F1%2Fanswer%2F2%3Fshare_code%3Dabc%26utm_psn%3D9"
            )
        )
    }

    @Test
    fun cleansUrlInsideText() {
        val text = "北京到底有多少条地铁线？ - 知乎 " +
            "https://www.zhihu.com/question/1/answer/2?share_code=abc&utm_psn=9 复制链接"
        assertEquals(
            "北京到底有多少条地铁线？ - 知乎 https://www.zhihu.com/question/1/answer/2 复制链接",
            AntiTracking.cleanShareText(text)
        )
    }

    @Test
    fun cleansEveryUrlInText() {
        val text = "https://www.zhihu.com/question/1?share_code=a 和 https://zhuanlan.zhihu.com/p/2?utm_medium=x"
        assertEquals(
            "https://www.zhihu.com/question/1 和 https://zhuanlan.zhihu.com/p/2",
            AntiTracking.cleanShareText(text)
        )
    }

    @Test
    fun keepsUrlTrailingPunctuation() {
        val text = "看这个 https://www.zhihu.com/question/1?share_code=a。"
        assertEquals("看这个 https://www.zhihu.com/question/1。", AntiTracking.cleanShareText(text))
    }

    @Test
    fun ignoresOtherSites() {
        val text = "https://www.bilibili.com/video/BV1?utm_source=share"
        assertEquals(text, AntiTracking.cleanShareText(text))
    }

    @Test
    fun plainTextUntouched() {
        val text = "这段话里没有任何链接"
        assertEquals(text, AntiTracking.cleanShareText(text))
    }

    @Test
    fun stripsUppercaseParamNames() {
        assertEquals(
            "https://www.zhihu.com/hot",
            AntiTracking.stripTracking("https://www.zhihu.com/hot?UTM_Source=weixin&Share_Code=abc")
        )
    }

    @Test
    fun handlesEmptyValue() {
        assertEquals(
            "https://www.zhihu.com/hot",
            AntiTracking.stripTracking("https://www.zhihu.com/hot?utm_source=")
        )
    }

    @Test
    fun handlesTrailingQuestionMark() {
        assertEquals(
            "https://www.zhihu.com/hot?",
            AntiTracking.stripTracking("https://www.zhihu.com/hot?")
        )
    }

    @Test
    fun keepsQuestionWhenOnlyTrackingParams() {
        assertEquals(
            "https://www.zhihu.com/question/1/answer/2",
            AntiTracking.stripTracking(
                "https://www.zhihu.com/question/1/answer/2?utm_source=a&s_r=b&s_s_i=c"
            )
        )
    }
}
