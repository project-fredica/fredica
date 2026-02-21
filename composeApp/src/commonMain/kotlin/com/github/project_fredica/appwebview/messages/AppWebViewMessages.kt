package com.github.project_fredica.appwebview.messages

object AppWebViewMessages {
    val all by lazy {
        listOf(
            OpenBrowserJsMessageHandler(),
            GetAppConfigJsMessageHandler(),
            SaveAppConfigJsMessageHandler(),
        )
    }
}