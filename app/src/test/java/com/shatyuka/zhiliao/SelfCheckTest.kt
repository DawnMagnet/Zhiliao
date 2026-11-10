package com.shatyuka.zhiliao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 运行期自证只做记账，纯逻辑，JVM 里就能验。 */
class SelfCheckTest {

    @Before
    fun setUp() {
        SelfCheck.reset()
    }

    @Test
    fun fireIsRecordedOncePerFeatureAndTarget() {
        assertTrue(SelfCheck.fire("去信息流广告", "com.zhihu.android.a#b"))
        assertFalse(SelfCheck.fire("去信息流广告", "com.zhihu.android.a#b"))
        assertTrue(SelfCheck.fire("去搜索广告", "com.zhihu.android.a#b"))
        assertEquals(2, SelfCheck.firedCount())
    }

    @Test
    fun summaryListsTargetsThatNeverFired() {
        SelfCheck.installed("去信息流广告", "com.zhihu.android.a#b")
        SelfCheck.installed("去搜索广告", "com.zhihu.android.c#d")
        SelfCheck.fire("去信息流广告", "com.zhihu.android.a#b")

        assertEquals("已装 2 个目标，未触发 1 个：去搜索广告 com.zhihu.android.c#d", SelfCheck.summary())
    }

    @Test
    fun summaryReportsAllFired() {
        SelfCheck.installed("反追踪", "com.zhihu.android.e#f")
        SelfCheck.fire("反追踪", "com.zhihu.android.e#f")

        assertEquals("已装 1 个目标，全部触发过", SelfCheck.summary())
    }

    @Test
    fun summaryHandlesEmptyRegistry() {
        assertEquals("尚未安装任何目标", SelfCheck.summary())
    }

    @Test
    fun installedWithoutFireStaysPending() {
        SelfCheck.installed("禁止自动刷新", "com.zhihu.android.g#h")

        assertEquals(1, SelfCheck.installedCount())
        assertEquals(0, SelfCheck.firedCount())
        assertEquals(1, SelfCheck.neverFired().size)
    }

    /**
     * Hook 命中时不该再拼字符串：key 在安装时拼一次，命中路径只传它。
     * 这里验预拼 key 与"功能+目标"走的是同一份账，否则两套写法的统计会各算各的。
     */
    @Test
    fun precomputedKeySharesBookkeepingWithFeatureAndTarget() {
        val key = SelfCheck.keyOf("去评论广告", "com.zhihu.android.h#i")
        SelfCheck.installedKey(key)
        SelfCheck.installed("去评论广告", "com.zhihu.android.h#i")

        assertEquals(1, SelfCheck.installedCount())
        assertTrue(SelfCheck.fireKey(key))
        assertFalse(SelfCheck.fire("去评论广告", "com.zhihu.android.h#i"))
        assertEquals(1, SelfCheck.firedCount())
        assertEquals("已装 1 个目标，全部触发过", SelfCheck.summary())
    }

    @Test
    fun keyUsesPlaceholderForMissingFeature() {
        assertEquals("?|com.zhihu.android.a#b", SelfCheck.keyOf(null, "com.zhihu.android.a#b"))
        assertEquals("?|com.zhihu.android.a#b", SelfCheck.keyOf("", "com.zhihu.android.a#b"))
    }
}
