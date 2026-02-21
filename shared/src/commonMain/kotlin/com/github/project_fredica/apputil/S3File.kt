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

@Serializable
data class S3File(
    val endpointUrl: String,
    val region: String,
    val bucket: String,
    val path: String,
) {
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

expect fun S3File.Companion.createHttpClientEngine(): aws.smithy.kotlin.runtime.http.engine.HttpClientEngine?

//class AnonymousCredentialsProvider : CredentialsProvider {
//    override suspend fun resolve(attributes: Attributes): Credentials = Credentials("ANONYMOUS", "")
//}