import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PromptBuilder } from "~/components/prompt-builder/PromptBuilder";
import { PromptStreamPane } from "~/components/prompt-builder/PromptStreamPane";
import type { ApiFetchFn } from "~/util/materialWebenApi";

const printError = vi.fn();

vi.mock("~/util/error_handler", () => ({
    print_error: (...args: unknown[]) => printError(...args),
}));

function createApiFetch(options?: {
    models?: Array<{ app_model_id: string; label: string; notes?: string | null }>;
    availability?: {
        available_count: number;
        has_any_available_model: boolean;
        selected_model_id: string | null;
        selected_model_available: boolean;
    };
}): ApiFetchFn {
    const models = options?.models ?? [{ app_model_id: "chat_model_id", label: "默认聊天模型", notes: "note" }];
    const availability = options?.availability ?? {
        available_count: models.length,
        has_any_available_model: models.length > 0,
        selected_model_id: models[0]?.app_model_id ?? null,
        selected_model_available: models.length > 0,
    };

    return vi.fn(async (path: string) => {
        if (path === "/api/v1/LlmModelListRoute") {
            return { resp: new Response("[]", { status: 200 }), data: models };
        }
        if (path.startsWith("/api/v1/LlmModelAvailabilityRoute")) {
            return { resp: new Response("{}", { status: 200 }), data: availability };
        }
        if (path.startsWith("/api/v1/PromptTemplateDetailRoute")) {
            return { resp: new Response("{}", { status: 200 }), data: null };
        }
        throw new Error(`unexpected path: ${path}`);
    }) as ApiFetchFn;
}

describe("PromptBuilder", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        window.localStorage.clear();
    });

    it("keeps textarea value when switching tabs", async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();

        render(
            <PromptBuilder
                value="hello"
                onChange={onChange}
                previewResult={{ text: "preview", charCount: 7, blocked: false, warnings: [] }}
                onPreview={vi.fn()}
                onGenerate={vi.fn()}
                streamPane={<div>stream content</div>}
                renderPane={<div>render content</div>}
                settingsStorageKey="scope-a"
                apiFetch={createApiFetch()}
            />,
        );

        const textarea = screen.getByPlaceholderText("在这里编写 Prompt 模板，可使用 ${material.title}、${subtitle} 等变量。") as HTMLTextAreaElement;
        expect(textarea.value).toBe("hello");

        await user.click(screen.getByRole("tab", { name: "预览" }));
        await user.click(screen.getByRole("tab", { name: "编辑器" }));

        expect((screen.getByPlaceholderText("在这里编写 Prompt 模板，可使用 ${material.title}、${subtitle} 等变量。") as HTMLTextAreaElement).value).toBe("hello");
    });

    it("shows preview and generate actions on preview and stream tabs", async () => {
        const user = userEvent.setup();
        const onPreview = vi.fn();
        const onGenerate = vi.fn();

        render(
            <PromptBuilder
                value="hello"
                onChange={vi.fn()}
                previewResult={{ text: "preview", charCount: 7, blocked: false, warnings: [] }}
                onPreview={onPreview}
                onGenerate={onGenerate}
                streamPane={<PromptStreamPane text="stream content" />}
                settingsStorageKey="scope-a"
                apiFetch={createApiFetch()}
            />,
        );

        await waitFor(() => {
            expect(screen.getByText("当前使用的模型为 默认聊天模型")).toBeTruthy();
        });

        await user.click(screen.getByRole("tab", { name: "预览" }));
        expect(screen.getByRole("button", { name: "预览" })).toBeTruthy();
        expect(screen.getByRole("button", { name: "生成" })).toBeTruthy();

        await user.click(screen.getByRole("button", { name: "生成" }));
        expect(onGenerate).toHaveBeenCalledTimes(1);

        await user.click(screen.getByRole("tab", { name: "LLM 输出" }));
        expect(screen.getByRole("button", { name: "预览" })).toBeTruthy();
        expect(screen.getByRole("button", { name: "生成" })).toBeTruthy();
    });

    it("shows settings tab and persists selected model by scoped localStorage key", async () => {
        const user = userEvent.setup();
        const onResolvedModelChange = vi.fn();
        window.localStorage.setItem("prompt_builder:scope-a:selectedModelId", "coding_model_id");

        render(
            <PromptBuilder
                value="hello"
                onChange={vi.fn()}
                previewResult={{ text: "preview", charCount: 7, blocked: false, warnings: [] }}
                onPreview={vi.fn()}
                onGenerate={vi.fn()}
                settingsStorageKey="scope-a"
                onResolvedModelChange={onResolvedModelChange}
                apiFetch={createApiFetch({
                    models: [
                        { app_model_id: "chat_model_id", label: "默认聊天模型", notes: "chat note" },
                        { app_model_id: "coding_model_id", label: "默认编码模型", notes: "coding note" },
                    ],
                    availability: {
                        available_count: 2,
                        has_any_available_model: true,
                        selected_model_id: "coding_model_id",
                        selected_model_available: true,
                    },
                })}
            />,
        );

        await waitFor(() => {
            expect(screen.getByText("当前使用的模型为 默认编码模型")).toBeTruthy();
        });
        expect(onResolvedModelChange).toHaveBeenLastCalledWith("coding_model_id");

        await user.click(screen.getByRole("tab", { name: "设置" }));
        const select = screen.getByRole("combobox") as HTMLSelectElement;
        expect(select.value).toBe("coding_model_id");

        await user.selectOptions(select, "chat_model_id");
        expect(window.localStorage.getItem("prompt_builder:scope-a:selectedModelId")).toBe("chat_model_id");
    });

    it("shows host-invalid message when no models are available", async () => {
        render(
            <PromptBuilder
                value="hello"
                onChange={vi.fn()}
                previewResult={{ text: "preview", charCount: 7, blocked: false, warnings: [] }}
                onPreview={vi.fn()}
                onGenerate={vi.fn()}
                settingsStorageKey="scope-a"
                apiFetch={createApiFetch({
                    models: [],
                    availability: {
                        available_count: 0,
                        has_any_available_model: false,
                        selected_model_id: null,
                        selected_model_available: false,
                    },
                })}
            />,
        );

        await waitFor(() => {
            expect(screen.getByRole("tab", { name: "⚠ 设置" })).toBeTruthy();
            expect(screen.getByText("服主配置无效")).toBeTruthy();
        });
        expect(screen.getAllByRole("button", { name: "生成" })[0].hasAttribute("disabled")).toBe(true);
    });


    it("reports error when model list load fails and keeps degraded UI", async () => {
        const apiFetch = vi.fn(async (path: string) => {
            if (path === "/api/v1/LlmModelListRoute") throw new Error("model list failed");
            if (path.startsWith("/api/v1/LlmModelAvailabilityRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: {
                        available_count: 0,
                        has_any_available_model: false,
                        selected_model_id: null,
                        selected_model_available: false,
                    },
                };
            }
            if (path.startsWith("/api/v1/PromptTemplateDetailRoute")) {
                return { resp: new Response("{}", { status: 200 }), data: null };
            }
            throw new Error(`unexpected path: ${path}`);
        }) as ApiFetchFn;

        render(
            <PromptBuilder
                value="hello"
                onChange={vi.fn()}
                previewResult={{ text: "preview", charCount: 7, blocked: false, warnings: [] }}
                onPreview={vi.fn()}
                onGenerate={vi.fn()}
                settingsStorageKey="scope-a"
                apiFetch={apiFetch}
            />,
        );

        await waitFor(() => {
            expect(printError).toHaveBeenCalledWith(expect.objectContaining({ reason: "加载模型列表失败" }));
            expect(screen.getByRole("tab", { name: "⚠ 设置" })).toBeTruthy();
        });
    });

    it("reports error when availability load fails and keeps degraded UI", async () => {
        const apiFetch = vi.fn(async (path: string) => {
            if (path === "/api/v1/LlmModelListRoute") {
                return {
                    resp: new Response("[]", { status: 200 }),
                    data: [{ app_model_id: "chat_model_id", label: "默认聊天模型", notes: "note" }],
                };
            }
            if (path.startsWith("/api/v1/LlmModelAvailabilityRoute")) throw new Error("availability failed");
            if (path.startsWith("/api/v1/PromptTemplateDetailRoute")) {
                return { resp: new Response("{}", { status: 200 }), data: null };
            }
            throw new Error(`unexpected path: ${path}`);
        }) as ApiFetchFn;

        render(
            <PromptBuilder
                value="hello"
                onChange={vi.fn()}
                previewResult={{ text: "preview", charCount: 7, blocked: false, warnings: [] }}
                onPreview={vi.fn()}
                onGenerate={vi.fn()}
                settingsStorageKey="scope-a"
                apiFetch={apiFetch}
            />,
        );

        await waitFor(() => {
            expect(printError).toHaveBeenCalledWith(expect.objectContaining({ reason: "检查模型可用性失败" }));
            expect(screen.getByRole("tab", { name: "⚠ 设置" })).toBeTruthy();
        });
    });
});
