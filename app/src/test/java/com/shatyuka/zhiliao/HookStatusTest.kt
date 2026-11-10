package com.shatyuka.zhiliao

import com.shatyuka.zhiliao.hooks.IHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 置灰账本的测试。最有价值的是第一条：新增 Hook 忘了登记映射，
 * 设置页就会对它永远显示"可用"——这种漏登记在这里当场上红。
 */
class HookStatusTest {

    @Test
    fun everyHookIsAccountedFor() {
        for (hook: IHook in Hooks.hooks) {
            val name = hook.getName()
            assertTrue(
                "Hook「$name」没有登记设置项映射，失败时它的开关不会置灰",
                HookStatus.hasSwitch(name) || HookStatus.hasNoSwitch(name)
            )
        }
    }

    @Test
    fun failingHookCollectsItsKeys() {
        val status = HookStatus()
        status.markUnsupported("隐藏导航栏按钮")
        assertEquals(
            "switch_homenav,switch_findnav,switch_messagenav,switch_profilenav,switch_panelnav",
            status.failedKeysLine()
        )
    }

    @Test
    fun repeatedAndUnknownFeaturesDoNotDuplicate() {
        val status = HookStatus()
        status.markUnsupported("去搜索广告")
        status.markUnsupported("去搜索广告")
        status.markUnsupported("这条功能根本不存在")
        assertEquals("switch_searchad", status.failedKeysLine())
        assertEquals(1, status.size())
    }

    @Test
    fun resetClearsEverything() {
        val status = HookStatus()
        status.markUnsupported("反追踪")
        status.reset()
        assertEquals("", status.failedKeysLine())
        assertEquals(0, status.size())
    }
}
