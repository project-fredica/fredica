package com.github.project_fredica.apputil

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.SystemPropertyCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectResponse
import aws.sdk.kotlin.services.s3.model.ListObjectsRequest
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.io.files.FileNotFoundException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 代表 S3 兼容存储（如 Amazon S3、MinIO、Cloudflare R2）中一个文件对象的元数据。
 *
 * 可通过 [getObject] 实际下载文件内容，支持静态凭据或系统属性凭据两种鉴权方式。
 * 可通过 [loadFromJsonObject] 从 JSON 配置中反序列化。
 *
 * @property endpointUrl S3 服务端点 URL，例如 `https://s3.amazonaws.com` 或 MinIO 地址
 * @property region      存储桶所在区域，例如 `us-east-1`（MinIO 可填任意值）
 * @property bucket      存储桶名称
 * @property path        文件在桶内的路径；以 `/` 开头时会在请求前自动去除前导斜线
 */
@Serializable
data class S3File(
    val endpointUrl: String,
    val region: String,
    val bucket: String,
    val path: String,
) {
    /**
     * 下载此 S3 文件对象并将响应传递给 [block] 处理。
     *
     * 凭据优先级：
     * 1. 若 [accessKeyId] 和 [secretAccessKey] 均不为空，使用静态凭据（[StaticCredentialsProvider]）
     * 2. 否则，从系统属性（`aws.accessKeyId` / `aws.secretAccessKey`）读取（[SystemPropertyCredentialsProvider]）
     *
     * 内部先通过 `listObjects` 以 [path] 为前缀确认文件存在，再调用 `getObject` 获取内容，
     * 若未找到文件则抛出 [kotlinx.io.files.FileNotFoundException]。
     *
     * @param accessKeyId     S3 Access Key ID，为 null 则使用系统属性凭据
     * @param secretAccessKey S3 Secret Access Key，为 null 则使用系统属性凭据
     * @param block           接收 [GetObjectResponse] 的处理逻辑，在此 lambda 中读取 `body`
     */
    suspend fun getObject(
        accessKeyId: String? = null,
        secretAccessKey: String? = null,
        block: suspend (resp: GetObjectResponse) -> Unit
    ) {
        val logger = createLogger()
        logger.debug("s3file is $this")
        val s3client = S3Client.builder().apply {
            config.apply {
                createHttpClientEngine()?.let {
                    this.httpClient = it
                }
                endpointUrl = Url.parse(this@S3File.endpointUrl)
                region = this@S3File.region
                if (!accessKeyId.isNullOrBlank() && !secretAccessKey.isNullOrBlank()) {
                    credentialsProvider = StaticCredentialsProvider {
                        this.accessKeyId = accessKeyId
                        this.secretAccessKey = secretAccessKey
                    }
                } else {
                    credentialsProvider = SystemPropertyCredentialsProvider()
                }
            }
        }.build()
        var pth = this@S3File.path
        while (pth.startsWith("/")) {
            pth = pth.removePrefix("/")
        }
        s3client.use {
            logger.debug("s3client is $s3client")
            val listResp = s3client.listObjects(ListObjectsRequest {
                bucket = this@S3File.bucket
//                val dirname = pth.slice(0 until pth.lastIndexOf("/"))
                logger.debug("listObjects : $pth")
                prefix = pth
            })
            val listContents = listResp.contents
            logger.debug("listObjects response content : $listContents")
            if (listContents.isNullOrEmpty()) {
                throw FileNotFoundException("S3File not found : $S3File")
            }
            val obj = listContents[0]
            logger.debug("S3File object is : $obj")
            s3client.getObject(GetObjectRequest {
                this.bucket = this@S3File.bucket
                this.key = obj.key
            }) { resp ->
                logger.debug("S3File getObject resp is $resp")
                val contentLength = resp.contentLength ?: throw IllegalStateException("Missing content length")
                logger.debug(
                    "S3File content length is $contentLength , file size is ${FileSize(contentLength)}"
                )
                block(resp)
            }
        }
    }

    companion object {
        /**
         * 从 [JsonObject] 中解析 [S3File] 实例，包装在 [Result] 中。
         * 期望 JSON 包含 `endpointUrl`、`region`、`bucket`、`path` 四个字符串字段。
         */
        fun loadFromJsonObject(j: JsonObject) = Result.wrap {
            val endpointUrl = j["endpointUrl"].asT<String>().getOrThrow()
            val region = j["region"].asT<String>().getOrThrow()
            val bucket = j["bucket"].asT<String>().getOrThrow()
            val path = j["path"].asT<String>().getOrThrow()
            S3File(
                endpointUrl = endpointUrl, region = region, bucket = bucket, path = path
            )
        }
    }
}

/**
 * 创建平台特定的 AWS HTTP 客户端引擎（如 OkHttp）。
 * 返回 null 时 AWS SDK 将使用其默认引擎。
 * 由各平台 actual 实现提供。
 */
expect fun S3File.Companion.createHttpClientEngine(): aws.smithy.kotlin.runtime.http.engine.HttpClientEngine?

//class AnonymousCredentialsProvider : CredentialsProvider {
//    override suspend fun resolve(attributes: Attributes): Credentials = Credentials("ANONYMOUS", "")
//}