package com.github.project_fredica.db.promptgraph

// =============================================================================
// PromptGraphDef —— 提示词图"设计"（静态蓝图）
// =============================================================================
//
// 管理一张表：prompt_graph_def
//
// 三层模型：
//   PromptGraphDef（蓝图）→ PromptGraphRun（运行实例）→ PromptNodeRun（节点记录）
//
// 节点定义（PromptNodeDef）、边定义（PromptEdgeDef）、Schema 注册表等
// 均以 JSON 字符串形式存储在 DB 的 *_json 字段中，DB 行只持有顶层元数据。
//
// Phase B MVP 仅支持：LLM_CALL 节点、TRANSFORM（Kotlin-handler）节点。
// CONDITION、HUMAN_REVIEW、MCP 留待后续阶段。
// =============================================================================

import com.github.project_fredica.promptgraph.TransformHandlerRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =============================================================================
// 嵌套数据类（存储在 nodes_json / edges_json / schema_registry_json 中）
// =============================================================================

/**
 * 节点类型枚举。
 *
 * Phase B MVP 实现：LLM_CALL、TRANSFORM（Kotlin 处理函数）。
 * 其余类型（CONDITION、MERGE、HUMAN_REVIEW）后续阶段添加。
 */
enum class PromptNodeType {
    LLM_CALL,
    TRANSFORM,
    CONDITION,
    MERGE,
    HUMAN_REVIEW,
}

/**
 * 单个提示词节点定义。
 *
 * 注意：TRANSFORM 节点在 Phase B 使用 [transformHandler]（Kotlin 函数名）
 * 而不是 JS 表达式字符串。此妥协在引擎侧有明确注释。
 */
@Serializable
data class PromptNodeDef(
    /** 节点唯一标识（在本 Graph 内唯一）。 */
    val id: String,
    /** 节点类型。 */
    val type: PromptNodeType,
    /** 展示名称（前端画布标签）。 */
    val label: String,

    // ── LLM_CALL 专属 ─────────────────────────────────────────────────────
    /** 使用哪个默认角色（"chat" | "vision" | "coding" | "dev_test"）。 */
    @SerialName("model_role") val modelRole: String? = null,
    /** 若非 null，覆盖 model_role 指定的模型（使用 app_model_id）。 */
    @SerialName("model_id_override") val modelIdOverride: String? = null,
    /** 系统提示词（支持 %变量% 替换）。 */
    @SerialName("system_prompt") val systemPrompt: String? = null,
    /** 用户提示词模板（支持 %变量% 替换）。 */
    @SerialName("user_prompt_tpl") val userPromptTpl: String? = null,
    /** 覆盖模型默认温度；null = 使用模型配置默认值。 */
    @SerialName("temperature_override") val temperatureOverride: Double? = null,
    /** 覆盖最大输出 token；null = 使用模型配置默认值。 */
    @SerialName("max_tokens_override") val maxTokensOverride: Int? = null,

    // ── TRANSFORM 专属（Phase B MVP：Kotlin 处理函数，非 JS 表达式）─────────
    /**
     * Kotlin 转换处理函数名称。
     *
     * MVP 实现注意：本字段存储在 [TransformHandlerRegistry] 中注册的函数 key，
     * **不是** prompt-graph.md §2.3 中描述的 JS 表达式字符串。
     * 后续阶段引入 JS 引擎（GraalVM/QuickJS）后，此字段语义将变更为 JS 表达式。
     */
    @SerialName("transform_handler") val transformHandler: String? = null,

    // ── CONDITION 专属（Phase B 不执行，仅保留字段）────────────────────────
    @SerialName("condition_expr") val conditionExpr: String? = null,

    // ── 通用字段 ──────────────────────────────────────────────────────────
    /**
     * 注入上游上下文的节点 ID 白名单。
     * 空列表 = 只注入直接上游节点的输出（引擎默认行为）。
     */
    @SerialName("context_include") val contextInclude: List<String> = emptyList(),
    /** 注入上下文的最大字符数（超出则截断）。 */
    @SerialName("context_max_chars") val contextMaxChars: Int = 8000,
    /** 失败重试次数（默认 0 = 不重试）。 */
    @SerialName("max_retries") val maxRetries: Int = 0,
    /** 画布 X 坐标（前端展示用）。 */
    @SerialName("position_x") val positionX: Double = 0.0,
    /** 画布 Y 坐标（前端展示用）。 */
    @SerialName("position_y") val positionY: Double = 0.0,
)

/**
 * 边定义。Schema 绑定在边上（而非节点上），描述此数据通道的类型契约。
 * 详见 prompt-graph.md §2.4。
 */
@Serializable
data class PromptEdgeDef(
    /** 边唯一标识（在本 Graph 内唯一）。 */
    val id: String,
    /** 出发节点 ID。 */
    @SerialName("source_node_id") val sourceNodeId: String,
    /** 目标节点 ID。 */
    @SerialName("target_node_id") val targetNodeId: String,
    /** CONDITION 节点出边专用：分支 key（如 "true" / "false"）。 */
    @SerialName("condition_key") val conditionKey: String? = null,
    /** 此边传递数据的 Schema ID（引用 schema_registry 中的 SchemaEntry.id）；null = 自由文本。 */
    @SerialName("schema_id") val schemaId: String? = null,
    /** 可选边标签（前端展示用，如 "概念列表"）。 */
    val label: String? = null,
)

/**
 * Schema 注册表条目。每个 PromptGraphDef 内嵌全图 schema 注册表。
 */
@Serializable
data class SchemaEntry(
    /** 在本 PromptGraphDef 内唯一的 schema 标识，如 "concept_list"。 */
    val id: String,
    /** 人类可读描述。 */
    val description: String? = null,
    /**
     * 标准 JSON Schema（Draft 7）对象，以字符串形式存储。
     * 引擎在构造 LLM 请求的 response_format 时从此字段反序列化。
     */
    val schema: String,
    /** 语义版本（"major.minor.patch"），用于历史数据迁移检测。 */
    val version: String = "1.0.0",
    /** 此版本相对上一版本是否为破坏性变更。 */
    @SerialName("breaking_change") val breakingChange: Boolean = false,
)

// =============================================================================
// PromptGraphDef（DB 行模型）
// =============================================================================

/**
 * 提示词图蓝图（DB 行模型）。
 *
 * 节点列表、边列表、Schema 注册表等复杂嵌套结构均以 JSON 字符串存储，
 * 避免过度规范化带来的 JOIN 负担，保持 DDL 简洁。
 */
@Serializable
data class PromptGraphDef(
    /** UUID（系统内置图使用固定前缀，如 "system:weben_video_concept_extract"）。 */
    val id: String,
    /** 人类可读名称，如 "视频概念提取图"。 */
    val name: String,
    /** 图描述（可选）。 */
    val description: String? = null,
    /** [PromptNodeDef] 列表的 JSON 序列化。 */
    @SerialName("nodes_json") val nodesJson: String = "[]",
    /** [PromptEdgeDef] 列表的 JSON 序列化（含 schema_id 绑定）。 */
    @SerialName("edges_json") val edgesJson: String = "[]",
    /** [SchemaEntry] 列表的 JSON 序列化（全图 Schema 注册表）。 */
    @SerialName("schema_registry_json") val schemaRegistryJson: String = "[]",
    /** 迁移脚本列表的 JSON 序列化（Phase B 不使用，占位保留字段）。 */
    @SerialName("migrations_json") val migrationsJson: String = "[]",
    /** 图版本号（整数，每次保存 +1）。 */
    val version: Int = 1,
    /** 图对外契约的语义版本（"major.minor.patch"）。 */
    @SerialName("schema_version") val schemaVersion: String = "1.0.0",
    /** 来源类型：'system'（内置不可删）| 'user'（用户创建）| 'system_fork'（Fork 副本）。 */
    @SerialName("source_type") val sourceType: String = "user",
    /** Fork 来源 ID（仅 system_fork 时有值）。 */
    @SerialName("parent_def_id") val parentDefId: String? = null,
    /** Fork 时父图的 version 快照。 */
    @SerialName("parent_def_ver_at_fork") val parentDefVerAtFork: Int? = null,
    /** Fork 时父图的 schema_version 快照。 */
    @SerialName("parent_schema_ver_at_fork") val parentSchemaVerAtFork: String? = null,
    /** 记录创建时间，Unix 秒。 */
    @SerialName("created_at") val createdAt: Long,
    /** 最后更新时间，Unix 秒。 */
    @SerialName("updated_at") val updatedAt: Long,
)

// =============================================================================
// PromptGraphDefRepo
// =============================================================================

interface PromptGraphDefRepo {
    /**
     * 幂等写入 Graph 定义。
     * 冲突时（同 id）完整替换（用于系统图版本升级）。
     */
    suspend fun upsert(def: PromptGraphDef)
    suspend fun getById(id: String): PromptGraphDef?
    suspend fun listAll(
        sourceType: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): List<PromptGraphDef>

    /** 删除 Graph 定义（system 类型由调用方在业务层拦截，Repo 层不限制）。 */
    suspend fun deleteById(id: String)
}

// =============================================================================
// PromptGraphDefService
// =============================================================================

object PromptGraphDefService {
    private var _repo: PromptGraphDefRepo? = null
    val repo: PromptGraphDefRepo
        get() = _repo ?: error("PromptGraphDefService 未初始化，请先调用 initialize()")

    fun initialize(repo: PromptGraphDefRepo) {
        _repo = repo
    }
}
