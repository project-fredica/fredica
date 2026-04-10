package com.github.project_fredica.appwebview.messages

actual val AppWebViewMessages.native: List<MyJsMessageHandler>
    get() = listOf(
        DownloadTorchJsMessageHandler(),
        GetActiveTorchDownloadJsMessageHandler(),
        GetTorchInfoJsMessageHandler(),
        GetTorchCheckJsMessageHandler(),
        GetTorchPipCommandJsMessageHandler(),
        RunTorchDetectJsMessageHandler(),
        SaveTorchConfigJsMessageHandler(),
        GetTorchMirrorCheckJsMessageHandler(),
        GetTorchMirrorVersionsJsMessageHandler(),
        GetTorchAllMirrorVariantsJsMessageHandler(),
        RefreshTorchMirrorCacheJsMessageHandler(),
    )