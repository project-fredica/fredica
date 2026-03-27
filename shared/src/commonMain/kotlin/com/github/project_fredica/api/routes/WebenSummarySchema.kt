package com.github.project_fredica.api.routes

import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable

@Schema(withSchemaObject = true)
@Description("Weben 知识提取结果，顶层对象包含 concepts、relations、flashcards 三个数组字段。")
@Serializable
data class WebenSummary(
    @Description("概念列表。")
    val concepts: List<WebenSummaryConcept> = emptyList(),
    @Description("关系列表。")
    val relations: List<WebenSummaryRelation> = emptyList(),
    @Description("闪卡列表。")
    val flashcards: List<WebenSummaryFlashcard> = emptyList(),
)

@Schema(withSchemaObject = true)
@Description("单个概念。")
@Serializable
data class WebenSummaryConcept(
    @Description("概念名称。")
    val name: String,
    @Description("概念类型。示例：术语、理论、协议、算法、器件芯片、公式、设计模式、工具软件、硬件经验、开发经验、方法技巧。")
    val type: String,
    @Description("概念简述。")
    val description: String,
    @Description("概念别名列表，可省略。")
    val aliases: List<String>? = null,
)

@Schema(withSchemaObject = true)
@Description("单个关系。")
@Serializable
data class WebenSummaryRelation(
    @Description("关系主语，对应 concepts 中某个概念名称。")
    val subject: String,
    @Description("关系谓词。示例：包含、依赖、用于、对比、是...的实例、实现、扩展。")
    val predicate: String,
    @Description("关系宾语，对应 concepts 中某个概念名称。")
    val `object`: String,
    @Description("支撑该关系的原文摘录，可省略。")
    val excerpt: String? = null,
)

@Schema(withSchemaObject = true)
@Description("单个闪卡。")
@Serializable
data class WebenSummaryFlashcard(
    @Description("问题。")
    val question: String,
    @Description("答案。")
    val answer: String,
    @Description("该闪卡关联的概念名称，对应 concepts 中某个概念名称。")
    val concept: String,
)
