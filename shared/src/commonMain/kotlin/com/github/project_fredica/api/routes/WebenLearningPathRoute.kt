package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
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
 * 算法：在 weben_relation 图上做 Dijkstra 最短路径。
 *   节点权重 = 1.0 - mastery（掌握度越低，越需要先学）
 *   边权重由 predicate 决定：'依赖' = 1.0（强制前置），其他 = 0.5
 *   掌握度 >= 0.8 的节点标记为"已掌握"但保留在路径中。
 */
object WebenLearningPathRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "给定目标概念，返回推荐学习路径（Dijkstra，节点权重 = 1 - mastery）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenLearningPathParam>().getOrThrow()

        val targetConcept = WebenConceptService.repo.getById(p.targetConceptId)
            ?: return buildValidJson { kv("error", "target concept not found") }

        // 加载全部概念（id → mastery）和关系边
        val allConcepts    = WebenConceptService.repo.listAll(limit = 5000)
        val masteryMap     = allConcepts.associate { it.id to it.mastery }
        val allRelations   = WebenRelationService.repo.listByConcept(p.targetConceptId)
            // 仅取反向依赖（object_id == target 且 predicate == '依赖'）
            // 以及目标的依赖链

        // 构建邻接表（从 object 到 subject，表示"subject 是学习 object 的前置"）
        // 使用完整边列表，但深度探索仅围绕目标
        val allEdges = buildList {
            for (concept in allConcepts.take(500)) {
                addAll(WebenRelationService.repo.listByObject(concept.id)
                    .filter { it.predicate == "依赖" })
            }
        }

        val path = dijkstraPath(p.targetConceptId, masteryMap, allEdges)

        val json = AppUtil.GlobalVars.json
        val pathConcepts = path.mapNotNull { id ->
            allConcepts.find { it.id == id }
        }

        return buildValidJson {
            kv("path",    ValidJsonString(json.encodeToString(pathConcepts)))
            kv("target",  ValidJsonString(json.encodeToString(targetConcept)))
        }
    }

    /**
     * Dijkstra 从目标概念反向寻找需要优先掌握的前置路径。
     * 返回按学习顺序排列的概念 id 列表（从最需要先学的开始）。
     */
    private fun dijkstraPath(
        targetId: String,
        masteryMap: Map<String, Double>,
        edges: List<com.github.project_fredica.db.weben.WebenRelation>,
    ): List<String> {
        // 反向邻接表：target → subjects（学 target 前需要掌握的 subjects）
        val prerequisites = mutableMapOf<String, MutableList<String>>()
        for (edge in edges) {
            if (edge.predicate == "依赖") {
                prerequisites.getOrPut(edge.objectId) { mutableListOf() }.add(edge.subjectId)
            }
        }

        val dist = mutableMapOf<String, Double>()
        val prev = mutableMapOf<String, String>()
        // priority queue: (distance, nodeId)
        val pq = PriorityQueue<Pair<Double, String>>(compareBy { it.first })

        dist[targetId] = 0.0
        pq.add(Pair(0.0, targetId))

        while (pq.isNotEmpty()) {
            val (d, u) = pq.poll()
            if (d > (dist[u] ?: Double.MAX_VALUE)) continue

            val prereqs = prerequisites[u] ?: continue
            for (v in prereqs) {
                val mastery  = masteryMap[v] ?: 0.0
                val weight   = 1.0 - mastery  // 掌握度低的节点权重大（更需要先学）
                val newDist  = d + weight
                if (newDist < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = newDist
                    prev[v] = u
                    pq.add(Pair(newDist, v))
                }
            }
        }

        // 按 dist 升序返回（最需要先学的排前面），排除 target 自身
        return dist.entries
            .filter { it.key != targetId }
            .sortedByDescending { it.value }  // dist 大 = 更前置
            .map { it.key }
            .plus(targetId)                   // 目标放最后
    }
}

@Serializable
data class WebenLearningPathParam(
    @SerialName("target_concept_id") val targetConceptId: String,
)
