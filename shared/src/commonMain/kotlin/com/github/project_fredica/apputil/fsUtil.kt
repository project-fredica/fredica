package com.github.project_fredica.apputil

/**
 * 将任意字符串转换为安全的文件名片段（slug）。
 *
 * 移植自 Python `filename_slugify`（django/utils/text.py），并修正 Unicode 支持：
 *   1. Unicode NFKC 规范化
 *   2. 转小写
 *   3. 将路径分隔符（/ \）替换为空格，使其参与后续连字符合并
 *   4. 去掉非 Unicode 字母/数字、非下划线、非连字符、非空白字符
 *      （使用 \p{L}\p{N} 而非 \w，确保中文等 Unicode 字符被保留）
 *   5. 连续空白 / 连字符合并为单个连字符
 *   6. 去掉首尾的连字符和下划线
 *
 * 用于保护目录名参数，防止路径穿越或非法字符导致的文件系统问题。
 */
fun filenameSlugify(value: String): String {
    // Step 1: NFKC 规范化（合并兼容字符，如全角转半角）
    val normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
    // Step 2: 转小写
    val lowered = normalized.lowercase()
    // Step 3: 路径分隔符替换为空格（使其参与后续连字符合并，而非直接粘连相邻字符）
    val separated = lowered.replace(Regex("[/\\\\]"), " ")
    // Step 4: 去掉非 Unicode 字母/数字、非下划线、非连字符、非空白字符
    val cleaned = separated.replace(Regex("[^\\p{L}\\p{N}_\\s\\-]"), "")
    // Step 5: 连续空白 / 连字符合并为单个连字符
    val slugged = cleaned.replace(Regex("[-\\s]+"), "-")
    // Step 6: 去掉首尾的连字符和下划线
    return slugged.trim('-', '_')
}
