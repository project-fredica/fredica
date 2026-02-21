package com.github.project_fredica.apputil

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import java.io.File

suspend fun HttpClient.download(
    url: String,
    dst: File
) {

}