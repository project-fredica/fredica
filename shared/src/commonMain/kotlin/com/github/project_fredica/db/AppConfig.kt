package com.github.project_fredica.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    @SerialName("server_port") val serverPort: Int = 7631,
    @SerialName("data_dir") val dataDir: String = "",
    @SerialName("auto_start") val autoStart: Boolean = false,
    @SerialName("start_minimized") val startMinimized: Boolean = false,
    @SerialName("open_browser_on_start") val openBrowserOnStart: Boolean = true,
    val theme: String = "system",
    val language: String = "zh-CN",
    @SerialName("proxy_enabled") val proxyEnabled: Boolean = false,
    @SerialName("proxy_url") val proxyUrl: String = "",
    @SerialName("rsshub_url") val rsshubUrl: String = "",
    // FFmpeg 配置
    @SerialName("ffmpeg_path") val ffmpegPath: String = "",
    @SerialName("ffmpeg_hw_accel") val ffmpegHwAccel: String = "auto",
    @SerialName("ffmpeg_auto_detect") val ffmpegAutoDetect: Boolean = true,
    // 设备检测结果（只读，启动时写入）
    @SerialName("device_info_json") val deviceInfoJson: String = "",
    @SerialName("ffmpeg_probe_json") val ffmpegProbeJson: String = "",
    // LLM 模型配置
    @SerialName("llm_models_json") val llmModelsJson: String = "[]",
    @SerialName("llm_default_roles_json") val llmDefaultRolesJson: String = "{}",
    // LLM 测试令牌（仅用于开发阶段验证 SSE 客户端）
    @SerialName("llm_test_api_key") val llmTestApiKey: String = "",
    @SerialName("llm_test_base_url") val llmTestBaseUrl: String = "",
    @SerialName("llm_test_model") val llmTestModel: String = "",
)

interface AppConfigRepo {
    suspend fun getConfig(): AppConfig
    suspend fun updateConfig(config: AppConfig)
}

object AppConfigService {
    private var _repo: AppConfigRepo? = null

    val repo: AppConfigRepo
        get() = _repo ?: throw IllegalStateException("AppConfigService not initialized")

    fun initialize(repo: AppConfigRepo) {
        _repo = repo
    }
}
