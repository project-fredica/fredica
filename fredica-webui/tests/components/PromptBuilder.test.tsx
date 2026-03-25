import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { PromptBuilder } from "~/components/prompt-builder/PromptBuilder";

const variableResolver = async () => ({ kind: "text" as const, status: "ok" as const, value: "value" });

describe("PromptBuilder", () => {
    it("keeps textarea value when switching tabs", async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();

        render(
            <PromptBuilder
                value="hello"
                onChange={onChange}
                variableResolver={variableResolver}
                previewResult={{ text: "preview", charCount: 7, blocked: false, warnings: [] }}
                onPreview={vi.fn()}
                onGenerate={vi.fn()}
                streamPane={<div>stream content</div>}
                renderPane={<div>render content</div>}
            />,
        );

        const textarea = screen.getByPlaceholderText("在这里编写 Prompt 模板，可使用 ${material.title}、${subtitle} 等变量。") as HTMLTextAreaElement;
        expect(textarea.value).toBe("hello");

        await user.click(screen.getByRole("tab", { name: "预览" }));
        await user.click(screen.getByRole("tab", { name: "编辑器" }));

        expect((screen.getByPlaceholderText("在这里编写 Prompt 模板，可使用 ${material.title}、${subtitle} 等变量。") as HTMLTextAreaElement).value).toBe("hello");
    });

    it("disables render tab when renderDisabled=true", () => {
        render(
            <PromptBuilder
                value="hello"
                onChange={vi.fn()}
                variableResolver={variableResolver}
                previewResult={null}
                onPreview={vi.fn()}
                onGenerate={vi.fn()}
                renderDisabled
            />,
        );

        expect(screen.getByRole("tab", { name: "组件渲染" }).hasAttribute("disabled")).toBe(true);
    });
});
