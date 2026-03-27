@file:Suppress("KDocUnresolvedReference")

package com.github.project_fredica.apputil

object SseLineParser {
    /** 返回 null = 非 data 行；"[DONE]" = 流结束；其他 = data 内容 */
    fun parseLine(line: String): String? =
        if (line.startsWith("data:")) line.removePrefix("data:").trim() else null
}
