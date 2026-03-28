import JSON5 from "json5";
import { callBridge } from "./bridge";
import { buildAuthHeaders, DEFAULT_SERVER_PORT } from "./app_fetch";
import { json_parse, type JsonValue } from "./json";
import { print_error } from "./error_handler";

/** direct 模式：前端直接持有凭证，SSE 流式 */
export interface LlmChatDirectParams {
    mode: "direct";
    base_url: string;
    api_key: string;
    model: string;
    messages_json: string;
}

/** router 模式：后端 Ktor 代理转发，SSE 流式，走系统代理 */
export interface LlmChatRouterParams {
    mode: "router";
    app_model_id: string;
    messages_json: string;
    disable_cache?: boolean;
    overwrite_on_disable?: boolean;
    extra_fields_json?: string;
    connection: LlmChatConnectionConfig;
}

/** bridge 模式：JsBridge 调用 Kotlin，非流式，仅 WebView 可用 */
export interface LlmChatBridgeParams {
    mode: "bridge";
    app_model_id: string;
    messages_json: string;
    disable_cache?: boolean;
    overwrite_on_disable?: boolean;
    extra_fields_json?: string;
}

export type LlmChatParams =
    | LlmChatDirectParams
    | LlmChatRouterParams
    | LlmChatBridgeParams;

/** router 模式的连接配置，对应 appConfig 中的 webserver_* 字段 */
export interface LlmChatConnectionConfig {
    domain?: string | null;
    port?: string | null;
    schema?: string | null;
    appAuthToken?: string | null;
}

/** LLM 响应元数据 */
export interface LlmResponseMeta {
    source: 'CACHE' | 'LLM_FRESH' | 'REVISION';
    keyHash: string;
}

/** LLM 提供商错误 */
export class LlmProviderError extends Error {
    constructor(
        public errorType: string,
        message: string,
    ) {
        super(message);
        this.name = 'LlmProviderError';
    }
}

/** 构造单条 user 消息的 messages_json（兼容旧调用方） */
export function singleUserMessage(content: string): string {
    return JSON.stringify([{ role: "user", content }]);
}

async function* sseChunks(
    resp: Response,
    onMeta?: (meta: LlmResponseMeta) => void
): AsyncGenerator<string> {
    const reader = resp.body?.getReader();
    if (!reader) throw new Error("无法读取响应流");
    const decoder = new TextDecoder();
    let buf = "";
    let currentEvent = "";
    try {
        while (true) {
            const { done, value } = await reader.read();
            buf += decoder.decode(value, { stream: !done });
            const lines = buf.split("\n");
            buf = done ? "" : (lines.pop() ?? "");
            for (const line of lines) {
                const trimmed = line.replace(/\r$/, "").trim();

                if (trimmed.startsWith("event:")) {
                    currentEvent = trimmed.slice(6).trim();
                    continue;
                }

                if (trimmed.startsWith("data:")) {
                    const data = trimmed.slice(5).trim();

                    if (data === "[DONE]") return;

                    if (currentEvent === "llm_source") {
                        const meta = json_parse<LlmResponseMeta>(data);
                        if (meta && onMeta) onMeta(meta);
                        currentEvent = "";
                        continue;
                    }

                    if (currentEvent === "llm_error") {
                        const errData = json_parse<{ error_type?: string; message?: string }>(data);
                        throw new LlmProviderError(
                            errData?.error_type ?? "UNKNOWN",
                            errData?.message ?? "LLM provider error"
                        );
                    }

                    const dataObj = json_parse(data);
                    if (dataObj === null || typeof dataObj !== "object" || Array.isArray(dataObj)) {
                        print_error({ reason: "SSE data 格式错误：期望对象", err: new Error(`data=${data}`) });
                        continue;
                    }
                    const choices = (dataObj as Record<string, unknown>).choices;
                    if (!Array.isArray(choices) || choices.length === 0) continue;
                    const firstChoice = choices[0];
                    if (typeof firstChoice !== "object" || firstChoice === null) {
                        print_error({ reason: "SSE choices[0] 格式错误", err: new Error(`choices[0]=${JSON.stringify(firstChoice)}`) });
                        continue;
                    }
                    const delta = (firstChoice as Record<string, unknown>).delta;
                    if (typeof delta !== "object" || delta === null) continue;
                    const content = (delta as Record<string, unknown>).content;
                    if (typeof content === "string" && content) yield content;
                    currentEvent = "";
                }
            }
            if (done) break;
        }
    } finally {
        reader.releaseLock();
    }
}

export async function* llmChat(
    params: LlmChatParams,
    onMeta?: (meta: LlmResponseMeta) => void
): AsyncGenerator<string> {
    if (params.mode === "direct") {
        const baseUrl = params.base_url.replace(/\/+$/, "");
        const messagesArray = json_parse(params.messages_json);
        const resp = await fetch(`${baseUrl}/chat/completions`, {
            method: "POST",
            headers: {
                "Accept": "text/event-stream",
                "Content-Type": "application/json",
                "Authorization": `Bearer ${params.api_key}`,
            },
            body: JSON.stringify({
                model: params.model,
                messages: messagesArray,
                stream: true,
            }),
        });
        if (!resp.ok) {
            throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);
        }
        yield* sseChunks(resp, onMeta);
    } else if (params.mode === "router") {
        const { connection } = params;
        const s = connection?.schema ?? "http";
        const d = connection?.domain ?? "localhost";
        const p = connection?.port ?? DEFAULT_SERVER_PORT;
        const body: Record<string, unknown> = {
            app_model_id: params.app_model_id,
            messages_json: params.messages_json,
        };
        if (params.disable_cache !== undefined) body.disable_cache = params.disable_cache;
        if (params.overwrite_on_disable !== undefined) body.overwrite_on_disable = params.overwrite_on_disable;
        if (params.extra_fields_json) body.extra_fields_json = params.extra_fields_json;

        const resp = await fetch(`${s}://${d}:${p}/api/v1/LlmProxyChatRoute`, {
            method: "POST",
            headers: {
                ...buildAuthHeaders(connection?.appAuthToken),
                "Content-Type": "application/json",
            },
            body: JSON.stringify(body),
        });
        if (!resp.ok) {
            throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);
        }
        yield* sseChunks(resp, onMeta);
    } else {
        const body: Record<string, unknown> = {
            app_model_id: params.app_model_id,
            messages_json: params.messages_json,
        };
        if (params.disable_cache !== undefined) body.disable_cache = params.disable_cache;
        if (params.overwrite_on_disable !== undefined) body.overwrite_on_disable = params.overwrite_on_disable;
        if (params.extra_fields_json) body.extra_fields_json = params.extra_fields_json;

        const raw = await callBridge("llm_proxy_chat", JSON.stringify(body));
        const parsed = json_parse(raw) as unknown as {
            error?: string;
            error_type?: string;
            content?: string;
            source?: string;
            key_hash?: string;
        };
        if (parsed !== null && typeof parsed === "object" && !Array.isArray(parsed)) {
            if (parsed.error) {
                if (parsed.error_type) {
                    throw new LlmProviderError(parsed.error_type, parsed.error);
                }
                throw new Error(String(parsed.error));
            }
            if (parsed.source && parsed.key_hash && onMeta) {
                onMeta({
                    source: parsed.source as LlmResponseMeta['source'],
                    keyHash: parsed.key_hash,
                });
            }
        }
        const content = parsed?.content ?? "";
        if (content) yield content;
    }
}

/**
 * 通过后端代理调用 LLM（router 模式），不暴露 api_key/base_url 到前端网络层。
 */
export async function* llmProxyChat(params: {
    appModelId: string;
    messagesJson: string;
    disableCache?: boolean;
    overwriteOnDisable?: boolean;
    extraFieldsJson?: string;
    connection: LlmChatConnectionConfig;
    onMeta?: (meta: LlmResponseMeta) => void;
}): AsyncGenerator<string> {
    yield* llmChat({
        mode: "router",
        app_model_id: params.appModelId,
        messages_json: params.messagesJson,
        disable_cache: params.disableCache,
        overwrite_on_disable: params.overwriteOnDisable,
        extra_fields_json: params.extraFieldsJson,
        connection: params.connection,
    }, params.onMeta);
}

/**
 * 从 LLM 输出的自由文本中提取 JSON 值。
 *
 * ## 支持的格式
 *
 * ### 1. 标准 JSON（对象 / 数组 / 原始值）
 * ```
 * parseJsonFromText('{"a":1}')          // → { a: 1 }
 * parseJsonFromText('[1, 2, 3]')         // → [1, 2, 3]
 * parseJsonFromText('"hello"')           // → "hello"
 * ```
 *
 * ### 2. Markdown 代码块（````json` 或 ` ``` `）
 * 优先提取第一个代码块内容，再对其内容递归解析。
 * ```
 * parseJsonFromText('```json\n{"x":1}\n```')   // → { x: 1 }
 * ```
 *
 * ### 3. 多个代码块
 * 当文本中存在多个代码块时，依次尝试每个块，返回第一个成功解析的结果。
 * ```
 * parseJsonFromText('```\n{bad}\n```\n```json\n{"ok":1}\n```')  // → { ok: 1 }
 * ```
 *
 * ### 4. JSON5（注释、尾逗号、单引号键名等）
 * 若标准 `JSON.parse` 失败，自动尝试 JSON5 宽松解析：
 * - 去除单行注释（`// ...`）和块注释（`/* ... *\/`）
 * - 去除尾逗号（对象末尾 `,}` 和数组末尾 `,]`）
 * - 将单引号键名转为双引号
 * ```
 * parseJsonFromText("{ // comment\n  a: 1, }")   // → { a: 1 }
 * ```
 *
 * ### 5. JSONL（JSON Lines）→ 数组
 * 若整体解析失败，尝试按行解析：每行是一个独立 JSON 值，
 * 收集所有成功解析的行并返回数组。至少需要 2 行才触发此逻辑。
 * ```
 * parseJsonFromText('{"a":1}\n{"b":2}')   // → [{ a: 1 }, { b: 2 }]
 * ```
 *
 * ### 6. 裸 JSON（前后有多余文字）
 * 若代码块和整体解析均失败，在文本中扫描第一个 `{` / `[` 到对应闭合括号，
 * 提取该子串再解析。
 * ```
 * parseJsonFromText('Here is the result: {"key":"val"} done')  // → { key: "val" }
 * parseJsonFromText('Result: [1,2,3] end')                     // → [1, 2, 3]
 * ```
 *
 * ## 返回值
 * - 成功：解析出的 `JsonValue`（对象、数组、字符串、数字、布尔、null）
 * - 失败：`null`
 *
 * @param raw LLM 输出的原始文本
 */
export function parseJsonFromText(raw: string): JsonValue {
    if (typeof raw !== "string") return null;
    const trimmed = raw.trim();
    if (!trimmed) return null;

    // ── 1. 提取所有 Markdown 代码块 ──────────────────────────────────────────
    const fencedBlocks: string[] = [];
    const fenceRe = /```(?:[a-z0-9]*)\s*([\s\S]*?)```/gi;
    let m: RegExpExecArray | null;
    while ((m = fenceRe.exec(trimmed)) !== null) {
        const inner = m[1].trim();
        if (inner) fencedBlocks.push(inner);
    }

    // 依次尝试每个代码块
    for (const block of fencedBlocks) {
        const result = _tryParseCandidate(block);
        if (result !== null) return result;
    }

    // ── 2. 整体尝试（无代码块时，或代码块全部失败时）────────────────────────
    const direct = _tryParseCandidate(trimmed);
    if (direct !== null) return direct;

    // ── 3. JSONL：按行解析，收集成功行 ──────────────────────────────────────
    const lines = trimmed.split("\n").map((l) => l.trim()).filter(Boolean);
    if (lines.length >= 2) {
        const parsed: JsonValue[] = [];
        for (const line of lines) {
            const v = _tryParseCandidate(line);
            if (v !== null) parsed.push(v);
        }
        if (parsed.length >= 2) return parsed;
    }

    // ── 4. 裸 JSON：扫描第一个 { 或 [ ────────────────────────────────────────
    const extracted = _extractFirstJsonSubstring(trimmed);
    if (extracted !== null) {
        const result = _tryParseCandidate(extracted);
        if (result !== null) return result;
    }

    return null;
}

/**
 * 尝试用标准 JSON.parse（含 U+2028/U+2029 净化），失败则降级到 JSON5 宽松解析。
 * 使用 json_parse 而非裸 JSON.parse，统一处理行分隔符转义。
 */
function _tryParseCandidate(candidate: string): JsonValue {
    const s = candidate.trim();
    if (!s) return null;
    try {
        return json_parse(s);
    } catch {
        // 降级：JSON5 宽松解析（JSON5 库自身也能处理 U+2028/U+2029）
        return _tryParseJson5(s);
    }
}

/** 使用 json5 库解析宽松 JSON5 格式（注释、尾逗号、裸键名、单引号等） */
function _tryParseJson5(s: string): JsonValue {
    try {
        return JSON5.parse(s) as JsonValue;
    } catch {
        return null;
    }
}

/**
 * 在文本中找到第一个 `{` 或 `[`，然后用括号计数法找到对应的闭合括号，
 * 返回该子串。用于处理 LLM 在 JSON 前后附加了说明文字的情况。
 */
function _extractFirstJsonSubstring(text: string): string | null {
    const firstBrace = text.indexOf("{");
    const firstBracket = text.indexOf("[");

    let start: number;
    let open: string;
    let close: string;

    if (firstBrace < 0 && firstBracket < 0) return null;
    if (firstBrace < 0) {
        start = firstBracket;
        open = "[";
        close = "]";
    } else if (firstBracket < 0) {
        start = firstBrace;
        open = "{";
        close = "}";
    } else if (firstBrace < firstBracket) {
        start = firstBrace;
        open = "{";
        close = "}";
    } else {
        start = firstBracket;
        open = "[";
        close = "]";
    }

    let depth = 0;
    let inString = false;
    let escape = false;
    for (let i = start; i < text.length; i++) {
        const ch = text[i];
        if (escape) { escape = false; continue; }
        if (ch === "\\" && inString) { escape = true; continue; }
        if (ch === '"') { inString = !inString; continue; }
        if (inString) continue;
        if (ch === open) depth++;
        else if (ch === close) {
            depth--;
            if (depth === 0) return text.slice(start, i + 1);
        }
    }
    return null;
}
