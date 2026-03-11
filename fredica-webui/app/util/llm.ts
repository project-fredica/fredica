import { callBridge } from "./bridge";
import { buildAuthHeaders, DEFAULT_SERVER_PORT } from "./app_fetch";

/** direct 模式：前端直接持有凭证，SSE 流式 */
export interface LlmChatDirectParams {
    mode: "direct";
    base_url: string;
    api_key: string;
    model: string;
    message: string;
}

/** router 模式：后端 Ktor 代理转发，SSE 流式，走系统代理 */
export interface LlmChatRouterParams {
    mode: "router";
    app_model_id: string;
    message: string;
    connection: LlmChatConnectionConfig;
}

/** bridge 模式：JsBridge 调用 Kotlin，非流式，仅 WebView 可用 */
export interface LlmChatBridgeParams {
    mode: "bridge";
    app_model_id: string;
    message: string;
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

async function* sseChunks(resp: Response): AsyncGenerator<string> {
    const reader = resp.body?.getReader();
    if (!reader) throw new Error("无法读取响应流");
    const decoder = new TextDecoder();
    let buf = "";
    try {
        while (true) {
            const { done, value } = await reader.read();
            buf += decoder.decode(value, { stream: !done });
            const lines = buf.split("\n");
            buf = done ? "" : (lines.pop() ?? "");
            for (const line of lines) {
                const trimmed = line.replace(/\r$/, "").trim();
                if (!trimmed.startsWith("data:")) continue;
                const data = trimmed.slice(5).trim();
                if (data === "[DONE]") return;
                try {
                    const delta = JSON.parse(data)?.choices?.[0]?.delta?.content;
                    if (delta) yield delta;
                } catch { /* ignore malformed chunks */ }
            }
            if (done) break;
        }
    } finally {
        reader.releaseLock();
    }
}

export async function* llmChat(params: LlmChatParams): AsyncGenerator<string> {
    if (params.mode === "direct") {
        const baseUrl = params.base_url.replace(/\/+$/, "");
        const resp = await fetch(`${baseUrl}/chat/completions`, {
            method: "POST",
            headers: {
                "Accept": "text/event-stream",
                "Content-Type": "application/json",
                "Authorization": `Bearer ${params.api_key}`,
            },
            body: JSON.stringify({
                model: params.model,
                messages: [{ role: "user", content: params.message }],
                stream: true,
            }),
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);
        yield* sseChunks(resp);

    } else if (params.mode === "router") {
        const { connection } = params;
        const s = connection?.schema ?? "http";
        const d = connection?.domain ?? "localhost";
        const p = connection?.port ?? DEFAULT_SERVER_PORT;
        const resp = await fetch(`${s}://${d}:${p}/api/v1/LlmProxyChatRoute`, {
            method: "POST",
            headers: {
                ...buildAuthHeaders(connection?.appAuthToken),
                "Content-Type": "application/json",
            },
            body: JSON.stringify({
                app_model_id: params.app_model_id,
                message: params.message,
            }),
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);
        yield* sseChunks(resp);

    } else {
        const raw = await callBridge(
            "llm_proxy_chat",
            JSON.stringify({ app_model_id: params.app_model_id, message: params.message }),
        );
        const parsed = JSON.parse(raw);
        if (parsed?.error) throw new Error(parsed.error);
        const content = parsed?.content ?? "";
        if (content) yield content;
    }
}
