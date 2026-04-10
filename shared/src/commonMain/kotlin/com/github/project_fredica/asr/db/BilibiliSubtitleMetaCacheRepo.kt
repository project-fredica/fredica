package com.github.project_fredica.asr.db

import com.github.project_fredica.asr.model.BilibiliSubtitleMetaCache

interface BilibiliSubtitleMetaCacheRepo {
    suspend fun insert(entry: BilibiliSubtitleMetaCache)
    suspend fun queryBest(bvid: String, pageIndex: Int): BilibiliSubtitleMetaCache?
}
