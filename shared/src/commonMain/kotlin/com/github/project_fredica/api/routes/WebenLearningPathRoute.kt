package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenRelationService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.PriorityQueue

/**
 * POST /api/v1/WebenLearningPathRoute
 *
 * 给定目标概念 id，返回推荐学习路径（概念序列 + 每步掌握度）。
 *
 * ## 算法：反向 Dijkstra（从目标出发，沿"依赖"关系找前置节点）
 *
 * - **节点权重** = `1.0 - mastery`（掌握度越低，权重越大，越需要优先学习）
 * - **边过滤**：仅采用 predicate == "依赖" 的关系边（语义：A 依赖 B = 学 A 前需先学 B）
 * - **路径方向**：从 target 反向遍历前置节点，最终按"学习优先级"正序排列
 * - **结果排序**：dist 越大 → 越是深层前置 → 越早学习；target 自身放在最后
 *
 * ## 限制（当前实现）
 * - 图加载上限 500 个概念（避免超大图时性能抖动）
 * - 仅探索 target 的入边（listByObject），其他概念间的关系不纳入
 * - 掌握度 >= 0.8 的节点仍保留在路径中（标记为"已掌握"，前端可视情况折叠显示）
 */
object WebenLearningPathRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenLearningPathRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "给定目标概念，返回推荐学习路径（Dijkstra，节点权重 = 1 - mastery）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenLearningPathParam>().getOrThrow()
        logger.debug("WebenLearningPathRoute: 计算学习路径 targetConceptId=${p.targetConceptId}")

        val targetConcept = WebenConceptService.repo.getById(p.targetConceptId)
        if (targetConcept == null) {
            logger.debug("WebenLearningPathRoute: 目标概念不存在 id=${p.targetConceptId}")
            return buildValidJson { kv("error", "target concept not found") }
        }

        // 加载全部概念（id → mastery）和关系边
        val allConcepts  = WebenConceptService.repo.listAll(limit = 5000)
        val masteryMap   = allConcepts.associate { it.id to it.mastery }
        logger.debug("WebenLearningPathRoute: 已加载 ${allConcepts.size} 个概念")

        // 采集图中所有"依赖"边（取前 500 个概念的入边，防止超大图卡顿）
        // 注：allRelations 变量名保留（原代码 lint 不报 unused，此处未使用；仅为可读性）
        val allEdges = buildList {
            for (concept in allConcepts.take(500)) {
                addAll(WebenRelationService.repo.listByObject(concept.id)
                    .filter { it.predicate == "依赖" })
            }
        }
        logger.debug(
            "WebenLearningPathRoute: 图构建完成，\"依赖\"边 ${allEdges.size} 条" +
            " (来自前 ${minOf(500, allConcepts.size)} 个概念的入边)"
        )

        // 反向 Dijkstra：找出目标的所有前置概念及其学习优先级
        val path = dijkstraPath(p.targetConceptId, masteryMap, allEdges)

        val pathConcepts = path.mapNotNull { id -> allConcepts.find { it.id == id } }
        logger.info(
            "WebenLearningPathRoute: 路径计算完成 targetId=${p.targetConceptId}" +
            " pathLength=${pathConcepts.size} (含 target)"
        )

        val json = AppUtil.GlobalVars.json
        return buildValidJson {
            kv("path",   ValidJsonString(json.encodeToString(pathConcepts)))
            kv("target", ValidJsonString(json.encodeToString(targetConcept)))
        }
    }

    /**
     * 从目标概念反向寻找需要优先掌握的前置路径（反向 Dijkstra）。
     *
     * 返回按学习顺序排列的概念 id 列表（从最需要先学的前置开始，目标放最后）。
     *
     * 原理：
     *   - 以 target 为源节点，dist[v] 累加 = 经过的所有节点的 (1 - mastery) 之和
     *   - dist 越大 → 前置层数越深、掌握度越低 → 越需要优先学习
     *
     * @param targetId   目标概念 id
     * @param masteryMap 概念 id → 掌握度（0.0-1.0）
     * @param edges      全图"依赖"关系边
     */
    private fun dijkstraPath(
        targetId: String,
        masteryMap: Map<String, Double>,
        edges: List<com.github.project_fredica.db.weben.WebenRelation>,
    ): List<String> {
        // 反向邻接表：objectId → subjectIds（学 object 前需要掌握的 subjects）
        val prerequisites = mutableMapOf<String, MutableList<String>>()
        for (edge in edges) {
            if (edge.predicate == "依赖") {
                prerequisites.getOrPut(edge.objectId) { mutableListOf() }.add(edge.subjectId)
            }
        }

        val dist = mutableMapOf<String, Double>()
        val prev = mutableMapOf<String, String>()
        // PriorityQueue：(dist, nodeId)，按 dist 升序弹出（最短路径优先）
        val pq = PriorityQueue<Pair<Double, String>>(compareBy { it.first })

        dist[targetId] = 0.0
        pq.add(Pair(0.0, targetId))

        while (pq.isNotEmpty()) {
            val (d, u) = pq.poll()
            // 跳过过期条目（dist 已被更短路径更新）
            if (d > (dist[u] ?: Double.MAX_VALUE)) continue

            val prereqs = prerequisites[u] ?: continue
            for (v in prereqs) {
                // 节点权重 = 1 - mastery：掌握度越低，权重越高
                val mastery = masteryMap[v] ?: 0.0
                val weight  = 1.0 - mastery
                val newDist = d + weight
                if (newDist < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = newDist
                    prev[v] = u
                    pq.add(Pair(newDist, v))
                }
            }
        }

        // 按 dist 降序排列（dist 大 = 更深层的前置，需要更早学习），最后附上 target 自身
        return dist.entries
            .filter { it.key != targetId }
            .sortedByDescending { it.value }
            .map { it.key }
            .plus(targetId)
    }
}

@Serializable
data class WebenLearningPathParam(
    @SerialName("target_concept_id") val targetConceptId: String,
)
