package com.github.project_fredica.orm

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Col(
    val isId: Boolean = false,
    val defaultValue: String = "",
)
