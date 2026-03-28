package com.github.project_fredica.apputil

/**
 * 计算字符串的 SHA-256 哈希，以十六进制字符串返回（64 chars）。
 * commonMain 声明，jvmMain 用 java.security.MessageDigest 实现。
 */
expect fun sha256Hex(input: String): String
