package com.github.project_fredica.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable


sealed interface Response<R>

@Serializable
@JvmInline
value class EndResponse<R>(val result: R) : Response<R>

@Serializable
data class FlowResult<R>(val packIndex: UInt, val result: R)

@Serializable
@JvmInline
value class FlowResponse<R>(val flow: Flow<FlowResult<R>>) : Response<R>