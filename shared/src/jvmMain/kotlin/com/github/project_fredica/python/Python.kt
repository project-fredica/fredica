package com.github.project_fredica.python

import com.github.project_fredica.apputil.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Python {
    suspend fun autoInit(isTest: Boolean = false) = Result.wrap {
        withContext(Dispatchers.IO) {
            val logger = createLogger()
            if (Platform.getPlatform().isWindows) {
                logger.info("Start auto init WinPython")
                AppUtil.Paths.desktopWinPythonExePath.let { exePath ->
                    exePath.parentFile.mkdirs()
                    val exeName = exePath.name
                    logger.debug("WinPython exeName is $exeName")
                    if (!exePath.exists()) {
                        logger.debug("WinPython exe not exist")
                    }
                }
                return@withContext AutoInitResult.TestOk
            } else {
                return@withContext AutoInitResult.UnsupportedPlatform
            }
        }
    }
}