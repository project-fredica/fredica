import {
    normalizeImportResponse,
    normalizeLlmModels,
    normalizeSubtitleContent,
    normalizeSubtitleItems,
} from "./materialWebenGuards";
import type { VariableMeta } from "./prompt-builder/types";
import { buildAuthHeaders, DEFAULT_SERVER_PORT } from "~/util/app_fetch";

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

// ── GraalJS 沙箱执行 ──────────────────────────────────────────────────────────

export interface PromptSandboxLog {
    level: string;
    args: string;
    ts: number;
}

export interface PromptSandboxResult {
    prompt_text: string | null;
    error: string | null;
    error_type: string | null;
    logs: PromptSandboxLog[];
}

/**
 * 调用后端 PromptTemplateRunRoute，在 GraalJS 沙箱执行脚本并返回结果 JSON。
 * 适合非流式场景（一次性获取执行结果）。
 */
export async function runPromptScript(
    apiFetch: ApiFetchFn,
    params: { script_code: string; material_id: string },
): Promise<PromptSandboxResult> {
    const { resp, data } = await apiFetch(
        "/api/v1/PromptTemplateRunRoute",
        { method: "POST", body: JSON.stringify(params) },
        { silent: true, timeout: 15_000 },
    );
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return data as PromptSandboxResult;
}

/**
 * 调用后端 PromptTemplatePreviewRoute（SSE），执行脚本并流式返回日志与最终 Prompt 文本。
 * 使用原始 fetch 直连 Ktor 服务，与 streamLlmRouterText 模式一致。
 */
export async function previewPromptScript(params: {
    scriptCode: string;
    materialId: string;
    connection: {
        domain?: string | null;
        port?: string | null;
        schema?: string | null;
        appAuthToken?: string | null;
    };
    onLog?: (log: PromptSandboxLog) => void;
}): Promise<{ promptText: string | null; error: string | null; errorType: string | null; logs: PromptSandboxLog[] }> {
    const { connection } = params;
    const schema = connection.schema ?? "http";
    const domain = connection.domain ?? "localhost";
    const port = connection.port ?? DEFAULT_SERVER_PORT;

    const resp = await fetch(`${schema}://${domain}:${port}/api/v1/PromptTemplatePreviewRoute`, {
        method: "POST",
        headers: {
            ...buildAuthHeaders(connection.appAuthToken),
            "Content-Type": "application/json",
        },
        body: JSON.stringify({ script_code: params.scriptCode, material_id: params.materialId }),
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);

    const reader = resp.body?.getReader();
    if (!reader) throw new Error("无法读取响应流");

    const decoder = new TextDecoder();
    let buffer = "";
    let promptText: string | null = null;
    let error: string | null = null;
    let errorType: string | null = null;
    const logs: PromptSandboxLog[] = [];

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (value) buffer += decoder.decode(value, { stream: !done });
            const lines = buffer.split("\n");
            buffer = done ? "" : (lines.pop() ?? "");
            for (const line of lines) {
                const trimmed = line.replace(/\r$/, "").trim();
                if (!trimmed.startsWith("data:")) continue;
                const dataStr = trimmed.slice(5).trim();
                try {
                    const event = JSON.parse(dataStr) as Record<string, unknown>;
                    if (event.type === "log") {
                        const log: PromptSandboxLog = { level: String(event.level ?? "log"), args: String(event.args ?? ""), ts: Number(event.ts ?? 0) };
                        logs.push(log);
                        params.onLog?.(log);
                    } else if (event.type === "result") {
                        promptText = String(event.prompt_text ?? "");
                    } else if (event.type === "error") {
                        error = String(event.error ?? "");
                        errorType = String(event.error_type ?? "unknown");
                    }
                } catch {
                    // ignore malformed events
                }
            }
            if (done) break;
        }
    } finally {
        reader.releaseLock();
    }

    return { promptText, error, errorType, logs };
}
