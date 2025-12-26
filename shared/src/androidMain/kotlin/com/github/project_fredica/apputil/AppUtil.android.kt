package com.github.project_fredica.apputil

import com.google.common.base.CaseFormat

actual fun AppUtil.StrUtil.caseCastLowerCamelToLowerUnderscore(src: String): String {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, src)
}