package com.shatyuka.zhiliao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自定义过滤的入口守卫。灾难性回溯能把知乎进程整个拖死，
 * 所以超长正则必须在进 Pattern 之前就被挡住，而不是"编译成功了就算过"。
 */
class RegexGuardTest {

    @Test
    fun overlongPatternIsRejected() {
        val sb = StringBuilder()
        for (i in 0..Helper.MAX_REGEX_LENGTH) {
            sb.append('a')
        }
        assertNull(Helper.compileRegex(sb.toString()))
    }

    @Test
    fun patternAtTheLimitStillCompiles() {
        val sb = StringBuilder()
        for (i in 0 until Helper.MAX_REGEX_LENGTH) {
            sb.append('a')
        }
        assertNotNull(Helper.compileRegex(sb.toString()))
    }

    @Test
    fun emptyAndBrokenPatternsAreNull() {
        assertNull(Helper.compileRegex(null))
        assertNull(Helper.compileRegex(""))
        assertNull(Helper.compileRegex("([a-z"))
        assertEquals("过滤器", Helper.compileRegex("过滤器")!!.pattern())
    }
}
