package com.shatyuka.zhiliao

/**
 * 诊断报告：把排障要用的信息拼成一段纯文本。
 *
 * 刻意只放"结构性"信息——版本、指纹、开关真假、失败项、文本框长度。
 * 自定义过滤的关键词和注入的 JS 一个字都不导出，那些是用户自己的内容。
 */
object DiagnosticReport {

    fun build(
        moduleVersion: String,
        timestamp: String,
        device: String,
        zhihu: String,
        fingerprint: String,
        status: String,
        failures: List<String>,
        switches: Map<String, Boolean>,
        textLengths: Map<String, Int>,
        packager: String,
        sourceRepo: String,
    ): String {
        val text = StringBuilder()
        text.appendLine("知了诊断报告")
        text.appendLine("时间      : $timestamp")
        text.appendLine("模块版本  : $moduleVersion")
        text.appendLine("打包者    : $packager")
        text.appendLine("源代码    : $sourceRepo")
        text.appendLine("设备      : $device")
        text.appendLine("知乎版本  : ${zhihu.ifEmpty { "未知（知乎没在运行？）" }}")
        text.appendLine("缓存指纹  : ${fingerprint.ifEmpty { "无" }}")
        text.appendLine("自检      : ${status.ifEmpty { "未查询" }}")
        text.appendLine("失败项    : ${if (failures.isEmpty()) "无" else failures.joinToString(", ")}")
        text.appendLine()
        text.appendLine("开关（共 ${switches.size} 项）")
        for ((key, value) in switches.entries.sortedBy { it.key }) {
            text.appendLine("  ${if (value) "[开]" else "[关]"} $key")
        }
        if (textLengths.isNotEmpty()) {
            text.appendLine()
            text.appendLine("自定义文本（只记长度，不记内容）")
            for ((key, length) in textLengths.entries.sortedBy { it.key }) {
                text.appendLine("  $key = $length 字")
            }
        }
        return text.toString()
    }
}
