package com.github.project_fredica.asr.service

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.python.PythonUtil
import kotlinx.coroutines.CancellationException

actual object FasterWhisperInstallService {
    private val logger = createLogger { "FasterWhisperInstallService" }

    @Volatile private var installed = false

    /**
     * 确保 faster-whisper 已安装到 pipLibDir。
     *
     * - 进程内已安装（[installed] == true）时直接返回 null（快速路径）。
     * - 安装成功后将 [installed] 置为 true；重启后 flag 重置，pip 会快速检测已安装版本并跳过。
     * - [CancellationException] 必须重抛，不能被吞掉。
     * - 其他异常记录 logger.error 并返回错误信息字符串，由 Executor 层将 Task 标记为 failed。
     *
     * @return null 表示成功；非 null 字符串为错误信息
     */
    actual suspend fun ensureInstalled(): String? {
        if (installed) return null
        return try {
            PythonUtil.Py314Embed.installPackage("faster-whisper==1.2.1")
            installed = true
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error("[FasterWhisperInstallService] pip install failed", e)
            e.message ?: "安装失败"
        }
    }
}
