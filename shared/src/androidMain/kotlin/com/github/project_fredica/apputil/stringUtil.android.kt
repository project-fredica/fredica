package com.github.project_fredica.apputil

import com.github.project_fredica.apputil.CaseFormat.LOWER_CAMEL
import com.github.project_fredica.apputil.CaseFormat.LOWER_HYPHEN
import com.github.project_fredica.apputil.CaseFormat.LOWER_UNDERSCORE
import com.github.project_fredica.apputil.CaseFormat.UPPER_CAMEL
import com.github.project_fredica.apputil.CaseFormat.UPPER_UNDERSCORE

fun CaseFormat.toGuavaCaseFormat(): com.google.common.base.CaseFormat {
    return when (this) {
        LOWER_HYPHEN -> com.google.common.base.CaseFormat.LOWER_HYPHEN
        LOWER_UNDERSCORE -> com.google.common.base.CaseFormat.LOWER_UNDERSCORE
        LOWER_CAMEL -> com.google.common.base.CaseFormat.LOWER_CAMEL
        UPPER_CAMEL -> com.google.common.base.CaseFormat.UPPER_CAMEL
        UPPER_UNDERSCORE -> com.google.common.base.CaseFormat.UPPER_UNDERSCORE
    }
}


actual fun String.convertCase(
    from: CaseFormat, to: CaseFormat
): String {
    return from.toGuavaCaseFormat().to(to.toGuavaCaseFormat(), this)
}