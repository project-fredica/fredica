import { buildAuthHeaders, DEFAULT_SERVER_PORT } from "~/util/app_fetch";
import { json_parse } from "~/util/json";

/**
 * 流式读取 LlmProxyChatRoute（OpenAI 兼容 SSE）。
 *
 * 每条 data 行的格式：{ choices: [{ delta: { content: string } }] }
 * 遇到裸 `data: [DONE]` 时立即返回，无需等待 ReadableStream 的 done 标志。
 * `event: llm_error` 行携带 provider 错误，作为异常抛出。
 * 格式错误的 JSON chunk 会静默跳过（debug 日志），避免单帧乱码中断整个流。
 */
export async function streamLlmRouterText(params: {
    appModelId: string;
    messagesJson: string;
    connection: {
        domain?: string | null;
        port?: string | null;
        schema?: string | null;
        appAuthToken?: string | null;
    };
    onChunk: (chunk: string) => void;
    signal?: AbortSignal;
    disableCache?: boolean;
}): Promise<string> {
    const schema = params.connection.schema ?? "http";
    const domain = params.connection.domain ?? "localhost";
    const port = params.connection.port ?? DEFAULT_SERVER_PORT;
    const resp = await fetch(`${schema}://${domain}:${port}/api/v1/LlmProxyChatRoute`, {
        method: "POST",
        headers: {
            ...buildAuthHeaders(params.connection.appAuthToken),
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            app_model_id: params.appModelId,
            messages_json: params.messagesJson,
            disable_cache: params.disableCache ?? false,
        }),
        signal: params.signal,
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);

    const reader = resp.body?.getReader();
    if (!reader) throw new Error("无法读取响应流");

    const decoder = new TextDecoder();
    let fullText = "";
    let buffer = "";
    let currentEvent = "";
    try {
        while (true) {
            if (params.signal?.aborted) break;
            const { done, value } = await reader.read();
            if (value) buffer += decoder.decode(value, { stream: !done });
            const lines = buffer.split("\n");
            buffer = done ? "" : (lines.pop() ?? "");
            for (const line of lines) {
                const trimmed = line.replace(/\r$/, "").trim();
                if (trimmed.startsWith("event:")) {
                    currentEvent = trimmed.slice(6).trim();
                    continue;
                }
                if (!trimmed.startsWith("data:")) continue;
                const data = trimmed.slice(5).trim();
                if (data === "[DONE]") return fullText;
                if (currentEvent === "llm_error") {
                    const errData = json_parse<{ error_type?: string; message?: string }>(data);
                    throw new Error(errData?.message ?? "LLM provider error");
                }
                currentEvent = "";
                try {
                    const chunk = (json_parse<any>(data))?.choices?.[0]?.delta?.content;
                    if (!chunk) continue;
                    fullText += chunk;
                    params.onChunk(chunk);
                } catch (error) {
                    console.debug("[streamLlmRouterText] ignore malformed LLM SSE chunk", error, { data });
                }
            }
            if (done) break;
        }
        return fullText;
    } finally {
        reader.releaseLock();
    }
}
