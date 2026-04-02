package com.github.project_fredica.prompt

// =============================================================================
// PromptRuntimeContextProvider (jvmMain)
// =============================================================================
//
// 为 GraalJS 沙箱提供 Prompt 运行时能力。
//
// 设计约束：
//   - 所有公开方法均为普通（非 suspend）函数，通过 resolver 内部按需调用 DB / 路由。
//   - 由 PromptScriptRuntime 将这些方法注入为 GraalJS ProxyExecutable。
//   - 每次执行创建一次实例，追踪 API 调用次数（上限 20 次）。
//
// 职责边界：
//   - getVar(key)        → 委托给 prompt.variables 下的变量解析器
//   - getSchemaHint(key) → 委托给 prompt.schema_hints 下的提示解析器
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.prompt.schema_hints.PromptSchemaHintResolver
import com.github.project_fredica.prompt.variables.MaterialPromptVariableResolver

class PromptRuntimeContextProvider {
    private val logger = createLogger { "PromptRuntimeContextProvider" }
    private var apiCallCount = 0
    private val variableResolver = MaterialPromptVariableResolver()
    private val schemaHintResolver = PromptSchemaHintResolver()

    companion object {
        const val MAX_API_CALLS = 20
    }

    private fun checkQuota() {
        if (++apiCallCount > MAX_API_CALLS) {
            throw RuntimeException("API 调用次数超出限制（最多 $MAX_API_CALLS 次）")
        }
    }

    /**
     * 解析脚本中的运行时变量。
     *
     * 当前仅 `getVar()` 计入 API 调用配额；未知 key 由下游 resolver 自行记录日志并返回空串。
     * 返回值约定为纯文本字符串，调用方无需再做 JSON 解析。
     */
    fun getVar(key: String): String {
        checkQuota()
        logger.debug("[PromptRuntimeContextProvider] getVar start key=$key apiCallCount=$apiCallCount")
        val result = variableResolver.resolve(key)
        logger.debug(
            "[PromptRuntimeContextProvider] getVar done key=$key isBlank=${result.isBlank()} length=${result.length}",
        )
        return result
    }

    /**
     * 解析脚本中的 schema hint。
     *
     * schema hint 不计入 API 调用配额；当前会合并后端资源中的默认 concept type examples
     * 与已有 WebenConcept.type distinct 值，并注入到 JSON Schema 的 example/examples 中。
     * 这些值仅用于提示 LLM，不构成允许列表；返回空串表示当前 key 没有对应 hint。
     */
    fun getSchemaHint(key: String): String {
        logger.debug("[PromptRuntimeContextProvider] getSchemaHint start key=$key")
        val result = schemaHintResolver.resolve(key)
        logger.debug(
            "[PromptRuntimeContextProvider] getSchemaHint done key=$key isBlank=${result.isBlank()} length=${result.length}",
        )
        return result
    }
}
