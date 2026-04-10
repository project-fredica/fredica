package com.github.project_fredica.prompt.variables

import com.github.project_fredica.asr.service.MaterialSubtitleService
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.MaterialVideoService

class MaterialPromptVariableResolver : PromptVariableResolver {
    private val logger = createLogger { "MaterialPromptVariableResolver" }

    companion object {
        private val MATERIAL_PATH_REGEX = Regex("""^material/([^/]+)/(.+)$""")
    }

    /**
     * 解析 `material/{id}/{subPath}` 形式的 prompt 变量。
     *
     * 支持的 `subPath`：`title`、`description`、`subtitles/{language}`。
     * 未知路径或格式错误统一返回空串；预期失败打 warn，内部异常打 error。
     */
    override suspend fun resolve(key: String): String {
        logger.debug("[MaterialPromptVariableResolver] resolve start key=$key")
        val match = MATERIAL_PATH_REGEX.find(key)
        if (match != null) {
            val materialId = match.groupValues[1]
            val subPath = match.groupValues[2]
            val result = when {
                subPath == "title" -> try {
                    MaterialVideoService.repo.findById(materialId)?.title ?: ""
                } catch (e: Throwable) {
                    logger.error("[MaterialPromptVariableResolver] title 读取失败 materialId=$materialId", e)
                    ""
                }

                subPath == "description" -> try {
                    MaterialVideoService.repo.findById(materialId)?.description ?: ""
                } catch (e: Throwable) {
                    logger.error("[MaterialPromptVariableResolver] description 读取失败 materialId=$materialId", e)
                    ""
                }

                subPath.startsWith("subtitles/") -> {
                    val lan = subPath.removePrefix("subtitles/").takeIf { it.isNotBlank() }.let {
                        if (it == "first") null else it
                    }
                    try {
                        MaterialSubtitleService.fetchSubtitleText(materialId, lan) ?: ""
                    } catch (e: Throwable) {
                        logger.error(
                            "[MaterialPromptVariableResolver] subtitle 读取失败 materialId=$materialId lan=$lan",
                            e,
                        )
                        ""
                    }
                }

                else -> {
                    logger.warn(
                        "[MaterialPromptVariableResolver] 未知 subPath=$subPath key=$key",
                        isHappensFrequently = false, err = null,
                    )
                    ""
                }
            }
            logger.debug(
                "[MaterialPromptVariableResolver] resolve done key=$key subPath=$subPath isBlank=${result.isBlank()} length=${result.length}",
            )
            return result
        }
        if (key.startsWith("material/")) {
            logger.warn(
                "[MaterialPromptVariableResolver] material 路径格式错误 key='$key'（期望 material/{id}/{subPath}，__materialId 可能为空）",
                isHappensFrequently = false, err = null,
            )
            return ""
        }
        logger.warn(
            "[MaterialPromptVariableResolver] 未知变量 key='$key'（期望格式：material/{id}/{subPath}）",
            isHappensFrequently = false, err = null,
        )
        return ""
    }
}
