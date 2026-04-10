package com.github.project_fredica.asr.db

import com.github.project_fredica.asr.model.BilibiliSubtitleBodyCache

interface BilibiliSubtitleBodyCacheRepo {
    suspend fun insert(entry: BilibiliSubtitleBodyCache)
    suspend fun queryBest(urlKey: String): BilibiliSubtitleBodyCache?
}
