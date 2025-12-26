package com.github.project_fredica.apputil

import oshi.PlatformEnum
import oshi.SystemInfo
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Paths
import kotlin.io.path.absolute
import kotlin.io.path.exists


object JVMPlatform : Platform {
    val oshiPlat: PlatformEnum = SystemInfo.getCurrentPlatform()

    override val description: String = "Java ${System.getProperty("java.version")}"

    override val isJVM: Boolean = true

    override val isWindows = oshiPlat == PlatformEnum.WINDOWS || oshiPlat == PlatformEnum.WINDOWSCE

    override val isLinux = oshiPlat == PlatformEnum.LINUX

    override val isMacOS = oshiPlat == PlatformEnum.MACOS

    override val isAndroid by lazy {
        if (oshiPlat == PlatformEnum.ANDROID) {
            throw Exception("Platform bug ? it should not android !")
        }
        return@lazy false
    }

    fun getNativeAssetPath(): File {
        val nativeAssetPath = getAsset(
            "${
                if (isWindows) "windows" else if (isLinux) "linux" else if (isMacOS) "macos" else throw Exception("invalid platform")
            }_dir_exist", autoCastSuffix = false
        )
        if (nativeAssetPath == null) {
            throw FileNotFoundException("JVM APP Util not found base asset path")
        }
        return nativeAssetPath.parentFile ?: throw kotlinx.io.IOException("no parent of $nativeAssetPath")
    }
}

actual fun Platform.Companion.getPlatform(): Platform = JVMPlatform

fun JVMPlatform.getAsset(vararg paths: String, autoCastSuffix: Boolean): File? {
    val logger = createLogger()
    val plat = Platform.getPlatform()
    val paths2: Array<String> = arrayOf(*paths)
    if (autoCastSuffix) {
        if (paths2[paths2.lastIndex].endsWith(".exe")) {
            if (!plat.isWindows) {
                paths2[paths2.lastIndex] = paths2[paths2.lastIndex].removeSuffix(".exe")
            }
        }
    }
    val devAssetPlatPaths = if (plat.isWindows) {
        arrayOf("common", "windows")
    } else if (plat.isLinux) {
        arrayOf("common", "linux")
    } else if (plat.isMacOS) {
        arrayOf("common", "macos")
    } else {
        arrayOf("common")
    }
    val currentDir = Paths.get(System.getProperty("user.dir"))
    logger.debug("currentDir : $currentDir")
    val allPaths = devAssetPlatPaths.map { assetPlatPath ->
        Paths.get(
            "desktop_assets", assetPlatPath, *paths2
        )
    }.flatMap {
        @Suppress("UNNECESSARY_SAFE_CALL") listOf(
            // relative path
            it,
            // Run from gradlew
            currentDir?.parent?.resolve(it),
        )
    }.toMutableList()
    allPaths.add(0, currentDir.resolve("app").resolve("resources"))
    // Run from installed
    for (p in allPaths.filter { it != null }.map { it!!.absolute() }) {
        val p2 = p.toFile()
        if (p.exists()) {
            logger.debug("found assets : $p2")
            return p2
        } else {
            logger.debug("not found : $p")
        }
    }
    logger.debug("not found any assets")
    return null
}