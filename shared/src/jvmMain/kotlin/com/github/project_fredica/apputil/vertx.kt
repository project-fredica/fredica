@file:Suppress("UnusedReceiverParameter")

package com.github.project_fredica.apputil

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json


private val vertx: Vertx by lazy { Vertx.vertx() }

val AppUtil.GlobalVars.globalVertx: Vertx get() = vertx

fun JsonObject.toKotlinJson(): kotlinx.serialization.json.JsonObject {
    return this.toString().let { Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }
}


@Suppress("NOTHING_TO_INLINE")
inline fun Buffer.u8(idx: Int): UByte = this.getByte(idx).toUByte()

@Suppress("UnusedReceiverParameter")
private val Dispatchers.Vertx
    get(): CoroutineDispatcher {
        return vertx.dispatcher()
    }

suspend fun <T> withContextVertx(block: suspend CoroutineScope.(vertx: io.vertx.core.Vertx) -> T): T {
    return withContext(Dispatchers.Vertx) {
        block(vertx)
    }
}

suspend fun closeVertx() {
    withContext(Dispatchers.IO) {
        vertx.close().coAwait()
    }
}