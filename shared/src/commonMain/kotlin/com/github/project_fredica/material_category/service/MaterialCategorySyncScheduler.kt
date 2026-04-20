package com.github.project_fredica.material_category.service

object MaterialCategorySyncScheduler {
    suspend fun recoverStale(timeoutSec: Long = 1800) {
        val stale = MaterialCategorySyncPlatformInfoService.repo.findStale("syncing", timeoutSec)
        for (info in stale) {
            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncFailure(info.id, "同步超时")
        }
    }
}
