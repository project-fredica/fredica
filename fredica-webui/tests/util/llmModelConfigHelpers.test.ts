import { describe, expect, it } from "vitest";
import {
    getModelValidationErrors,
    mergeQueryResult,
    normalizeCapabilities,
    type LlmModelProbeResponse,
} from "../../app/util/llmModelConfigHelpers";
import { parseJsonFromText } from "../../app/util/llm";

describe("llmModelConfig helpers", () => {
    it("validates required display name and api key", () => {
        const model = {
            name: "",
            api_key: "",
            base_url: "https://example.com/v1",
            model: "gpt-4o-mini",
        };

        expect(getModelValidationErrors(model)).toEqual({
            name: "请填写显示名称",
            api_key: "请填写 API Key",
        });
    });

    it("parses json payload from fenced text", () => {
        const parsed = parseJsonFromText('```json\n{"context_window":128000}\n```');
        expect(parsed).toEqual({ context_window: 128000 });
    });

    it("normalizes supported capabilities only", () => {
        expect(normalizeCapabilities(["VISION", "BAD", "MCP", 1])).toEqual(["VISION", "MCP"]);
    });

    it("prefers provider values and keeps model-only missing fields", () => {
        const probe: LlmModelProbeResponse = {
            provider_confirmed: true,
            model_exists: true,
            capabilities: ["VISION"],
            context_window: 64000,
            max_output_tokens: null,
            temperature: null,
            warnings: ["warn"],
            provider_notes: "provider",
        };

        const result = mergeQueryResult(probe, {
            capabilities: ["VISION", "FUNCTION_CALLING"],
            context_window: 128000,
            max_output_tokens: 8192,
        });

        expect(result.capabilities).toEqual([
            { value: "VISION", source: "provider" },
            { value: "FUNCTION_CALLING", source: "model" },
        ]);
        expect(result.context_window).toEqual({ value: 64000, source: "provider" });
        expect(result.max_output_tokens).toEqual({ value: 8192, source: "model" });
        expect(result.warnings).toEqual(["warn"]);
        expect(result.provider_notes).toBe("provider");
    });
});
