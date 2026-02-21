package com.github.project_fredica.apputil

enum class CaseFormat {
    LOWER_HYPHEN, LOWER_UNDERSCORE, LOWER_CAMEL, UPPER_CAMEL, UPPER_UNDERSCORE
}

expect fun String.convertCase(from: CaseFormat, to: CaseFormat): String