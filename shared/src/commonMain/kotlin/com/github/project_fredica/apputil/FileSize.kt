package com.github.project_fredica.apputil

/**
 * 表示文件大小的内联值类，封装字节数并提供人类可读的格式化输出。
 *
 * 单位换算采用 1024 进制：B → KB → MB → GB → TB。
 * [toString] 默认保留 2 位小数，等同于 `toHumanString(2)`。
 *
 * @property bytesValue 原始字节数
 */
@JvmInline
value class FileSize(val bytesValue: Long) {
    /**
     * 将字节数转为带单位的可读字符串。
     *
     * @param digits 小数点后保留的位数，默认 2
     * @sample `FileSize(1536).toHumanString()` → `"1.50KB"`
     */
    fun toHumanString(digits: Int = 2): String {
        if (bytesValue < 1024L) {
            return "${bytesValue}B"
        }
        if (bytesValue < 1024L * 1024) {
            return "${(bytesValue.toDouble() / 1024).toFixed(digits)}KB"
        }
        if (bytesValue < 1024L * 1024 * 1024) {
            return "${(bytesValue.toDouble() / 1024 / 1024).toFixed(digits)}MB"
        }
        if (bytesValue < 1024L * 1024 * 1024 * 1024) {
            return "${(bytesValue.toDouble() / 1024 / 1024 / 1024).toFixed(digits)}GB"
        }
        return "${(bytesValue.toDouble() / 1024 / 1024 / 1024 / 1024).toFixed(digits)}TB"
    }

    override fun toString(): String = toHumanString(2)
}