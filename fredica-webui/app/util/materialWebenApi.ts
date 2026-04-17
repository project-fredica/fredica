import type { WebenConcept, WebenExtractionRun } from "~/util/weben";
import {
    normalizeImportResponse,
    normalizeLlmModelAvailability,
    normalizeLlmModels,
    normalizeSubtitleContent,
    normalizeSubtitleItems,
} from "./materialWebenGuards";
import { buildAuthHeaders, DEFAULT_SERVER_PORT } from "~/util/app_fetch";
import { json_parse } from "~/util/json";

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
        types: string[];
        description: string;
        aliases?: string[];
    }>;
}

export interface LlmModelMeta {
    app_model_id: string;
    label: string;
    notes?: string | null;
}

export interface LlmModelAvailability {
    available_count: number;
    has_any_available_model: boolean;
    selected_model_id: string | null;
    selected_model_available: boolean;
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

export async function fetchLlmModelAvailability(
    apiFetch: ApiFetchFn,
    selectedModelId?: string | null,
): Promise<LlmModelAvailability> {
    const query = selectedModelId?.trim()
        ? `?selected_model_id=${encodeURIComponent(selectedModelId)}`
        : "";
    const { data } = await apiFetch(
        `/api/v1/LlmModelAvailabilityRoute${query}`,
        { method: "GET" },
        { silent: true },
    );
    return normalizeLlmModelAvailability(data);
}

export interface WebenConceptTypeHint {
    key: string;
    label: string;
    color: string;
    source_type: string;
}

export async function fetchWebenConceptTypeHints(
    apiFetch: ApiFetchFn,
): Promise<WebenConceptTypeHint[]> {
    const { data } = await apiFetch(
        "/api/v1/WebenConceptTypeHintsRoute",
        { method: "GET" },
        { silent: true },
    );
    if (!Array.isArray(data)) return [];
    return data.map(item => ({
        key: typeof item?.key === "string" ? item.key : "",
        label: typeof item?.label === "string" ? item.label : "",
        color: typeof item?.color === "string" ? item.color : "bg-gray-100 text-gray-600 ring-gray-200",
        source_type: typeof item?.source_type === "string" ? item.source_type : "existing",
    })).filter(item => item.key.trim().length > 0);
}

export interface WebenBatchImportResponse {
    ok?: boolean;
    error?: string;
    source_id?: string;
    concept_created?: number;
    concept_total?: number;
}

export async function importWebenResult(
    apiFetch: ApiFetchFn,
    payload: {
        material_id: string;
        source_title?: string;
        concepts: MaterialWebenLlmResult["concepts"];
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
    params: { script_code: string },
): Promise<PromptSandboxResult> {
    const { resp, data } = await apiFetch(
        "/api/v1/PromptTemplateRunRoute",
        { method: "POST", body: JSON.stringify(params) },
        { silent: true, timeout: 15_000 },
    );
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const result = data as Record<string, unknown>;
    return {
        prompt_text: typeof result?.prompt_text === "string" ? result.prompt_text : null,
        error: typeof result?.error === "string" ? result.error : null,
        error_type: typeof result?.error_type === "string" ? result.error_type : null,
        logs: Array.isArray(result?.logs) ? result.logs as PromptSandboxLog[] : [],
    };
}

/**
 * 调用后端 PromptTemplatePreviewRoute（SSE），执行脚本并流式返回日志与最终 Prompt 文本。
 * 使用原始 fetch 直连 Ktor 服务，与 streamLlmRouterText 模式一致。
 *
 * [params.scriptCode] 应为完整脚本（含前端编辑器注入的头部 `var __materialId = "..."`）。
 */
export async function previewPromptScript(params: {
    scriptCode: string;
    connection: {
        domain?: string | null;
        port?: string | null;
        schema?: string | null;
        appAuthToken?: string | null;
        sessionToken?: string | null;
    };
    onLog?: (log: PromptSandboxLog) => void;
}): Promise<{ promptText: string | null; promptTexts: string[] | null; error: string | null; errorType: string | null; logs: PromptSandboxLog[] }> {
    const { connection } = params;
    const schema = connection.schema ?? "http";
    const domain = connection.domain ?? "localhost";
    const port = connection.port ?? DEFAULT_SERVER_PORT;
    const url = `${schema}://${domain}:${port}/api/v1/PromptTemplatePreviewRoute`;
    console.debug("[previewPromptScript] fetch url=", url, "scriptLength=", params.scriptCode.length);

    const resp = await fetch(url, {
        method: "POST",
        headers: {
            ...buildAuthHeaders(connection.sessionToken
                ? { session_token: connection.sessionToken, webserver_auth_token: connection.appAuthToken ?? null }
                : connection.appAuthToken ?? null),
            "Content-Type": "application/json",
        },
        body: JSON.stringify({ script_code: params.scriptCode }),
    });
    console.debug("[previewPromptScript] response status=", resp.status, "ok=", resp.ok);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);

    const reader = resp.body?.getReader();
    if (!reader) throw new Error("无法读取响应流");

    const decoder = new TextDecoder();
    let buffer = "";
    let promptText: string | null = null;
    let promptTexts: string[] | null = null;
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
                    const event = json_parse<Record<string, unknown>>(dataStr);
                    if (!event) continue;
                    if (event.type === "log") {
                        const log: PromptSandboxLog = { level: String(event.level ?? "log"), args: String(event.args ?? ""), ts: Number(event.ts ?? 0) };
                        logs.push(log);
                        params.onLog?.(log);
                    } else if (event.type === "result") {
                        promptText = String(event.prompt_text ?? "");
                        if (Array.isArray(event.prompt_texts)) {
                            promptTexts = (event.prompt_texts as unknown[]).map(String);
                        }
                    } else if (event.type === "error") {
                        error = String(event.error ?? "");
                        errorType = String(event.error_type ?? "unknown");
                    }
                } catch (error) {
                    console.debug("[previewPromptScript] ignore malformed SSE event", error, { dataStr });
                }
            }
            if (done) break;
        }
    } finally {
        reader.releaseLock();
    }

    return { promptText, promptTexts, error, errorType, logs };
}

/**
 * 调用后端 PromptScriptGenerateRoute（SSE），执行脚本 → 逐段调用 LLM → 流式返回。
 * 脚本返回 string 时为单段模式；返回 string[] 时为分段 MapReduce 模式。
 */
export async function streamPromptScriptGenerate(params: {
    scriptCode: string;
    appModelId: string;
    connection: {
        domain?: string | null;
        port?: string | null;
        schema?: string | null;
        appAuthToken?: string | null;
        sessionToken?: string | null;
    };
    onScriptLog?: (log: PromptSandboxLog) => void;
    onSegmentStart?: (index: number, total: number) => void;
    onChunk: (chunk: string) => void;
    signal?: AbortSignal;
    disableCache?: boolean;
}): Promise<{ fullText: string; error: string | null }> {
    const { connection } = params;
    const schema = connection.schema ?? "http";
    const domain = connection.domain ?? "localhost";
    const port = connection.port ?? DEFAULT_SERVER_PORT;
    const url = `${schema}://${domain}:${port}/api/v1/PromptScriptGenerateRoute`;
    console.debug("[streamPromptScriptGenerate] fetch url=", url);

    const resp = await fetch(url, {
        method: "POST",
        headers: {
            ...buildAuthHeaders(connection.sessionToken
                ? { session_token: connection.sessionToken, webserver_auth_token: connection.appAuthToken ?? null }
                : connection.appAuthToken ?? null),
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            script_code: params.scriptCode,
            app_model_id: params.appModelId,
            disable_cache: params.disableCache ?? false,
        }),
        signal: params.signal,
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);

    const reader = resp.body?.getReader();
    if (!reader) throw new Error("无法读取响应流");

    const decoder = new TextDecoder();
    let buffer = "";
    let fullText = "";
    let error: string | null = null;

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (value) buffer += decoder.decode(value, { stream: !done });
            const lines = buffer.split("\n");
            buffer = done ? "" : (lines.pop() ?? "");
            for (const line of lines) {
                const trimmed = line.replace(/\r$/, "").trim();
                // 跳过 event: 行（如 llm_source）
                if (trimmed.startsWith("event:")) continue;
                if (!trimmed.startsWith("data:")) continue;
                const dataStr = trimmed.slice(5).trim();
                if (dataStr === "[DONE]") continue;
                try {
                    const event = json_parse<Record<string, unknown>>(dataStr);
                    if (!event) continue;
                    // 脚本日志
                    if (event.type === "script_log") {
                        const log: PromptSandboxLog = {
                            level: String(event.level ?? "log"),
                            args: String(event.args ?? ""),
                            ts: Number(event.ts ?? 0),
                        };
                        params.onScriptLog?.(log);
                    }
                    // 脚本错误
                    else if (event.type === "script_error") {
                        error = String(event.error ?? "脚本执行失败");
                    }
                    // 分段开始
                    else if (event.type === "segment_start") {
                        params.onSegmentStart?.(Number(event.index ?? 0), Number(event.total ?? 1));
                    }
                    // LLM chunk（标准 choices[0].delta.content 格式）
                    else if (Array.isArray(event.choices)) {
                        const delta = (event.choices as Array<{ delta?: { content?: string } }>)[0]?.delta;
                        const content = delta?.content ?? "";
                        if (content) {
                            fullText += content;
                            params.onChunk(content);
                        }
                    }
                    // LLM 错误（event: llm_error 的 data 行）
                    else if (event.error_type) {
                        error = String(event.message ?? event.error_type);
                    }
                } catch (parseErr) {
                    console.debug("[streamPromptScriptGenerate] ignore malformed SSE event", parseErr, { dataStr });
                }
            }
            if (done) break;
        }
    } finally {
        reader.releaseLock();
    }

    return { fullText, error };
}

// ── 概念提取运行 ──────────────────────────────────────────────────────────────

/** 按素材 ID 拉取已有概念列表（全量，用于 diff 计算）。 */
export async function fetchConceptsByMaterial(
    apiFetch: ApiFetchFn,
    materialId: string,
): Promise<WebenConcept[]> {
    const params = new URLSearchParams({ material_id: materialId, limit: "200", offset: "0" });
    const { data } = await apiFetch(
        `/api/v1/WebenConceptListRoute?${params}`,
        { method: "GET" },
        { silent: true },
    );
    const page = data as { items?: WebenConcept[] } | null;
    return page?.items ?? [];
}

/** 按来源 ID 拉取已有概念列表（全量，用于 diff 计算）。 */
export async function fetchConceptsBySource(
    apiFetch: ApiFetchFn,
    sourceId: string,
): Promise<WebenConcept[]> {
    const params = new URLSearchParams({ source_id: sourceId, limit: "200", offset: "0" });
    const { data } = await apiFetch(
        `/api/v1/WebenConceptListRoute?${params}`,
        { method: "GET" },
        { silent: true },
    );
    const page = data as { items?: WebenConcept[] } | null;
    return page?.items ?? [];
}

export interface ExtractionRunSavePayload {
    material_id?: string;
    source_id?: string;
    source_url?: string;
    source_title?: string;
    prompt_script?: string;
    prompt_text?: string;
    llm_model_id?: string;
    llm_input_json?: string;
    llm_output_raw?: string;
    concepts: MaterialWebenLlmResult["concepts"];
}

export interface ExtractionRunSaveResponse {
    ok?: boolean;
    error?: string;
    source_id?: string;
    run_id?: string;
    concept_created?: number;
    concept_total?: number;
}

/** 保存概念提取运行（含提取上下文 + 概念批量写入），取代旧的 importWebenResult。 */
export async function saveExtractionRun(
    apiFetch: ApiFetchFn,
    payload: ExtractionRunSavePayload,
): Promise<{ resp: Response; data: ExtractionRunSaveResponse | null }> {
    const { resp, data } = await apiFetch(
        "/api/v1/WebenExtractionRunSaveRoute",
        { method: "POST", body: JSON.stringify(payload) },
        { silent: true },
    );
    if (!data || typeof data !== "object") return { resp, data: null };
    return { resp, data: data as ExtractionRunSaveResponse };
}

/** 拉取来源的提取历史列表（分页，不含大字段）。 */
export async function fetchExtractionRunList(
    apiFetch: ApiFetchFn,
    sourceId: string,
    opts: { limit?: number; offset?: number } = {},
): Promise<{ items: WebenExtractionRun[]; total: number }> {
    const params = new URLSearchParams({
        source_id: sourceId,
        limit: String(opts.limit ?? 20),
        offset: String(opts.offset ?? 0),
    });
    const { data } = await apiFetch(
        `/api/v1/WebenExtractionRunListRoute?${params}`,
        { method: "GET" },
        { silent: true },
    );
    const page = data as { items?: WebenExtractionRun[]; total?: number } | null;
    return { items: page?.items ?? [], total: page?.total ?? 0 };
}

/** 拉取单次提取运行的完整详情（含 prompt_text / llm_output_raw）。 */
export async function fetchExtractionRunDetail(
    apiFetch: ApiFetchFn,
    runId: string,
): Promise<WebenExtractionRun | null> {
    const { resp, data } = await apiFetch(
        `/api/v1/WebenExtractionRunGetRoute?id=${encodeURIComponent(runId)}`,
        { method: "GET" },
        { silent: true },
    );
    if (!resp.ok) return null;
    if (!data || typeof data !== "object") return null;
    return data as WebenExtractionRun;
}
