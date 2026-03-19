package com.github.project_fredica.appwebview.messages

object AppWebViewMessages {
    val all by lazy {
        listOf(
            OpenBrowserJsMessageHandler(),
            GetAppConfigJsMessageHandler(),
            SaveAppConfigJsMessageHandler(),
            GetServerInfoJsMessageHandler(),
            GetDeviceInfoJsMessageHandler(),
            RunFfmpegDetectJsMessageHandler(),
            GetLlmModelsJsMessageHandler(),
            SaveLlmModelJsMessageHandler(),
            DeleteLlmModelJsMessageHandler(),
            ReorderLlmModelsJsMessageHandler(),
            GetLlmDefaultRolesJsMessageHandler(),
            SaveLlmDefaultRolesJsMessageHandler(),
            LlmProxyChatJsMessageHandler(),
            CheckBilibiliCredentialJsMessageHandler(),
            TryRefreshBilibiliCredentialJsMessageHandler(),
            RunFasterWhisperCompatEvalJsMessageHandler(),
            RunFasterWhisperModelDownloadJsMessageHandler(),
            GetTorchInfoJsMessageHandler(),
            GetTorchCheckJsMessageHandler(),
            GetTorchPipCommandJsMessageHandler(),
            RunTorchDetectJsMessageHandler(),
            SaveTorchConfigJsMessageHandler(),
            DownloadTorchJsMessageHandler(),
            GetActiveTorchDownloadJsMessageHandler(),
            GetTorchMirrorCheckJsMessageHandler(),
            GetTorchMirrorVersionsJsMessageHandler(),
            GetTorchAllMirrorVariantsJsMessageHandler(),
            RefreshTorchMirrorCacheJsMessageHandler(),
            GetSystemProxyJsMessageHandler(),
        )
    }
}