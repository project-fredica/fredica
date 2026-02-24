package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.loadJsonModel
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object ImageProxyRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "图片跨站代理，缓存到本地后返回"
    override val requiresAuth = false

    override suspend fun handler(param: String): Any {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val url = query["url"]?.firstOrNull()
            ?: throw IllegalArgumentException("缺少 url 参数")

        val cacheKey = sha256Hex(url)
        val ext = url.substringBefore("?").substringBefore("#")
            .substringAfterLast("/").substringAfterLast(".", "")
            .let { if (it.length in 1..5) it else "" }
        val cacheFileName = if (ext.isNotBlank()) "$cacheKey.$ext" else cacheKey

        val cacheDir = AppUtil.Paths.appDataImageCacheDir
        val cacheFile = cacheDir.resolve(cacheFileName)

        return withContext(Dispatchers.IO) {
            if (cacheFile.exists()) {
                ImageProxyResponse(
                    bytes = cacheFile.readBytes(),
                    contentType = guessContentTypeFromExt(ext),
                )
            } else {
                cacheDir.mkdirs()
                val resp = AppUtil.GlobalVars.ktorClientProxied.get(url) {
                    timeout {
                        connectTimeoutMillis = 30_000
                        requestTimeoutMillis = 60_000
                        socketTimeoutMillis = 60_000
                    }
                }
                val ctStr = resp.contentType()
                    ?.let { "${it.contentType}/${it.contentSubtype}" }
                    ?: guessContentTypeFromExt(ext)
                val bytes = resp.body<ByteArray>()
                cacheFile.writeBytes(bytes)
                ImageProxyResponse(bytes = bytes, contentType = ctStr)
            }
        }
    }

    private fun guessContentTypeFromExt(ext: String): String {
        return when (ext.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "avif" -> "image/avif"
            else -> "image/jpeg"
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

data class ImageProxyResponse(
    val bytes: ByteArray,
    val contentType: String,
)
