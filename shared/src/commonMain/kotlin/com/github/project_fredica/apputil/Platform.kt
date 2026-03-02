package com.github.project_fredica.apputil


/**
 * 多平台运行环境描述接口，通过 expect/actual 机制在各平台提供具体实现。
 *
 * 用途：运行时判断当前平台，以启用/禁用平台特有功能（如 Python 调用、系统代理读取等）。
 *
 * 使用方式：
 * ```kotlin
 * val platform = Platform.getPlatform()
 * if (platform.isWindows) { ... }
 * ```
 */
interface Platform {
    /** 平台的人类可读描述，例如 "Windows 11" / "Android 14" 等，用于日志和调试。 */
    val description: String

    /** 是否运行在 JVM 上（包含 Desktop 和 Server 场景，Android 除外）。 */
    val isJVM: Boolean get() = false

    /** 是否运行在 Android 平台（继承自 JVM，但单独区分以处理 Android 特有 API）。 */
    val isAndroid: Boolean get() = false

    /** 是否运行在 iOS 平台（Kotlin/Native）。 */
    val isIOS: Boolean get() = false

    /** 是否运行在 JavaScript/Kotlin-JS 平台。 */
    val isJs: Boolean get() = false

    /**
     * 是否支持调用 Python 脚本。
     * 当前仅 Windows 桌面端支持（通过 embedded Python 实现 AI 功能如语音分离等）。
     */
    val isSupportPython get() = isWindows

    /** 是否运行在 Windows 操作系统。 */
    val isWindows: Boolean get() = false

    /** 是否运行在 Linux 操作系统。 */
    val isLinux: Boolean get() = false

    /** 是否运行在 macOS 操作系统。 */
    val isMacOS: Boolean get() = false

    companion object
}

/** 获取当前运行平台的 [Platform] 实例，由各平台 actual 实现提供。 */
expect fun Platform.Companion.getPlatform(): Platform