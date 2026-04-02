import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import SummaryWebenPage, { resolveConceptDiffBaseline } from "~/routes/material.$materialId.summary.weben";

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
        Link: ({ children, to }: { children: React.ReactNode; to: string }) => <a href={String(to)}>{children}</a>,
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

describe("resolveConceptDiffBaseline", () => {
    function makeConcept(name: string, materialId: string) {
        return {
            id: `c-${materialId}-${name}`,
            material_id: materialId,
            canonical_name: name,
            concept_type: "术语",
            brief_definition: `${name} 的定义`,
            metadata_json: "{}",
            confidence: 1,
            first_seen_at: 0,
            last_seen_at: 0,
            created_at: 0,
            updated_at: 0,
        };
    }

    it("uses saved baseline first so changed/unchanged are preserved after a prior save", () => {
        const savedBaseline = [makeConcept("图", "mat-1")];
        const fetchedConcepts = [
            makeConcept("图", "mat-1"),
            makeConcept("消费升级", "mat-2"),
        ];

        const result = resolveConceptDiffBaseline({
            fetchedConcepts,
            savedConceptBaseline: savedBaseline,
            materialId: "mat-1",
        });

        expect(result).toEqual(savedBaseline);
    });

    it("falls back to fetched concepts filtered by current material only", () => {
        const fetchedConcepts = [
            makeConcept("图", "mat-1"),
            makeConcept("边", "mat-1"),
            makeConcept("消费升级", "mat-2"),
            makeConcept("房贷压力", "mat-2"),
        ];

        const result = resolveConceptDiffBaseline({
            fetchedConcepts,
            savedConceptBaseline: null,
            materialId: "mat-1",
        });

        expect(result.map(item => item.canonical_name)).toEqual(["图", "边"]);
    });

    it("uses route material id fallback when workspace material id is empty", () => {
        const fetchedConcepts = [
            makeConcept("图", "bilibili_bvid__BV1sNA1ztEvY__P1"),
            makeConcept("边", "bilibili_bvid__BV1sNA1ztEvY__P1"),
            makeConcept("消费升级", "bilibili_bvid__BV1tv4y1q7eL__P1"),
        ];

        const result = resolveConceptDiffBaseline({
            fetchedConcepts,
            savedConceptBaseline: null,
            materialId: "bilibili_bvid__BV1sNA1ztEvY__P1",
        });

        expect(result.map(item => item.canonical_name)).toEqual(["图", "边"]);
    });
});

async function generateReviewedResult(user: ReturnType<typeof userEvent.setup>, payload: string) {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
        if (typeof url === "string" && url.includes("PromptTemplatePreviewRoute")) {
            return createSseResponse([
                `data: ${JSON.stringify({ type: "result", prompt_text: "resolved prompt text" })}\n\n`,
            ]);
        }
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
            if (path.startsWith("/api/v1/WebenConceptListRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: { items: [], total: 0, offset: 0, limit: 200 },
                };
            }
            if (path === "/api/v1/WebenExtractionRunSaveRoute") {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: {
                        ok: true,
                        source_id: "source-1",
                        run_id: "run-1",
                        concept_total: 1,
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

    it("generates and renders parsed concept result", async () => {
        const user = userEvent.setup();

        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"GPIO","types":["术语"],"description":"通用输入输出"}]}',
        );

        await waitFor(() => {
            expect(screen.getByText("概念 (1)")).toBeTruthy();
            expect(screen.getByText("GPIO")).toBeTruthy();
        });
        expect(screen.queryByText(/关系|闪卡/)).toBeNull();
    });

    it("updates reviewed result after deleting a concept", async () => {
        const user = userEvent.setup();

        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"GPIO","types":["术语"],"description":"通用输入输出"},{"name":"开漏输出","types":["术语"],"description":"一种模式"}]}',
        );

        await waitFor(() => {
            expect(screen.getByText("概念 (2)")).toBeTruthy();
        });

        await user.click(screen.getByRole("button", { name: "删除概念 开漏输出" }));

        await waitFor(() => {
            expect(screen.getByText("概念 (1)")).toBeTruthy();
            expect(screen.queryByRole("button", { name: "删除概念 开漏输出" })).toBeNull();
        });
    });

    it("saves the reviewed concept result", async () => {
        const user = userEvent.setup();

        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"GPIO","types":["术语"],"description":"通用输入输出"},{"name":"链表","types":["数据结构"],"description":"一种线性数据结构"}]}',
        );

        await waitFor(() => {
            expect(screen.getByText("概念 (2)")).toBeTruthy();
        });

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));

        // Editor modal appears — click confirm
        await waitFor(() => {
            expect(screen.getByText("审阅概念变更")).toBeTruthy();
        });
        await user.click(screen.getByRole("button", { name: "确认保存" }));

        await waitFor(() => {
            expect(mockApiFetch).toHaveBeenCalledWith(
                "/api/v1/WebenExtractionRunSaveRoute",
                expect.objectContaining({ method: "POST" }),
                { silent: true },
            );
        });
        expect(toastSuccess).toHaveBeenCalledWith("已保存到 Weben：概念 1 条");
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
            if (path.startsWith("/api/v1/WebenConceptListRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: { items: [], total: 0, offset: 0, limit: 200 },
                };
            }
            if (path === "/api/v1/WebenExtractionRunSaveRoute") {
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
            '{"concepts":[{"name":"GPIO","types":["术语"],"description":"通用输入输出"}]}',
        );

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));
        await waitFor(() => { expect(screen.getByText("审阅概念变更")).toBeTruthy(); });
        await user.click(screen.getByRole("button", { name: "确认保存" }));

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
            if (path.startsWith("/api/v1/WebenConceptListRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: { items: [], total: 0, offset: 0, limit: 200 },
                };
            }
            if (path === "/api/v1/WebenExtractionRunSaveRoute") {
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
            '{"concepts":[{"name":"GPIO","types":["术语"],"description":"通用输入输出"}]}',
        );

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));
        await waitFor(() => { expect(screen.getByText("审阅概念变更")).toBeTruthy(); });
        await user.click(screen.getByRole("button", { name: "确认保存" }));

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

    it("W5: scrolls save button into view when LLM output is parsed", async () => {
        const scrollIntoView = vi.spyOn(window.HTMLElement.prototype, "scrollIntoView");

        const user = userEvent.setup();
        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"GPIO","types":["术语"],"description":"通用输入输出"}]}',
        );

        await waitFor(() => {
            expect(screen.getByText("概念 (1)")).toBeTruthy();
        });
        expect(scrollIntoView).toHaveBeenCalledWith({ behavior: "smooth", block: "nearest" });
    });

    // ── 审阅概念变更 diff modal ──────────────────────────────────────────────────

    /**
     * 辅助：在 generateReviewedResult 之后，覆写 mockApiFetch 让
     * WebenConceptListRoute 返回指定的已有概念列表。
     * 其余路径不会在"保存到 Weben"流程中被调用，直接 throw 以便发现漏网请求。
     */
    function mockExistingConcepts(items: object[]) {
        mockApiFetch.mockImplementation(async (path: string) => {
            if (path.startsWith("/api/v1/WebenConceptListRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: { items, total: items.length, offset: 0, limit: 200 },
                };
            }
            if (path === "/api/v1/WebenExtractionRunSaveRoute") {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: { ok: true, source_id: "source-1", run_id: "run-1", concept_total: items.length },
                };
            }
            throw new Error(`unexpected API path after render: ${path}`);
        });
    }

    function makeExistingConcept(
        canonicalName: string,
        conceptType: string,
        briefDefinition: string,
        overrides: Record<string, unknown> = {},
    ) {
        return {
            id: `c-${canonicalName}`,
            material_id: "bilibili_bvid__BV1sNA1ztEvY__P1",
            canonical_name: canonicalName,
            concept_type: conceptType,
            brief_definition: briefDefinition,
            metadata_json: "{}",
            confidence: 1.0,
            first_seen_at: 1000,
            last_seen_at: 1000,
            created_at: 1000,
            updated_at: 1000,
            ...overrides,
        };
    }

    it("W7: 审阅概念变更 shows all 新增 when no existing concepts for this material", async () => {
        // beforeEach already mocks WebenConceptListRoute → { items: [] }
        const user = userEvent.setup();
        await generateReviewedResult(
            user,
            '{"concepts":['
            + '{"name":"图","types":["术语"],"description":"由节点和边组成的数学结构"},'
            + '{"name":"节点","types":["术语"],"description":"图的基本组成单位"}'
            + ']}',
        );
        await waitFor(() => { expect(screen.getByText("概念 (2)")).toBeTruthy(); });

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));

        await waitFor(() => {
            expect(screen.getByText("审阅概念变更")).toBeTruthy();
            expect(screen.getByText("新增概念")).toBeTruthy();
        });
        // No 变化概念 or 本次未提及 when nothing existed before
        expect(screen.queryByText("变化概念")).toBeNull();
        expect(screen.queryByText(/本次未提及/)).toBeNull();
    });

    it("W8: 审阅概念变更 shows 新增 and 本次未提及 on re-save", async () => {
        const user = userEvent.setup();
        // incoming: 图 (same) + 握手引理 (new), but NOT 边 → 边 should appear in 本次未提及
        await generateReviewedResult(
            user,
            '{"concepts":['
            + '{"name":"图","types":["术语"],"description":"由节点和边组成的数学结构"},'
            + '{"name":"握手引理","types":["定理"],"description":"所有顶点度数之和等于边数的两倍"}'
            + ']}',
        );
        await waitFor(() => { expect(screen.getByText("概念 (2)")).toBeTruthy(); });

        mockExistingConcepts([
            makeExistingConcept("图", "术语", "由节点和边组成的数学结构"),
            makeExistingConcept("边", "术语", "连接两节点的线段"),
        ]);

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));

        await waitFor(() => {
            expect(screen.getByText("审阅概念变更")).toBeTruthy();
        });
        // 握手引理 is new → 新增概念 section
        expect(screen.getByText("新增概念")).toBeTruthy();
        // 边 is not in incoming → 本次未提及（原有概念） section
        expect(screen.getByText("本次未提及（原有概念）")).toBeTruthy();
        // 图 is unchanged → no 变化概念 section
        expect(screen.queryByText("变化概念")).toBeNull();
    });

    it("W10: 审阅概念变更 shows 没有变化 when re-save with identical concepts", async () => {
        const user = userEvent.setup();
        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"图","types":["术语"],"description":"由节点和边组成的数学结构"}]}',
        );
        await waitFor(() => { expect(screen.getByText("概念 (1)")).toBeTruthy(); });

        mockExistingConcepts([
            makeExistingConcept("图", "术语", "由节点和边组成的数学结构"),
        ]);

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));

        await waitFor(() => {
            expect(screen.getByText("审阅概念变更")).toBeTruthy();
            expect(screen.getByText("没有变化")).toBeTruthy();
        });
        expect(screen.queryByText("新增概念")).toBeNull();
        expect(screen.queryByText("变化概念")).toBeNull();
        expect(screen.queryByText(/本次未提及/)).toBeNull();
    });

    it("W11: 审阅概念变更 folds changed concept into 合并后无变化 when merge removes effective type-only diff", async () => {
        const user = userEvent.setup();
        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"图","types":["术语"],"description":"由节点和边组成的数学结构"}]}',
        );
        await waitFor(() => { expect(screen.getByText("概念 (1)")).toBeTruthy(); });

        mockExistingConcepts([
            makeExistingConcept("图", "术语,定理", "由节点和边组成的数学结构"),
        ]);

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));

        await waitFor(() => {
            expect(screen.getByText("审阅概念变更")).toBeTruthy();
            expect(screen.getByText("变化概念")).toBeTruthy();
            expect(screen.getByText("合并后无变化")).toBeTruthy();
        });
    });

    it("W12: 审阅概念变更 shows 变化概念 when concept description changed on re-save", async () => {
        const user = userEvent.setup();
        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"图","types":["术语"],"description":"新描述：图是抽象的节点集合"}]}',
        );
        await waitFor(() => { expect(screen.getByText("概念 (1)")).toBeTruthy(); });

        mockExistingConcepts([
            makeExistingConcept("图", "术语", "旧描述：图论的基础结构"),
        ]);

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));

        await waitFor(() => {
            expect(screen.getByText("审阅概念变更")).toBeTruthy();
            expect(screen.getByText("变化概念")).toBeTruthy();
        });
        expect(screen.queryByText("新增概念")).toBeNull();
        expect(screen.queryByText(/本次未提及/)).toBeNull();
    });

    it("W12: save refreshes local old-data baseline so next save shows 没有变化 even if list API is stale", async () => {
        const user = userEvent.setup();
        const savedConcepts = [
            {
                name: "图",
                types: ["术语"],
                description: "由节点和边组成的数学结构",
            },
        ];

        await generateReviewedResult(
            user,
            JSON.stringify({ concepts: savedConcepts }),
        );
        await waitFor(() => { expect(screen.getByText("概念 (1)")).toBeTruthy(); });

        mockApiFetch.mockImplementation(async (path: string) => {
            if (path.startsWith("/api/v1/WebenConceptListRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: { items: [], total: 0, offset: 0, limit: 200 },
                };
            }
            if (path === "/api/v1/WebenExtractionRunSaveRoute") {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: { ok: true, source_id: "source-1", run_id: "run-1", concept_total: 1 },
                };
            }
            throw new Error(`unexpected API path after render: ${path}`);
        });

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));
        await waitFor(() => { expect(screen.getByText("审阅概念变更")).toBeTruthy(); });
        await user.click(screen.getByRole("button", { name: "确认保存" }));
        await waitFor(() => {
            expect(toastSuccess).toHaveBeenCalledWith("已保存到 Weben：概念 1 条");
        });

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));
        await waitFor(() => {
            expect(screen.getByText("审阅概念变更")).toBeTruthy();
            expect(screen.getByText("没有变化")).toBeTruthy();
        });
    });

    it("W14: initial review uses route material id when workspace material id is empty", async () => {
        mockWorkspaceContext.mockReturnValueOnce({
            material: {
                id: "",
                title: "测试素材",
                source_id: "BV1sNA1ztEvY",
                duration: 180,
            },
        });

        const user = userEvent.setup();
        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"图","types":["术语"],"description":"由节点和边组成的数学结构"}]}',
        );
        await waitFor(() => { expect(screen.getByText("概念 (1)")).toBeTruthy(); });

        mockExistingConcepts([
            makeExistingConcept("图", "术语", "由节点和边组成的数学结构"),
            makeExistingConcept("边", "术语", "连接两节点的线段"),
        ]);

        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));

        await waitFor(() => {
            expect(screen.getByText("审阅概念变更")).toBeTruthy();
            expect(screen.getByText("本次未提及（原有概念）")).toBeTruthy();
        });
        expect(screen.queryByText("新增概念")).toBeNull();
        expect(screen.queryByText("变化概念")).toBeNull();
    });

    it("W6: save uses material_id filter (not exclusive_material_id or source_id)", async () => {
        const user = userEvent.setup();

        await generateReviewedResult(
            user,
            '{"concepts":[{"name":"GPIO","types":["术语"],"description":"通用输入输出"}]}',
        );
        await waitFor(() => { expect(screen.getByText("概念 (1)")).toBeTruthy(); });

        mockApiFetch.mockClear();
        await user.click(screen.getByRole("button", { name: "保存到 Weben" }));

        await waitFor(() => {
            expect(mockApiFetch).toHaveBeenCalledWith(
                expect.stringContaining("material_id=bilibili_bvid__BV1sNA1ztEvY__P1"),
                expect.anything(),
                expect.anything(),
            );
        });
        expect(mockApiFetch).not.toHaveBeenCalledWith(
            expect.stringContaining("exclusive_material_id="),
            expect.anything(),
            expect.anything(),
        );
        expect(mockApiFetch).not.toHaveBeenCalledWith(
            expect.stringContaining("source_id="),
            expect.anything(),
            expect.anything(),
        );
    });
});
