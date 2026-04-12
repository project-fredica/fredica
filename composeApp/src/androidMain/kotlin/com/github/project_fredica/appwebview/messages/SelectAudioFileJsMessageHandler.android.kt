package com.github.project_fredica.appwebview.messages

actual suspend fun selectAudioFile(): String? =
    throw NotImplementedError("selectAudioFile is not supported on Android")
