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
    // faster-whisper 本地语音识别配置
    @SerialName("faster_whisper_model") val fasterWhisperModel: String = "",
    @SerialName("faster_whisper_compute_type") val fasterWhisperComputeType: String = "auto",
    @SerialName("faster_whisper_device") val fasterWhisperDevice: String = "auto",
    @SerialName("faster_whisper_init_json") val fasterWhisperInitJson: String = "{}",
    // 兼容性评估结果（只读，由 EvaluateWhisperCompatExecutor 写入）
    @SerialName("faster_whisper_compat_json") val fasterWhisperCompatJson: String = "{}",
    @SerialName("faster_whisper_models_dir") val fasterWhisperModelsDir: String = "",
    // Bilibili 登录态（留空则匿名请求；仅当出现"账号未登录"类错误时才需要配置）
    @SerialName("bilibili_sessdata") val bilibiliSessdata: String = "",
    @SerialName("bilibili_bili_jct") val bilibiliBiliJct: String = "",
    @SerialName("bilibili_buvid3") val bilibiliBuvid3: String = "",
    @SerialName("bilibili_dedeuserid") val bilibiliDedeuserid: String = "",
    @SerialName("bilibili_ac_time_value") val bilibiliAcTimeValue: String = "",
    @SerialName("bilibili_buvid4") val bilibiliBuvid4: String = "",
    @SerialName("bilibili_proxy") val bilibiliProxy: String = "",
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
