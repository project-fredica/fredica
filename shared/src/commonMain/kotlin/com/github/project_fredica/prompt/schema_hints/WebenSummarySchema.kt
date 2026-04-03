package com.github.project_fredica.prompt.schema_hints

import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable

@Schema(withSchemaObject = true)
@Description("Weben 知识提取结果，顶层对象包含 concepts 数组字段。")
@Serializable
data class WebenSummary(
    @Description("概念列表。")
    val concepts: List<WebenSummaryConcept> = emptyList(),
)

@Schema(withSchemaObject = true)
@Description("单个概念。")
@Serializable
data class WebenSummaryConcept(
    @Description("概念名称。")
    val name: String,
    @Description("概念类型列表，自由文本字段，至少一个元素。建议使用简短稳定的类别名；程序提供的 examples 仅用于参考，LLM 可以定义新的合理类型，不限于这些示例。")
    val types: List<String>,
    @Description("概念简述。")
    val description: String,
    @Description("概念别名列表，可省略。")
    val aliases: List<String>? = null,
)



