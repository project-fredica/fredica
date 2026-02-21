package com.github.project_fredica.apputil

@JvmInline
value class FileSize(val bytesValue: Long) {
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