package com.shatyuka.zhiliao.hooks

import android.widget.Toast
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.io.File
import java.io.FilenameFilter
import java.text.StringCharacterIterator
import java.util.Locale
import java.util.regex.Pattern

class Cleaner : IHook {
    override fun getName(): String {
        return "临时文件清理"
    }

    override fun init(classLoader: ClassLoader) {
        if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_autoclean", false)) {
            val size = humanReadableByteCount(doClean())
            if (!Helper.prefs!!.getBoolean("switch_silenceclean", false)) {
                Toast.makeText(Helper.context!!, "共清理 $size 临时文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun hook() {
    }

    private class Filter : FilenameFilter {
        override fun accept(dir: File, name: String): Boolean {
            val regex = regex_cache ?: return false
            return regex.matcher(name).find()
        }
    }

    companion object {
        private val regex_cache: Pattern? = Helper.compileRegex("(^\\d{4}-\\d{2}-\\d{2}$)|(^-?\\d+$)|(^[0-9a-fA-F]{32}$)")

        @JvmStatic
        fun humanReadableByteCount(bytes: Long): String {
            val absB = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(bytes)
            if (absB < 1024) {
                return "$bytes B"
            }
            var value = absB
            val ci = StringCharacterIterator("KMGTPE")
            var i = 40
            while (i >= 0 && absB > (0xfffccccccccccccL shr i)) {
                value = value shr 10
                ci.next()
                i -= 10
            }
            value *= java.lang.Long.signum(bytes)
            return String.format(Locale.US, "%.1f %cB", value / 1024.0, ci.current())
        }

        @JvmStatic
        fun getFileSize(file: File): Long {
            if (file.isFile) {
                return file.length()
            }
            val children = file.listFiles()
            var size = 0L
            if (children != null) {
                for (child in children) {
                    size += getFileSize(child)
                }
            }
            return size
        }

        @JvmStatic
        fun getFileSize(files: Array<File>?): Long {
            if (files == null) {
                return 0
            }
            var size = 0L
            for (file in files) {
                size += getFileSize(file)
            }
            return size
        }

        @JvmStatic
        fun getCacheFiles(): Array<File>? {
            return Helper.context!!.filesDir.listFiles(Filter())
        }

        @JvmStatic
        fun doClean(): Long {
            val files = getCacheFiles()
            var size = 0L
            for (file in files!!) {
                try {
                    val fileSize = getFileSize(file)
                    val deleted = if (file.isDirectory) {
                        Helper.deleteDirectory(file)
                    } else {
                        file.delete()
                    }
                    if (deleted) {
                        size += fileSize
                    } else {
                        XposedBridge.log("[Zhiliao][临时文件清理] 删除失败: $file")
                    }
                } catch (e: Exception) {
                    XposedBridge.log("[Zhiliao][临时文件清理] 删除异常: $file $e")
                }
            }
            return size
        }
    }
}
