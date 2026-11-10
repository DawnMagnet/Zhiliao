package com.shatyuka.zhiliao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断报告里最容易出事的不是排版，是漏内容：自定义过滤的关键词、注入的 JS
 * 都是用户自己写的东西，报告里只能出现长度，不能出现原文。
 */
class DiagnosticReportTest {

    private fun sample(status: String = "初始化成功=31/31 Hook句柄=50 全部可用") = DiagnosticReport.build(
        moduleVersion = "26.09.24",
        timestamp = "2026-10-17 22:50:00",
        device = "Xiaomi 2211133C / Android 14 (SDK 34)",
        zhihu = "11.10.0/41012",
        fingerprint = "41012:5:1789999331707",
        status = status,
        failures = listOf("switch_homenav"),
        switches = mapOf("switch_mainswitch" to true, "switch_feedad" to false),
        textLengths = mapOf("edit_regex" to 12),
        packager = "Dominic Wen",
        sourceRepo = "https://github.com/DawnMagnet/Zhiliao",
    )

    @Test
    fun reportCarriesTheFieldsWeNeedToDebug() {
        val text = sample()
        assertTrue(text.contains("打包者    : Dominic Wen"))
        assertTrue(text.contains("源代码    : https://github.com/DawnMagnet/Zhiliao"))
        assertTrue(text.contains("知乎版本  : 11.10.0/41012"))
        assertTrue(text.contains("缓存指纹  : 41012:5:1789999331707"))
        assertTrue(text.contains("失败项    : switch_homenav"))
        assertTrue(text.contains("[开] switch_mainswitch"))
        assertTrue(text.contains("[关] switch_feedad"))
        assertTrue(text.contains("edit_regex = 12 字"))
    }

    @Test
    fun missingZhihuLooksLikeMissingNotBroken() {
        val text = DiagnosticReport.build(
            moduleVersion = "26.09.24",
            timestamp = "t",
            device = "d",
            zhihu = "",
            fingerprint = "",
            status = "",
            failures = emptyList(),
            switches = emptyMap(),
            textLengths = emptyMap(),
            packager = "Dominic Wen",
            sourceRepo = "https://github.com/DawnMagnet/Zhiliao",
        )
        assertTrue(text.contains("未知（知乎没在运行？）"))
        assertTrue(text.contains("失败项    : 无"))
        assertFalse(text.contains("edit_regex"))
    }
}
