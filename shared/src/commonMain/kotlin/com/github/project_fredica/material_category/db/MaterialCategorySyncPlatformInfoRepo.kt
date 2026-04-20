package com.github.project_fredica.material_category.db

import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformInfo

interface MaterialCategorySyncPlatformInfoRepo {
    suspend fun getById(id: String): MaterialCategorySyncPlatformInfo?
    suspend fun findByPlatformKey(syncType: String, platformId: String): MaterialCategorySyncPlatformInfo?
    suspend fun findByCategoryId(categoryId: String): MaterialCategorySyncPlatformInfo?
    suspend fun create(info: MaterialCategorySyncPlatformInfo)
    suspend fun deleteById(id: String): Boolean
    suspend fun updateAfterSyncSuccess(
        id: String,
        syncCursor: String,
        lastSyncedAt: Long,
        itemCount: Int,
    )
    suspend fun updateAfterSyncFailure(id: String, error: String)
    suspend fun setSyncState(id: String, state: String)
    suspend fun updateDisplayName(id: String, displayName: String)
    suspend fun setLastWorkflowRunId(id: String, workflowRunId: String)
    suspend fun findStale(syncState: String, olderThanSec: Long): List<MaterialCategorySyncPlatformInfo>
}
