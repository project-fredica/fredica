@file:Suppress("UnusedReceiverParameter")

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

    val nativeAssetPath: File by lazy {
        val nativeAssetPath = getAsset(
            "${
                if (isWindows) "windows" else if (isLinux) "linux" else if (isMacOS) "macos" else throw Exception("invalid platform")
            }_dir_exist", autoCastSuffix = false
        )
        if (nativeAssetPath == null) {
            throw FileNotFoundException("JVM APP Util not found base asset path")
        }
        return@lazy nativeAssetPath.parentFile ?: throw kotlinx.io.IOException("no parent of $nativeAssetPath")
    }
}

actual fun Platform.Companion.getPlatform(): Platform = JVMPlatform

/**
 * 按优先级查找 desktop_assets 下的资源文件，同时兼容三种运行场景：
 *
 * 1. **打包安装后**（最高优先级）：
 *    Compose Desktop 打包时会将 `desktop_assets/common/` 和 `desktop_assets/{windows,linux,macos}/`
 *    的内容**直接合并**（不保留平台子目录层级）到安装目录的 `app/resources/` 下。
 *    例如 `desktop_assets/windows/lfs/python-314-embed/` → `app/resources/lfs/python-314-embed/`
 *    因此安装后查找路径为：`{user.dir}/app/resources/{paths...}`
 *    ⚠️ 坑：不要在安装后路径里加 common/ 或 windows/ 子目录，打包时已被展平。
 *
 * 2. **通过 `./gradlew :composeApp:run` 运行**：
 *    user.dir 为 `{project}/composeApp`，资源在 `{project}/desktop_assets/{common,windows,...}/{paths...}`
 *    通过 `currentDir.parent.resolve(...)` 向上一级找到项目根目录。
 *
 * 3. **直接从 IDE 运行**（相对路径兜底）：
 *    user.dir 即项目根目录，直接用相对路径 `desktop_assets/{common,windows,...}/{paths...}`。
 *
 * @param paths     资源相对路径片段，如 `"lfs", "python-314-embed", "python.exe"`
 * @param autoCastSuffix 若为 true，非 Windows 平台自动去掉 `.exe` 后缀
 */
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
            // 场景3：IDE 直接运行，user.dir = 项目根目录
            it,
            // 场景2：gradlew run，user.dir = composeApp/，需向上一级
            currentDir?.parent?.resolve(it),
        )
    }.toMutableList()
    // 场景1：打包安装后，desktop_assets/{common,windows,...} 被展平合并到 app/resources/
    val installedResourcesBase = currentDir.resolve("app").resolve("resources")
    allPaths.add(0, paths2.fold(installedResourcesBase) { acc, s -> acc.resolve(s) })
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