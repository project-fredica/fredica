import { describe, expect, it } from "vitest";
import { buildPrompt, extractPromptVariableKeys } from "~/util/prompt-builder/buildPrompt";

describe("buildPrompt", () => {
    it("extracts unique variable keys", () => {
        expect(extractPromptVariableKeys("a ${foo} b ${bar} c ${foo}")).toEqual(["foo", "bar"]);
    });

    it("keeps unavailable markers in preview mode", async () => {
        const result = await buildPrompt(
            "hello ${name} ${subtitle}",
            async key => key === "name"
                ? { kind: "text", status: "ok", value: "fredica" }
                : { kind: "text", status: "unavailable", unavailableReason: "暂无字幕" },
            { mode: "preview" },
        );

        expect(result.text).toContain("fredica");
        expect(result.text).toContain("[subtitle: 暂无字幕]");
        expect(result.blocked).toBe(false);
        expect(result.warnings).toEqual([{ key: "subtitle", reason: "暂无字幕" }]);
    });

    it("blocks submit mode when required variables are unavailable", async () => {
        const result = await buildPrompt(
            "hello ${subtitle}",
            async () => ({ kind: "text", status: "unavailable", unavailableReason: "暂无字幕" }),
            { mode: "submit" },
        );

        expect(result.text).toBe("hello ");
        expect(result.blocked).toBe(true);
        expect(result.warnings).toEqual([{ key: "subtitle", reason: "暂无字幕" }]);
    });
});
