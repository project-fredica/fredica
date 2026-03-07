package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi


// AI建议：按字母序排序
fun FredicaApi.Route.Companion.getCommonRoutes() = listOf(
    BilibiliFavoriteGetPageRoute,
    BilibiliFavoriteGetVideoListRoute,
    BilibiliVideoAiConclusionRoute,
    BilibiliVideoGetPagesRoute,
    ImageProxyRoute,
    MaterialActiveTasksRoute,
    MaterialCategoryCreateRoute,
    MaterialCategoryDeleteRoute,
    MaterialCategoryListRoute,
    MaterialDeleteRoute,
    MaterialDownloadStatusRoute,
    MaterialImportRoute,
    MaterialListRoute,
    MaterialRunTaskRoute,
    MaterialSetCategoriesRoute,
    MaterialTaskCreateRoute,
    MaterialTaskListRoute,
    MaterialTaskUpdateRoute,
    NetworkTestRoute,
    TaskCancelRoute,
    TaskPauseRoute,
    TaskResumeRoute,
    WorkerTaskListRoute,
    WorkflowRunStartRoute,
)
