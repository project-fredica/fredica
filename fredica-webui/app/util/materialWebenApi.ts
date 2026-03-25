import {
    normalizeImportResponse,
    normalizeLlmModels,
    normalizeSubtitleContent,
    normalizeSubtitleItems,
} from "./materialWebenGuards";
import type { VariableMeta } from "./prompt-builder/types";

export interface ApiFetchFn {
    (
        path: string,
        init?: RequestInit,
        options?: { parseJson?: boolean; timeout?: number; silent?: boolean; signal?: AbortSignal },
    ): Promise<{ resp: Response; data: unknown }>;
}

export interface MaterialSubtitleItem {
    lan: string;
    lan_doc: string;
    source: string;
    queried_at: number;
    subtitle_url: string;
    type: number;
}

export interface MaterialWebenLlmResult {
    concepts: Array<{
        name: string;
        type: string;
        description: string;
        aliases?: string[];
    }>;
    relations: Array<{
        subject: string;
        predicate: string;
        object: string;
        excerpt?: string;
    }>;
    flashcards: Array<{
        question: string;
        answer: string;
        concept: string;
    }>;
}

export interface LlmModelMeta {
    app_model_id: string;
    label: string;
    notes?: string | null;
}

export async function fetchMaterialSubtitles(
    apiFetch: ApiFetchFn,
    materialId: string,
): Promise<MaterialSubtitleItem[]> {
    const { data } = await apiFetch(
        `/api/v1/MaterialSubtitleListRoute?material_id=${encodeURIComponent(materialId)}`,
        { method: "GET" },
        { silent: true },
    );
    return normalizeSubtitleItems(data);
}

export interface MaterialSubtitleContentResponse {
    text: string;
    word_count: number;
    segment_count: number;
    source: string;
    subtitle_url: string;
}

export async function fetchSubtitleContent(
    apiFetch: ApiFetchFn,
    subtitle: Pick<MaterialSubtitleItem, "subtitle_url" | "source">,
): Promise<MaterialSubtitleContentResponse> {
    const { data } = await apiFetch(
        "/api/v1/MaterialSubtitleContentRoute",
        {
            method: "POST",
            body: JSON.stringify({
                subtitle_url: subtitle.subtitle_url,
                source: subtitle.source,
                is_update: false,
            }),
        },
        { silent: true, timeout: 5 * 60 * 1000 },
    );
    return normalizeSubtitleContent(data, {
        source: subtitle.source,
        subtitle_url: subtitle.subtitle_url,
    });
}

export async function fetchLlmModels(
    apiFetch: ApiFetchFn,
): Promise<LlmModelMeta[]> {
    const { data } = await apiFetch(
        "/api/v1/LlmModelListRoute",
        { method: "GET" },
        { silent: true },
    );
    return normalizeLlmModels(data);
}

export interface WebenBatchImportResponse {
    ok?: boolean;
    error?: string;
    source_id?: string;
    concept_created?: number;
    concept_total?: number;
    relation_imported?: number;
    flashcard_imported?: number;
}

export async function importWebenResult(
    apiFetch: ApiFetchFn,
    payload: {
        material_id: string;
        source_title?: string;
        concepts: MaterialWebenLlmResult["concepts"];
        relations: MaterialWebenLlmResult["relations"];
        flashcards: MaterialWebenLlmResult["flashcards"];
    },
): Promise<{ resp: Response; data: WebenBatchImportResponse | null }> {
    const { resp, data } = await apiFetch(
        "/api/v1/WebenConceptBatchImportRoute",
        {
            method: "POST",
            body: JSON.stringify(payload),
        },
        { silent: true },
    );
    return { resp, data: normalizeImportResponse(data) };
}

export function getWebenPromptVariables(): VariableMeta[] {
    return [
        {
            key: "material.title",
            label: "素材标题",
            description: "当前素材标题",
            kind: "text",
            required: true,
        },
        {
            key: "material.duration",
            label: "素材时长",
            description: "当前素材时长（分钟）",
            kind: "text",
        },
        {
            key: "subtitle",
            label: "字幕全文",
            description: "当前素材可用字幕拼接后的文本",
            kind: "text",
            required: true,
        },
        {
            key: "weben_schema_hint",
            label: "Weben Schema 提示",
            description: "约束 LLM 输出概念、关系、闪卡的 JSON 结构",
            kind: "text",
            required: true,
        },
    ];
}
