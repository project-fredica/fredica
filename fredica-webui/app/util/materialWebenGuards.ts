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
import { CONCEPT_TYPES, PREDICATES } from "~/util/weben";

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
}

export function getSafeAppConfig(value: unknown): SafeAppConfig {
    const record = asRecord(value);
    const schema = record?.webserver_schema;
    return {
        webserver_domain: typeof record?.webserver_domain === "string" ? record.webserver_domain : null,
        webserver_port: typeof record?.webserver_port === "string" ? record.webserver_port : null,
        webserver_schema: schema === "http" || schema === "https" ? schema : null,
        webserver_auth_token: typeof record?.webserver_auth_token === "string" ? record.webserver_auth_token : null,
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
        relation_imported: asNumber(record.relation_imported),
        flashcard_imported: asNumber(record.flashcard_imported),
    };
}

export interface WebenValidationIssue {
    level: "error" | "warning";
    scope: "concept" | "relation" | "flashcard" | "result";
    index?: number;
    message: string;
}

export interface WebenValidationResult {
    sanitizedResult: MaterialWebenLlmResult;
    blockingErrors: WebenValidationIssue[];
    warnings: WebenValidationIssue[];
}

export function validateWebenResult(result: MaterialWebenLlmResult): WebenValidationResult {
    const validConceptTypes = new Set(CONCEPT_TYPES.map(item => item.key));
    const validPredicates = new Set(PREDICATES);
    const conceptNames = new Set(result.concepts.map(item => item.name.trim()).filter(Boolean));

    const blockingErrors: WebenValidationIssue[] = [];
    const warnings: WebenValidationIssue[] = [];

    const sanitizedConcepts = result.concepts.filter((concept, index) => {
        if (!concept.name.trim() || !concept.type.trim() || !concept.description.trim()) {
            blockingErrors.push({ level: "error", scope: "concept", index, message: `概念 #${index + 1} 缺少名称、类型或描述` });
            return false;
        }
        if (!validConceptTypes.has(concept.type)) {
            blockingErrors.push({ level: "error", scope: "concept", index, message: `概念“${concept.name}”的类型“${concept.type}”不在允许列表中` });
            return false;
        }
        return true;
    });

    const sanitizedConceptNames = new Set(sanitizedConcepts.map(item => item.name));

    const sanitizedRelations = result.relations.filter((relation, index) => {
        if (!relation.subject.trim() || !relation.predicate.trim() || !relation.object.trim()) {
            blockingErrors.push({ level: "error", scope: "relation", index, message: `关系 #${index + 1} 缺少主语、谓词或宾语` });
            return false;
        }
        if (!validPredicates.has(relation.predicate)) {
            blockingErrors.push({ level: "error", scope: "relation", index, message: `关系“${relation.subject} ${relation.predicate} ${relation.object}”的谓词不在允许列表中` });
            return false;
        }
        if (!sanitizedConceptNames.has(relation.subject) || !sanitizedConceptNames.has(relation.object)) {
            blockingErrors.push({ level: "error", scope: "relation", index, message: `关系“${relation.subject} ${relation.predicate} ${relation.object}”引用了不存在的概念` });
            return false;
        }
        return true;
    });

    const sanitizedFlashcards = result.flashcards.filter((card, index) => {
        if (!card.question.trim() || !card.answer.trim() || !card.concept.trim()) {
            blockingErrors.push({ level: "error", scope: "flashcard", index, message: `闪卡 #${index + 1} 缺少问题、答案或概念` });
            return false;
        }
        if (!sanitizedConceptNames.has(card.concept)) {
            blockingErrors.push({ level: "error", scope: "flashcard", index, message: `闪卡“${card.question}”引用了不存在的概念“${card.concept}”` });
            return false;
        }
        return true;
    });

    if (sanitizedConcepts.length === 0 && (sanitizedRelations.length > 0 || sanitizedFlashcards.length > 0 || conceptNames.size > 0)) {
        warnings.push({ level: "warning", scope: "result", message: "当前结果没有可导入的有效概念，相关关系和闪卡也会被阻止保存。" });
    }

    return {
        sanitizedResult: {
            concepts: sanitizedConcepts,
            relations: sanitizedRelations,
            flashcards: sanitizedFlashcards,
        },
        blockingErrors,
        warnings,
    };
}

export function normalizeWebenResult(data: unknown): MaterialWebenLlmResult {
    const record = asRecord(data);
    const concepts = Array.isArray(record?.concepts) ? record.concepts : [];
    const relations = Array.isArray(record?.relations) ? record.relations : [];
    const flashcards = Array.isArray(record?.flashcards) ? record.flashcards : [];
    return {
        concepts: concepts.map(item => {
            const concept = asRecord(item);
            return {
                name: asString(concept?.name),
                type: asString(concept?.type),
                description: asString(concept?.description),
                aliases: Array.isArray(concept?.aliases)
                    ? concept.aliases.filter((alias): alias is string => typeof alias === "string")
                    : undefined,
            };
        }).filter(item => item.name.trim().length > 0),
        relations: relations.map(item => {
            const relation = asRecord(item);
            return {
                subject: asString(relation?.subject),
                predicate: asString(relation?.predicate),
                object: asString(relation?.object),
                excerpt: typeof relation?.excerpt === "string" ? relation.excerpt : undefined,
            };
        }).filter(item => item.subject.trim().length > 0 && item.predicate.trim().length > 0 && item.object.trim().length > 0),
        flashcards: flashcards.map(item => {
            const card = asRecord(item);
            return {
                question: asString(card?.question),
                answer: asString(card?.answer),
                concept: asString(card?.concept),
            };
        }).filter(item => item.question.trim().length > 0 && item.answer.trim().length > 0 && item.concept.trim().length > 0),
    };
}
