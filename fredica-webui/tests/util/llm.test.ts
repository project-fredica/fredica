import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { llmChat, singleUserMessage, LlmProviderError } from "~/util/llm";

describe("llm.ts", () => {
    beforeEach(() => {
        vi.stubGlobal("fetch", vi.fn());
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("L1: normal SSE stream yields chunks and meta", async () => {
        const mockFetch = vi.fn().mockResolvedValue({
            ok: true,
            body: new ReadableStream({
                start(controller) {
                    controller.enqueue(new TextEncoder().encode('data: {"choices":[{"delta":{"content":"Hello"}}]}\n\n'));
                    controller.enqueue(new TextEncoder().encode('data: {"choices":[{"delta":{"content":" world"}}]}\n\n'));
                    controller.enqueue(new TextEncoder().encode('event: llm_source\ndata: {"source":"LLM_FRESH","keyHash":"abc123"}\n\n'));
                    controller.enqueue(new TextEncoder().encode('data: [DONE]\n\n'));
                    controller.close();
                },
            }),
        });
        vi.stubGlobal("fetch", mockFetch);

        let meta: any = null;
        const chunks: string[] = [];
        for await (const chunk of llmChat(
            {
                mode: "router",
                app_model_id: "test",
                messages_json: singleUserMessage("hi"),
                connection: { appAuthToken: "token" },
            },
            (m) => { meta = m; }
        )) {
            chunks.push(chunk);
        }

        expect(chunks).toEqual(["Hello", " world"]);
        expect(meta).toEqual({ source: "LLM_FRESH", keyHash: "abc123" });
    });

    it("L2: SSE with llm_source event sets meta.source=CACHE", async () => {
        const mockFetch = vi.fn().mockResolvedValue({
            ok: true,
            body: new ReadableStream({
                start(controller) {
                    controller.enqueue(new TextEncoder().encode('data: {"choices":[{"delta":{"content":"cached"}}]}\n\n'));
                    controller.enqueue(new TextEncoder().encode('event: llm_source\ndata: {"source":"CACHE","keyHash":"xyz789"}\n\n'));
                    controller.enqueue(new TextEncoder().encode('data: [DONE]\n\n'));
                    controller.close();
                },
            }),
        });
        vi.stubGlobal("fetch", mockFetch);

        let meta: any = null;
        const chunks: string[] = [];
        for await (const chunk of llmChat(
            { mode: "router", app_model_id: "test", messages_json: singleUserMessage("hi"), connection: {} },
            (m) => { meta = m; }
        )) {
            chunks.push(chunk);
        }

        expect(meta?.source).toBe("CACHE");
        expect(meta?.keyHash).toBe("xyz789");
    });

    it("L3: SSE with llm_error event throws LlmProviderError", async () => {
        const mockFetch = vi.fn().mockResolvedValue({
            ok: true,
            body: new ReadableStream({
                start(controller) {
                    controller.enqueue(new TextEncoder().encode('event: llm_error\ndata: {"error_type":"RATE_LIMIT","message":"Too many requests"}\n\n'));
                    controller.close();
                },
            }),
        });
        vi.stubGlobal("fetch", mockFetch);

        await expect(async () => {
            for await (const chunk of llmChat(
                { mode: "router", app_model_id: "test", messages_json: singleUserMessage("hi"), connection: {} }
            )) {
                // should throw before yielding
            }
        }).rejects.toThrow(LlmProviderError);
    });

    it("L4: singleUserMessage wraps content in messages array", () => {
        const result = singleUserMessage("hello");
        expect(result).toBe(JSON.stringify([{ role: "user", content: "hello" }]));
    });

    it("L5: disable_cache parameter is included in request body", async () => {
        const mockFetch = vi.fn().mockResolvedValue({
            ok: true,
            body: new ReadableStream({
                start(controller) {
                    controller.enqueue(new TextEncoder().encode('data: [DONE]\n\n'));
                    controller.close();
                },
            }),
        });
        vi.stubGlobal("fetch", mockFetch);

        for await (const chunk of llmChat({
            mode: "router",
            app_model_id: "test",
            messages_json: singleUserMessage("hi"),
            disable_cache: true,
            connection: {},
        })) {
            // consume stream
        }

        const callBody = JSON.parse(mockFetch.mock.calls[0][1].body);
        expect(callBody.disable_cache).toBe(true);
    });
});