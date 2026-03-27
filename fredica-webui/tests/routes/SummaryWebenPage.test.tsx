import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import SummaryWebenPage from "~/routes/material.$materialId.summary.weben";

// ────────────────────────────────────────────────────────────────────────────
// SummaryWebenPage テスト概要
// ────────────────────────────────────────────────────────────────────────────
//
// Phase 7 以降、"生成"/"预览" の fetch フローは 2 段階:
//   1. PromptTemplatePreviewRoute (SSE) → resolved prompt text
//   2. LlmProxyChatRoute (SSE)         → LLM delta chunks
//
// generateReviewedResult() ヘルパーは URL で判別して両方をモックする。
//
// 追加テスト (W1-W4):
//   W1 preview calls PromptTemplatePreviewRoute
//   W2 generate calls preview script first, then LLM (sequential order)
//   W3 script error on generate stops before LLM call
//   W4 previewPromptScript HTTP failure calls print_error
// ────────────────────────────────────────────────────────────────────────────

const mockApiFetch = vi.fn();
const mockWorkspaceContext = vi.fn();
const mockAppConfig = vi.fn();
const toastSuccess = vi.fn();
const printError = vi.fn();
const reportHttpError = vi.fn();

vi.mock("react-router", async () => {
    const actual = await vi.importActual<typeof import("react-router")>("react-router");
    return {
        ...actual,
        useParams: () => ({ materialId: "bilibili_bvid__BV1sNA1ztEvY__P1" }),
    };
});

vi.mock("~/routes/material.$materialId", () => ({
    useWorkspaceContext: () => mockWorkspaceContext(),
}));

vi.mock("~/util/app_fetch", async () => {
    const actual = await vi.importActual<typeof import("~/util/app_fetch")>("~/util/app_fetch");
    return {
        ...actual,
        useAppFetch: () => ({ apiFetch: mockApiFetch }),
    };
});

vi.mock("~/context/appConfig", () => ({
    useAppConfig: () => mockAppConfig(),
}));

vi.mock("react-toastify", () => ({
    toast: {
        success: (...args: unknown[]) => toastSuccess(...args),
    },
}));

vi.mock("~/util/error_handler", () => ({
    print_error: (...args: unknown[]) => printError(...args),
    reportHttpError: (...args: unknown[]) => reportHttpError(...args),
}));

function createSseResponse(chunks: string[]) {
    const encoder = new TextEncoder();
    let index = 0;
    return new Response(new ReadableStream<Uint8Array>({
        pull(controller) {
            if (index >= chunks.length) {
                controller.close();
                return;
            }
            controller.enqueue(encoder.encode(chunks[index]));
            index += 1;
        },
    }), {
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
    });
}

function createDeltaChunk(content: string) {
    return `data: ${JSON.stringify({ choices: [{ delta: { content } }] })}\n`;
}

async function generateReviewedResult(user: ReturnType<typeof userEvent.setup>, payload: string) {
    // Phase 7: 生成按钮触发两次 fetch:
    //   1. PromptTemplatePreviewRoute → SSE {"type":"result","prompt_text":"..."}
    //   2. LlmProxyChatRoute         → SSE delta chunks
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
        if (typeof url === "string" && url.includes("PromptTemplatePreviewRoute")) {
            return createSseResponse([
                `data: ${JSON.stringify({ type: "result", prompt_text: "resolved prompt text" })}\n\n`,
            ]);
        }
        // LlmProxyChatRoute: 返回 LLM delta 格式
        return createSseResponse([
            createDeltaChunk(payload),
            "data: [DONE]\n",
        ]);
    }));

    render(<SummaryWebenPage />);

    await waitFor(() => {
        expect(screen.getByText("当前使用的模型为 默认聊天模型")).toBeTruthy();
    });

    await user.click(screen.getByRole("button", { name: "生成" }));
    await user.click(screen.getByRole("tab", { name: "组件渲染" }));
}

describe("SummaryWebenPage", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.stubGlobal("fetch", vi.fn(async () => createSseResponse(["data: [DONE]\n"])));

        mockWorkspaceContext.mockReturnValue({
            material: {
                id: "bilibili_bvid__BV1sNA1ztEvY__P1",
                title: "测试素材",
                source_id: "BV1sNA1ztEvY",
                duration: 180,
            },
        });
        mockAppConfig.mockReturnValue({
            appConfig: {
                webserver_domain: "localhost",
                webserver_port: "7631",
                webserver_schema: "http",
                webserver_auth_token: "token",
            },
        });

        mockApiFetch.mockImplementation(async (path: string) => {
            if (path.startsWith("/api/v1/MaterialSubtitleListRoute")) {
                return {
                    resp: new Response("[]", { status: 200 }),
                    data: [{
                        lan: "zh-CN",
                        lan_doc: "中文",
                        source: "bilibili_platform",
                        queried_at: 1,
                        subtitle_url: "https://example.com/subtitle.json",
                        type: 1,
                    }],
                };
            }
            if (path === "/api/v1/LlmModelListRoute") {
                return {
                    resp: new Response("[]", { status: 200 }),
                    data: [{ app_model_id: "chat_model_id", label: "默认聊天模型", notes: "note" }],
                };
            }
            if (path.startsWith("/api/v1/LlmModelAvailabilityRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: {
                        available_count: 1,
                        has_any_available_model: true,
                        selected_model_id: "chat_model_id",
                        selected_model_available: true,
                    },
                };
            }
            if (path === "/api/v1/MaterialSubtitleContentRoute") {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: {
                        text: "GPIO 是一种接口。开漏输出是一种模式。",
                        word_count: 20,
                        segment_count: 2,
                        source: "bilibili_platform",
                        subtitle_url: "https://example.com/subtitle.json",
                    },
                };
            }
            if (path === "/api/v1/WebenConceptBatchImportRoute") {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: {
                        ok: true,
                        source_id: "source-1",
                        concept_total: 1,
                        relation_imported: 1,
                        flashcard_imported: 1,
                    },
                };
            }
            throw new Error(`unexpected path: ${path}`);
        });
    });

    it("renders without crashing for /material/:id/summary/weben", async () => {
        render(<SummaryWebenPage />);

        expect(screen.getByText("Weben 知识提取工作台")).toBeTruthy();
        await waitFor(() => {
            expect(mockApiFetch).toHaveBeenCalledWith(
                expect.stringContaining("/api/v1/MaterialSubtitleListRoute?material_id="),
                { method: "GET" },
                { silent: true },
            );
            expect(mockApiFetch).toHaveBeenCalledWith(
                "/api/v1/LlmModelListRoute",
                { method: "GET" },
                { silent: true },
            );
            expect(mockApiFetch).toHaveBeenCalledWith(
                "/api/v1/LlmModelAvailabilityRoute?selected_model_id=chat_model_id",
                { method: "GET" },
                { silent: true },
            );
        });
        expect(screen.queryByText("模型选择")).toBeNull();
    });

    it("keeps rendering when workspace material or API payload shape is malformed", async () => {
        mockWorkspaceContext.mockReturnValueOnce({
            material: {
                id: 123,
                title: null,
                source_id: undefined,
                duration: "bad",
            },
        });
        mockApiFetch.mockImplementation(async (path: string) => {
            if (path.startsWith("/api/v1/MaterialSubtitleListRoute")) {
                return { resp: new Response("[]", { status: 200 }), data: [{ subtitle_url: 123, lan: null }] };
            }
            if (path === "/api/v1/LlmModelListRoute") {
                return { resp: new Response("[]", { status: 200 }), data: [{ app_model_id: 42, label: null }] };
            }
            throw new Error(`unexpected path: ${path}`);
        });

        render(<SummaryWebenPage />);

        expect(screen.getByText("Weben 知识提取工作台")).toBeTruthy();
        await waitFor(() => {
            expect(screen.getByText("暂无可用字幕，请先去“字幕提取”标签获取字幕")).toBeTruthy();
            expect(screen.getByRole("tab", { name: "⚠ 设置" })).toBeTruthy();
        });
    });

    it("keeps rendering when app config hook or app fetch hook returns malformed data", async () => {
        mockAppConfig.mockReturnValueOnce({ appConfig: { webserver_port: 7631, webserver_schema: "ws" } });
        mockApiFetch.mockReset();
        render(<SummaryWebenPage />);

        expect(screen.getByText("Weben 知识提取工作台")).toBeTruthy();
        await waitFor(() => {
            expect(screen.getByText("当前阶段")).toBeTruthy();
        });
    });

    it("generates and renders parsed result", async () => {
        const user = userEvent.setup();

        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"GPIO","type":"术语","description":"通用输入输出"}],"relations":[],"flashcards":[]}',
        );

        await waitFor(() => {
            expect(screen.getByText("概念 (1)")).toBeTruthy();
            expect(screen.getByText("GPIO")).toBeTruthy();
        });
    });

    it("updates reviewed result after deleting a concept and blocks save when references break", async () => {
        const user = userEvent.setup();

        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"GPIO","type":"术语","description":"通用输入输出"},{"name":"开漏输出","type":"术语","description":"一种模式"}],"relations":[{"subject":"GPIO","predicate":"用于","object":"开漏输出"}],"flashcards":[{"question":"GPIO 是什么？","answer":"一种接口","concept":"GPIO"}]}',
        );

        await waitFor(() => {
            expect(screen.getByText("概念 (2)")).toBeTruthy();
            expect(screen.getByText("关系 (1)")).toBeTruthy();
            expect(screen.getByText("闪卡 (1)")).toBeTruthy();
        });

        await user.click(screen.getByRole("button", { name: "删除概念 开漏输出" }));

        await waitFor(() => {
            expect(screen.getByText("概念 (1)")).toBeTruthy();
            expect(screen.queryByRole("button", { name: "删除概念 开漏输出" })).toBeNull();
            expect(screen.getByText("关系“GPIO 用于 开漏输出”引用了不存在的概念")).toBeTruthy();
        });
        expect(screen.getByRole("button", { name: "保存到 Weben" }).hasAttribute("disabled")).toBe(true);
    });

    it("saves the sanitized reviewed result", async () => {
        const user = userEvent.setup();

        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"GPIO","type":"术语","description":"通用输入输出"},{"name":"开漏输出","type":"术语","description":"一种模式"}],"relations":[{"subject":"GPIO","predicate":"用于","object":"开漏输出"}],"flashcards":[{"question":"GPIO 是什么？","answer":"一种接口","concept":"GPIO"}]}',
        );

        await waitFor(() => {
            expect(screen.getByText("概念 (2)")).toBeTruthy();
        });

        await user.click(screen.getByRole("button", { name: "删除关系 GPIO 用于 开漏输出" }));
        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));

        await waitFor(() => {
            expect(mockApiFetch).toHaveBeenCalledWith(
                "/api/v1/WebenConceptBatchImportRoute",
                expect.objectContaining({
                    method: "POST",
                    body: JSON.stringify({
                        material_id: "bilibili_bvid__BV1sNA1ztEvY__P1",
                        source_title: "测试素材",
                        concepts: [
                            { name: "GPIO", type: "术语", description: "通用输入输出" },
                            { name: "开漏输出", type: "术语", description: "一种模式" },
                        ],
                        relations: [],
                        flashcards: [
                            { question: "GPIO 是什么？", answer: "一种接口", concept: "GPIO" },
                        ],
                    }),
                }),
                { silent: true },
            );
        });
        expect(toastSuccess).toHaveBeenCalled();
    });

    it("reports HTTP errors when save fails with non-2xx response", async () => {
        mockApiFetch.mockImplementation(async (path: string) => {
            if (path.startsWith("/api/v1/MaterialSubtitleListRoute")) {
                return {
                    resp: new Response("[]", { status: 200 }),
                    data: [{ lan: "zh-CN", lan_doc: "中文", source: "bilibili_platform", queried_at: 1, subtitle_url: "https://example.com/subtitle.json", type: 1 }],
                };
            }
            if (path === "/api/v1/LlmModelListRoute") {
                return {
                    resp: new Response("[]", { status: 200 }),
                    data: [{ app_model_id: "chat_model_id", label: "默认聊天模型", notes: "note" }],
                };
            }
            if (path.startsWith("/api/v1/LlmModelAvailabilityRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: {
                        available_count: 1,
                        has_any_available_model: true,
                        selected_model_id: "chat_model_id",
                        selected_model_available: true,
                    },
                };
            }
            if (path === "/api/v1/MaterialSubtitleContentRoute") {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: { text: "GPIO 是一种接口。", word_count: 8, segment_count: 1, source: "bilibili_platform", subtitle_url: "https://example.com/subtitle.json" },
                };
            }
            if (path === "/api/v1/WebenConceptBatchImportRoute") {
                return {
                    resp: new Response("{}", { status: 500 }),
                    data: {},
                };
            }
            throw new Error(`unexpected path: ${path}`);
        });

        const user = userEvent.setup();
        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"GPIO","type":"术语","description":"通用输入输出"}],"relations":[],"flashcards":[]}',
        );

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));

        await waitFor(() => {
            expect(reportHttpError).toHaveBeenCalledWith("保存到 Weben 失败", expect.any(Response));
        });
    });

    it("reports backend error field when save returns business error", async () => {
        mockApiFetch.mockImplementation(async (path: string) => {
            if (path.startsWith("/api/v1/MaterialSubtitleListRoute")) {
                return {
                    resp: new Response("[]", { status: 200 }),
                    data: [{ lan: "zh-CN", lan_doc: "中文", source: "bilibili_platform", queried_at: 1, subtitle_url: "https://example.com/subtitle.json", type: 1 }],
                };
            }
            if (path === "/api/v1/LlmModelListRoute") {
                return {
                    resp: new Response("[]", { status: 200 }),
                    data: [{ app_model_id: "chat_model_id", label: "默认聊天模型", notes: "note" }],
                };
            }
            if (path.startsWith("/api/v1/LlmModelAvailabilityRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: {
                        available_count: 1,
                        has_any_available_model: true,
                        selected_model_id: "chat_model_id",
                        selected_model_available: true,
                    },
                };
            }
            if (path === "/api/v1/MaterialSubtitleContentRoute") {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: { text: "GPIO 是一种接口。", word_count: 8, segment_count: 1, source: "bilibili_platform", subtitle_url: "https://example.com/subtitle.json" },
                };
            }
            if (path === "/api/v1/WebenConceptBatchImportRoute") {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: { error: "duplicate source" },
                };
            }
            throw new Error(`unexpected path: ${path}`);
        });

        const user = userEvent.setup();
        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"GPIO","type":"术语","description":"通用输入输出"}],"relations":[],"flashcards":[]}',
        );

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));

        await waitFor(() => {
            expect(printError).toHaveBeenCalledWith({ reason: "保存到 Weben 失败: duplicate source" });
        });
    });

    it("shows host-invalid message and disables generate when no models are available", async () => {
        mockApiFetch.mockImplementation(async (path: string) => {
            if (path.startsWith("/api/v1/MaterialSubtitleListRoute")) {
                return {
                    resp: new Response("[]", { status: 200 }),
                    data: [],
                };
            }
            if (path === "/api/v1/LlmModelListRoute") {
                return {
                    resp: new Response("[]", { status: 200 }),
                    data: [],
                };
            }
            if (path.startsWith("/api/v1/LlmModelAvailabilityRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: {
                        available_count: 0,
                        has_any_available_model: false,
                        selected_model_id: "chat_model_id",
                        selected_model_available: false,
                    },
                };
            }
            throw new Error(`unexpected path: ${path}`);
        });

        render(<SummaryWebenPage />);

        await waitFor(() => {
            expect(screen.getByRole("tab", { name: "⚠ 设置" })).toBeTruthy();
            expect(screen.getByText("服主配置无效")).toBeTruthy();
        });
        expect(screen.getAllByRole("button", { name: "生成" })[0].hasAttribute("disabled")).toBe(true);
    });

    it("shows selected-invalid message and disables generate", async () => {
        mockApiFetch.mockImplementation(async (path: string) => {
            if (path.startsWith("/api/v1/MaterialSubtitleListRoute")) {
                return {
                    resp: new Response("[]", { status: 200 }),
                    data: [],
                };
            }
            if (path === "/api/v1/LlmModelListRoute") {
                return {
                    resp: new Response("[]", { status: 200 }),
                    data: [{ app_model_id: "chat_model_id", label: "默认聊天模型", notes: "note" }],
                };
            }
            if (path.startsWith("/api/v1/LlmModelAvailabilityRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: {
                        available_count: 1,
                        has_any_available_model: true,
                        selected_model_id: "chat_model_id",
                        selected_model_available: false,
                    },
                };
            }
            throw new Error(`unexpected path: ${path}`);
        });

        render(<SummaryWebenPage />);

        await waitFor(() => {
            expect(screen.getByRole("tab", { name: "⚠ 设置" })).toBeTruthy();
            expect(screen.getByText("你配置的模型失效")).toBeTruthy();
        });
        expect(screen.getAllByRole("button", { name: "生成" })[0].hasAttribute("disabled")).toBe(true);
    });

    it("enables generate when PromptBuilder resolves a valid model", async () => {
        render(<SummaryWebenPage />);

        await waitFor(() => {
            expect(screen.getByText("当前使用的模型为 默认聊天模型")).toBeTruthy();
        });
        expect(screen.getAllByRole("button", { name: "生成" })[0].hasAttribute("disabled")).toBe(false);
    });

    // ── W1-W4: Phase 7 backend script execution ─────────────────────────────

    it("W1: 预览 calls PromptTemplatePreviewRoute (backend script execution)", async () => {
        const fetchMock = vi.fn(async (_url: string) =>
            createSseResponse([
                `data: ${JSON.stringify({ type: "result", prompt_text: "my resolved prompt" })}\n\n`,
            ])
        );
        vi.stubGlobal("fetch", fetchMock);

        const user = userEvent.setup();
        render(<SummaryWebenPage />);
        await waitFor(() => { expect(screen.getByText("当前使用的模型为 默认聊天模型")).toBeTruthy(); });

        await user.click(screen.getByRole("button", { name: "预览" }));

        await waitFor(() => {
            expect(fetchMock).toHaveBeenCalledWith(
                expect.stringContaining("PromptTemplatePreviewRoute"),
                expect.any(Object),
            );
        });
    });

    it("W2: 生成 calls preview script first then LLM (sequential)", async () => {
        const callOrder: string[] = [];
        vi.stubGlobal("fetch", vi.fn(async (url: string) => {
            callOrder.push(String(url));
            if (url.includes("PromptTemplatePreviewRoute")) {
                return createSseResponse([
                    `data: ${JSON.stringify({ type: "result", prompt_text: "resolved" })}\n\n`,
                ]);
            }
            return createSseResponse(["data: [DONE]\n"]);
        }));

        const user = userEvent.setup();
        render(<SummaryWebenPage />);
        await waitFor(() => { expect(screen.getByText("当前使用的模型为 默认聊天模型")).toBeTruthy(); });

        await user.click(screen.getByRole("button", { name: "生成" }));

        await waitFor(() => {
            const previewIdx = callOrder.findIndex(u => u.includes("PromptTemplatePreviewRoute"));
            const llmIdx = callOrder.findIndex(u => u.includes("LlmProxyChatRoute"));
            expect(previewIdx).toBeGreaterThanOrEqual(0);
            expect(llmIdx).toBeGreaterThanOrEqual(0);
            // preview script 必须先于 LLM 调用
            expect(previewIdx).toBeLessThan(llmIdx);
        });
    });

    it("W3: script error on 生成 stops before LLM call", async () => {
        const calledUrls: string[] = [];
        vi.stubGlobal("fetch", vi.fn(async (url: string) => {
            calledUrls.push(String(url));
            if (url.includes("PromptTemplatePreviewRoute")) {
                return createSseResponse([
                    `data: ${JSON.stringify({ type: "error", error: "字幕不可用", error_type: "script_error" })}\n\n`,
                ]);
            }
            return createSseResponse(["data: [DONE]\n"]);
        }));

        const user = userEvent.setup();
        render(<SummaryWebenPage />);
        await waitFor(() => { expect(screen.getByText("当前使用的模型为 默认聊天模型")).toBeTruthy(); });

        await user.click(screen.getByRole("button", { name: "生成" }));

        await waitFor(() => {
            expect(calledUrls.some(u => u.includes("PromptTemplatePreviewRoute"))).toBe(true);
            expect(printError).toHaveBeenCalledWith({ reason: "脚本执行失败: 字幕不可用" });
        });
        // 确认 LLM 路由未被调用
        expect(calledUrls.every(u => !u.includes("LlmProxyChatRoute"))).toBe(true);
    });

    it("reports script error on 预览", async () => {
        vi.stubGlobal("fetch", vi.fn(async () =>
            createSseResponse([
                `data: ${JSON.stringify({ type: "error", error: "脚本异常", error_type: "script_error" })}\n\n`,
            ])
        ));

        const user = userEvent.setup();
        render(<SummaryWebenPage />);
        await waitFor(() => { expect(screen.getByText("当前使用的模型为 默认聊天模型")).toBeTruthy(); });

        await user.click(screen.getByRole("button", { name: "预览" }));

        await waitFor(() => {
            expect(printError).toHaveBeenCalledWith({ reason: "脚本执行失败: 脚本异常" });
            expect(screen.getByText("脚本执行失败：脚本异常")).toBeTruthy();
        });
    });

    it("W4: previewPromptScript HTTP failure calls print_error on 预览", async () => {
        vi.stubGlobal("fetch", vi.fn(async () =>
            new Response("Internal Error", { status: 500 })
        ));

        const user = userEvent.setup();
        render(<SummaryWebenPage />);
        await waitFor(() => { expect(screen.getByText("当前使用的模型为 默认聊天模型")).toBeTruthy(); });

        await user.click(screen.getByRole("button", { name: "预览" }));

        await waitFor(() => {
            expect(printError).toHaveBeenCalledWith(
                expect.objectContaining({ reason: "构建 Prompt 预览失败" }),
            );
        });
    });
});
