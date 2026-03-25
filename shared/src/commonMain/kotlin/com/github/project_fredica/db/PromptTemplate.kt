package com.github.project_fredica.db

// =============================================================================
// PromptTemplate —— Prompt 脚本模板数据模型
// =============================================================================
//
// 设计说明：
//   - 模板分两类来源：system（系统内置，只读）/ user（用户创建，可增删改）
//   - 模板正文（script_code）是 JS 脚本，入口约定为 async function main()
//   - 不包含 variables 元数据：上下文通过宿主 API 在运行时注入
//   - based_on_template_id 仅作内部溯源元数据，不引入第三种来源概念
// =============================================================================

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Prompt 脚本模板，存储于 prompt_template 表 */
@Serializable
data class PromptTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    /** 业务分类，例如 "weben_extract" */
    val category: String = "",
    /** 模板来源："system"（只读内置）/ "user"（用户创建） */
    @SerialName("source_type") val sourceType: String = "user",
    /** 脚本语言标识，当前固定为 "javascript" */
    @SerialName("script_language") val scriptLanguage: String = "javascript",
    /** 模板脚本正文，入口为 async function main() { ... } */
    @SerialName("script_code") val scriptCode: String = "",
    /**
     * 该模板面向的 schema 版本（如 "weben_v1"）。
     * 用于在 UI 中提示模板与当前 schema 的兼容性。
     */
    @SerialName("schema_target") val schemaTarget: String = "",
    /**
     * 若此模板基于某个系统模板复制而来，记录原模板 id。
     * 仅作溯源用，不新增第三种来源类型。
     */
    @SerialName("based_on_template_id") val basedOnTemplateId: String? = null,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
)

/** PromptTemplate 仓库接口（由 PromptTemplateDb 实现） */
interface PromptTemplateRepo {
    /** 返回所有用户模板，按 updated_at 倒序 */
    suspend fun listUserTemplates(): List<PromptTemplate>

    /** 按 category 过滤用户模板，结果按 updated_at 倒序 */
    suspend fun listUserTemplatesByCategory(category: String): List<PromptTemplate>

    /** 按 id 查询，找不到返回 null */
    suspend fun getById(id: String): PromptTemplate?

    /**
     * 保存用户模板（INSERT OR REPLACE）。
     * source_type 强制为 "user"，调用方不必手动设置。
     */
    suspend fun save(template: PromptTemplate): PromptTemplate

    /**
     * 删除用户模板。
     * 若 id 不存在或对应的是系统模板，返回 false。
     */
    suspend fun delete(id: String): Boolean
}

/** PromptTemplate 服务单例，由 FredicaApi.jvm.kt 初始化 */
object PromptTemplateService {
    private var _repo: PromptTemplateRepo? = null

    val repo: PromptTemplateRepo
        get() = _repo ?: throw IllegalStateException("PromptTemplateService not initialized")

    fun initialize(repo: PromptTemplateRepo) {
        _repo = repo
    }
}
