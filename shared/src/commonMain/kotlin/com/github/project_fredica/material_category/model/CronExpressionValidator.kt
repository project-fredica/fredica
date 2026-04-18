package com.github.project_fredica.material_category.model

object CronExpressionValidator {
    private val FIELD_RANGES = listOf(0..59, 0..23, 1..31, 1..12, 0..7)

    fun isValid(expr: String): Boolean {
        val fields = expr.trim().split("\\s+".toRegex())
        if (fields.size != 5) return false
        return fields.zip(FIELD_RANGES).all { (field, range) -> isFieldValid(field, range) }
    }

    private fun isFieldValid(field: String, range: IntRange): Boolean {
        if (field == "*") return true
        return field.split(",").all { part ->
            val stepParts = part.split("/", limit = 2)
            val base = stepParts[0]
            val step = stepParts.getOrNull(1)?.toIntOrNull()
            if (step != null && step <= 0) return@all false
            when {
                base == "*" -> true
                base.contains("-") -> {
                    val parts = base.split("-", limit = 2)
                    val lo = parts[0].toIntOrNull()
                    val hi = parts[1].toIntOrNull()
                    lo != null && hi != null && lo in range && hi in range && lo <= hi
                }
                else -> base.toIntOrNull()?.let { it in range } == true
            }
        }
    }
}
