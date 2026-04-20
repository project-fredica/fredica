package com.github.project_fredica.material_category.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MaterialCategorySyncPlatformInfoSummary(
    val id: String,
    @SerialName("sync_type") val syncType: String,
    @SerialName("platform_config") val platformConfig: MaterialCategorySyncPlatformIdentity,
    @SerialName("display_name") val displayName: String,
    @SerialName("last_synced_at") val lastSyncedAt: Long? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("sync_state") val syncState: String = "idle",
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("fail_count") val failCount: Int = 0,
    @SerialName("subscriber_count") val subscriberCount: Int = 0,
    @SerialName("my_subscription") val mySubscription: MaterialCategorySyncUserConfigSummary? = null,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("last_workflow_run_id") val lastWorkflowRunId: String? = null,
)

@Serializable
data class MaterialCategorySyncUserConfigSummary(
    val id: String,
    val enabled: Boolean = true,
    @SerialName("cron_expr") val cronExpr: String = "0 */6 * * *",
    @SerialName("freshness_window_sec") val freshnessWindowSec: Int = 3600,
)
