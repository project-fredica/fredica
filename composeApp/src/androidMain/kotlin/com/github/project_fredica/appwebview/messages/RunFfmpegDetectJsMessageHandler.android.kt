package com.github.project_fredica.appwebview.messages

actual suspend fun RunFfmpegDetectJsMessageHandler.deviceDetect(): String =
    throw NotImplementedError("deviceDetect is not supported on Android")
