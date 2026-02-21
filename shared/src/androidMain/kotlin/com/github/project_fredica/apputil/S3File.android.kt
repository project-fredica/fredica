package com.github.project_fredica.apputil

import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine

actual fun S3File.Companion.createHttpClientEngine(): HttpClientEngine? {
    return null
}