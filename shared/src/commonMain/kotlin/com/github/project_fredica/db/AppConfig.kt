package com.github.project_fredica.db

import com.github.project_fredica.llm.LlmDefaultRoles
import com.github.project_fredica.llm.LlmModelConfig
import com.github.project_fredica.llm.LlmTestConfig
import com.github.project_fredica.apputil.loadJsonModel
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
    // faster-whisper 本地语音识别配置
    @SerialName("faster_whisper_model") val fasterWhisperModel: String = "",
    @SerialName("faster_whisper_compute_type") val fasterWhisperComputeType: String = "auto",
    @SerialName("faster_whisper_device") val fasterWhisperDevice: String = "auto",
    @SerialName("faster_whisper_init_json") val fasterWhisperInitJson: String = "{}",
    @SerialName("faster_whisper_models_dir") val fasterWhisperModelsDir: String = "",
    // ASR 权限与测试配置（服主管理）
    @SerialName("asr_allow_download") val asrAllowDownload: Boolean = true,
    @SerialName("asr_disallowed_models") val asrDisallowedModels: String = "",
    @SerialName("asr_test_audio_path") val asrTestAudioPath: String = "",
    @SerialName("asr_test_wave_count") val asrTestWaveCount: Int = 10,
    // torch 检测结果（只读，启动时自动写入）
    @SerialName("torch_recommended_variant") val torchRecommendedVariant: String = "",
    @SerialName("torch_recommendation_json") val torchRecommendationJson: String = "",
    // torch 用户选择（用户主动写入，用于下载目录命名和符号链接）
    @SerialName("torch_variant") val torchVariant: String = "",
    // torch 下载代理设置
    @SerialName("torch_download_use_proxy") val torchDownloadUseProxy: Boolean = false,
    @SerialName("torch_download_proxy_url") val torchDownloadProxyUrl: String = "",
    // torch 下载源覆盖（内置 variant 用；留空则使用官方源，可填国内镜像地址）
    @SerialName("torch_download_index_url") val torchDownloadIndexUrl: String = "",
    // torch 自定义版本（variant=="custom" 时使用）
    @SerialName("torch_custom_packages") val torchCustomPackages: String = "",
    @SerialName("torch_custom_index_url") val torchCustomIndexUrl: String = "",
    @SerialName("torch_custom_variant_id") val torchCustomVariantId: String = "",
    // 认证相关
    // ⚠️ webserverAuthToken 由 AppConfigDb.updateConfig/updateConfigPartial 自动触发 WebserverAuthTokenCache.invalidate()
    @SerialName("webserver_auth_token") val webserverAuthToken: String = "",
    @SerialName("instance_initialized") val instanceInitialized: String = "false",
    @SerialName("salt_imk_b64") val saltImkB64: String = "",
    @SerialName("salt_auth_b64") val saltAuthB64: String = "",
    // [已迁移] Bilibili 登录态已迁移至 bilibili_account_pool 表，由 BilibiliAccountPoolService 管理
    // @SerialName("bilibili_sessdata") val bilibiliSessdata: String = "",
    // @SerialName("bilibili_bili_jct") val bilibiliBiliJct: String = "",
    // @SerialName("bilibili_buvid3") val bilibiliBuvid3: String = "",
    // @SerialName("bilibili_dedeuserid") val bilibiliDedeuserid: String = "",
    // @SerialName("bilibili_ac_time_value") val bilibiliAcTimeValue: String = "",
    // @SerialName("bilibili_buvid4") val bilibiliBuvid4: String = "",
    @SerialName("bilibili_proxy") val bilibiliProxy: String = "",
) : LlmTestConfig {
    // 从 llmDefaultRolesJson 中取 devTestModelId，再从 llmModelsJson 中查找对应模型
    private val devTestModel: LlmModelConfig? get() {
        val roles = llmDefaultRolesJson.loadJsonModel<LlmDefaultRoles>().getOrNull() ?: return null
        val modelId = roles.devTestModelId.takeIf { it.isNotBlank() } ?: return null
        val models = llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrNull() ?: return null
        return models.firstOrNull { it.id == modelId }
    }

    override val llmTestApiKey: String get() = devTestModel?.apiKey ?: ""
    override val llmTestBaseUrl: String get() = devTestModel?.baseUrl ?: ""
    override val llmTestModel: String get() = devTestModel?.model ?: ""
}

interface AppConfigRepo {
    suspend fun getConfig(): AppConfig
    suspend fun updateConfig(config: AppConfig)
    suspend fun updateConfigPartial(kvPatch: Map<String, String>)
}

object AppConfigService {
    private var _repo: AppConfigRepo? = null

    val repo: AppConfigRepo
        get() = _repo ?: throw IllegalStateException("AppConfigService not initialized")

    fun initialize(repo: AppConfigRepo) {
        _repo = repo
    }
}
