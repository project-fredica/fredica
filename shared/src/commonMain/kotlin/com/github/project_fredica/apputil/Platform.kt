package com.github.project_fredica.apputil


interface Platform {
    val description: String

    val isJVM: Boolean get() = false

    val isAndroid: Boolean get() = false

    val isIOS: Boolean get() = false

    val isJs: Boolean get() = false

    val isSupportPython get() = isWindows

    val isWindows: Boolean get() = false

    val isLinux: Boolean get() = false

    val isMacOS: Boolean get() = false

    companion object
}

expect fun Platform.Companion.getPlatform(): Platform