package com.github.project_fredica.apputil

import com.github.project_fredica.apputil.CaseFormat.*

/**
 * 将项目内部的 [CaseFormat] 枚举映射到 Guava 的 `CaseFormat`，
 * 以便复用 Guava 的字符串命名格式转换逻辑。
 */
fun CaseFormat.toGuavaCaseFormat(): com.google.common.base.CaseFormat {
    return when (this) {
        LOWER_HYPHEN -> com.google.common.base.CaseFormat.LOWER_HYPHEN
        LOWER_UNDERSCORE -> com.google.common.base.CaseFormat.LOWER_UNDERSCORE
        LOWER_CAMEL -> com.google.common.base.CaseFormat.LOWER_CAMEL
        UPPER_CAMEL -> com.google.common.base.CaseFormat.UPPER_CAMEL
        UPPER_UNDERSCORE -> com.google.common.base.CaseFormat.UPPER_UNDERSCORE
    }
}

/** JVM actual 实现，委托给 Guava 的 `CaseFormat.to()` 完成实际转换。 */
actual fun String.convertCase(
    from: CaseFormat, to: CaseFormat
): String {
    return from.toGuavaCaseFormat().to(to.toGuavaCaseFormat(), this)
}