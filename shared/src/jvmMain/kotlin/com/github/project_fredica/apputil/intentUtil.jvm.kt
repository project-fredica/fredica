package com.github.project_fredica.apputil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

actual suspend fun IntentUtil.openBrowser(url: String): Result<Unit> = Result.wrap {
    val logger = createLogger { "${::openBrowser.name}." }
    withContext(Dispatchers.IO) {
        if (!Desktop.isDesktopSupported()) {
            throw IllegalStateException("Desktop unsupported")
        }
        logger.debug("start open browser : $url")
        Desktop.getDesktop().browse(URI(url))
        logger.debug("finish open browser : $url")
    }
}