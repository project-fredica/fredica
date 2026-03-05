package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.python.PythonUtil
import io.ktor.http.HttpMethod

actual suspend fun RunFfmpegDetectJsMessageHandler.deviceDetect(): String =
    PythonUtil.Py314Embed.PyUtilServer.requestText(
        HttpMethod.Post, "/device/detect", requestTimeoutMs = 120_000L
    )
