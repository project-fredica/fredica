package com.github.project_fredica.asr.service

/**
 * 确保 faster-whisper 已安装。
 *
 * commonMain expect 声明；jvmMain actual 通过 PythonUtil.Py314Embed.installPackage 实现。
 *
 * @see [ensureInstalled] — null 表示成功；非 null 字符串为错误信息
 */
expect object FasterWhisperInstallService {
    suspend fun ensureInstalled(): String?
}
