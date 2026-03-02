package com.github.project_fredica.apputil

/**
 * 标识符命名格式，与 Google Guava 的 `CaseFormat` 一一对应。
 *
 * | 枚举值             | 示例            |
 * |--------------------|-----------------|
 * | LOWER_HYPHEN       | `my-variable`   |
 * | LOWER_UNDERSCORE   | `my_variable`   |
 * | LOWER_CAMEL        | `myVariable`    |
 * | UPPER_CAMEL        | `MyVariable`    |
 * | UPPER_UNDERSCORE   | `MY_VARIABLE`   |
 */
enum class CaseFormat {
    LOWER_HYPHEN, LOWER_UNDERSCORE, LOWER_CAMEL, UPPER_CAMEL, UPPER_UNDERSCORE
}

/**
 * 将字符串从一种命名格式转换为另一种命名格式。
 * 底层由各平台 actual 实现（JVM 使用 Guava）。
 *
 * @param from 输入字符串的当前命名格式
 * @param to   目标命名格式
 * @sample `"my_field".convertCase(LOWER_UNDERSCORE, LOWER_CAMEL)` → `"myField"`
 */
expect fun String.convertCase(from: CaseFormat, to: CaseFormat): String