package com.github.project_fredica.apputil

object IntentUtil

expect suspend fun IntentUtil.openBrowser(url: String): Result<Unit>