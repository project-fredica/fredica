import type { MaterialVideo } from "~/components/material-library/materialTypes";
import type {
    ApiFetchFn,
    LlmModelMeta,
    LlmModelAvailability,
    MaterialSubtitleContentResponse,
    MaterialSubtitleItem,
    MaterialWebenLlmResult,
    WebenBatchImportResponse,
} from "~/util/materialWebenApi";
import type { WebenSource } from "~/util/weben";

function asRecord(value: unknown): Record<string, unknown> | null {
    return value !== null && typeof value === "object" ? value as Record<string, unknown> : null;
}

function asString(value: unknown, fallback = ""): string {
    return typeof value === "string" ? value : fallback;
}

function asNumber(value: unknown, fallback = 0): number {
    return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function asBoolean(value: unknown, fallback = false): boolean {
    return typeof value === "boolean" ? value : fallback;
}

export function getSafeWorkspaceMaterial(value: unknown): MaterialVideo {
    const record = asRecord(value);
    return {
        id: asString(record?.id),
        type: asString(record?.type),
        source_type: asString(record?.source_type),
        source_id: asString(record?.source_id),
        title: asString(record?.title),
        cover_url: asString(record?.cover_url),
        description: asString(record?.description),
        duration: asNumber(record?.duration),
        local_video_path: asString(record?.local_video_path),
        local_audio_path: asString(record?.local_audio_path),
        transcript_path: asString(record?.transcript_path),
        extra: asString(record?.extra, "{}"),
        created_at: asNumber(record?.created_at),
        updated_at: asNumber(record?.updated_at),
        category_ids: Array.isArray(record?.category_ids)
            ? record!.category_ids.filter((item): item is string => typeof item === "string")
            : [],
    };
}

export interface SafeAppConfig {
    webserver_domain: string | null;
    webserver_port: string | null;
    webserver_schema: "http" | "https" | null;
    webserver_auth_token: string | null;
    session_token: string | null;
}

export function getSafeAppConfig(value: unknown): SafeAppConfig {
    const record = asRecord(value);
    const schema = record?.webserver_schema;
    return {
        webserver_domain: typeof record?.webserver_domain === "string" ? record.webserver_domain : null,
        webserver_port: typeof record?.webserver_port === "string" ? record.webserver_port : null,
        webserver_schema: schema === "http" || schema === "https" ? schema : null,
        webserver_auth_token: typeof record?.webserver_auth_token === "string" ? record.webserver_auth_token : null,
        session_token: typeof record?.session_token === "string" ? record.session_token : null,
    };
}

export function getSafeApiFetch(value: unknown): ApiFetchFn {
    if (typeof value === "function") return value as ApiFetchFn;
    return async () => ({
        resp: new Response("null", { status: 200 }),
        data: null,
    });
}

export function normalizeSubtitleItems(data: unknown): MaterialSubtitleItem[] {
    if (!Array.isArray(data)) return [];
    return data.map(item => {
        const record = asRecord(item);
        return {
            lan: asString(record?.lan),
            lan_doc: asString(record?.lan_doc),
            source: asString(record?.source),
            queried_at: asNumber(record?.queried_at),
            subtitle_url: asString(record?.subtitle_url),
            type: asNumber(record?.type),
        };
    }).filter(item => item.subtitle_url.trim().length > 0);
}

export function normalizeLlmModels(data: unknown): LlmModelMeta[] {
    if (!Array.isArray(data)) return [];
    return data.map(item => {
        const record = asRecord(item);
        return {
            app_model_id: asString(record?.app_model_id),
            label: asString(record?.label) || asString(record?.app_model_id),
            notes: typeof record?.notes === "string" ? record.notes : null,
        };
    }).filter(item => item.app_model_id.trim().length > 0);
}

export function normalizeLlmModelAvailability(data: unknown): LlmModelAvailability {
    const record = asRecord(data);
    return {
        available_count: asNumber(record?.available_count),
        has_any_available_model: asBoolean(record?.has_any_available_model),
        selected_model_id: typeof record?.selected_model_id === "string" ? record.selected_model_id : null,
        selected_model_available: asBoolean(record?.selected_model_available),
    };
}

export function normalizeSubtitleContent(data: unknown, fallback: Pick<MaterialSubtitleContentResponse, "source" | "subtitle_url">): MaterialSubtitleContentResponse {
    const record = asRecord(data);
    return {
        text: asString(record?.text),
        word_count: asNumber(record?.word_count),
        segment_count: asNumber(record?.segment_count),
        source: asString(record?.source, fallback.source),
        subtitle_url: asString(record?.subtitle_url, fallback.subtitle_url),
    };
}

export function normalizeImportResponse(data: unknown): WebenBatchImportResponse | null {
    const record = asRecord(data);
    if (!record) return null;
    return {
        ok: asBoolean(record.ok, false),
        error: typeof record.error === "string" ? record.error : undefined,
        source_id: asString(record.source_id) || undefined,
        concept_created: asNumber(record.concept_created),
        concept_total: asNumber(record.concept_total),
    };
}

export interface WebenValidationIssue {
    level: "error" | "warning";
    scope: "concept" | "result";
    index?: number;
    message: string;
}

export interface WebenValidationResult {
    sanitizedResult: MaterialWebenLlmResult;
    blockingErrors: WebenValidationIssue[];
    warnings: WebenValidationIssue[];
}

export function validateWebenResult(result: MaterialWebenLlmResult): WebenValidationResult {
    const blockingErrors: WebenValidationIssue[] = [];
    const warnings: WebenValidationIssue[] = [];

    const sanitizedConcepts = result.concepts.filter((concept, index) => {
        const hasValidType = Array.isArray(concept.types) && concept.types.some(t => t.trim().length > 0);
        if (!concept.name.trim() || !hasValidType || !concept.description.trim()) {
            blockingErrors.push({ level: "error", scope: "concept", index, message: `概念 #${index + 1} 缺少名称、类型或描述` });
            return false;
        }
        return true;
    });

    if (sanitizedConcepts.length === 0 && result.concepts.length > 0) {
        warnings.push({ level: "warning", scope: "result", message: "当前结果没有可导入的有效概念。" });
    }

    return {
        sanitizedResult: { concepts: sanitizedConcepts },
        blockingErrors,
        warnings,
    };
}

/**
 * 对 API 返回的 WebenSource 对象做守卫处理：
 * - material_id: 空字符串归一化为 null（旧版数据库存储遗留问题）
 * - url: 保留原始值，调用方需自行过滤 "material://" 占位符
 */
export function normalizeWebenSource(data: unknown): WebenSource | null {
    const r = asRecord(data);
    if (!r) return null;
    const id = asString(r.id);
    if (!id) return null;
    const rawMaterialId = r.material_id;
    return {
        id,
        material_id: typeof rawMaterialId === "string" && rawMaterialId.trim().length > 0
            ? rawMaterialId
            : null,
        url: asString(r.url),
        title: asString(r.title),
        source_type: asString(r.source_type),
        bvid: typeof r.bvid === "string" ? r.bvid : null,
        duration_sec: typeof r.duration_sec === "number" && Number.isFinite(r.duration_sec)
            ? r.duration_sec
            : null,
        quality_score: asNumber(r.quality_score, 0.5),
        analysis_status: asString(r.analysis_status, "pending"),
        workflow_run_id: typeof r.workflow_run_id === "string" ? r.workflow_run_id : null,
        progress: asNumber(r.progress),
        created_at: asNumber(r.created_at),
    };
}

export function normalizeWebenResult(data: unknown): MaterialWebenLlmResult {
    const record = asRecord(data);
    const concepts = Array.isArray(record?.concepts) ? record.concepts : [];
    return {
        concepts: concepts.map(item => {
            const concept = asRecord(item);
            return {
                name: asString(concept?.name),
                types: Array.isArray(concept?.types)
                    ? concept.types.filter((t): t is string => typeof t === "string")
                    : [],
                description: asString(concept?.description),
                aliases: Array.isArray(concept?.aliases)
                    ? concept.aliases.filter((alias): alias is string => typeof alias === "string")
                    : undefined,
            };
        }).filter(item => item.name.trim().length > 0),
    };
}
