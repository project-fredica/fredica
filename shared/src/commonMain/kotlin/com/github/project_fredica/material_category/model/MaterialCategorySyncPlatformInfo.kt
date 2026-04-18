package com.github.project_fredica.material_category.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MaterialCategorySyncPlatformInfo(
    val id: String,
    @SerialName("sync_type") val syncType: String,
    @SerialName("platform_id") val platformId: String,
    @SerialName("platform_config") val platformConfig: String = "{}",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("category_id") val categoryId: String,
    @SerialName("last_synced_at") val lastSyncedAt: Long? = null,
    @SerialName("sync_cursor") val syncCursor: String = "",
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("sync_state") val syncState: String = "idle",
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("fail_count") val failCount: Int = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)
