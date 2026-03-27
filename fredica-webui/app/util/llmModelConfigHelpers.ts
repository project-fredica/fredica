import type { JsonObject, JsonValue } from "./json";

export type LlmCapability =
    | "VISION"
    | "JSON_SCHEMA"
    | "MCP"
    | "FUNCTION_CALLING"
    | "LONG_CONTEXT";

export interface LlmModelConfigLike {
    name: string;
    api_key: string;
    capabilities?: LlmCapability[] | null;
    context_window?: number;
    max_output_tokens?: number;
    temperature?: number;
}

export interface LlmModelProbeResponse {
    provider_confirmed: boolean;
    model_exists: boolean;
    capabilities: LlmCapability[];
    context_window?: number | null;
    max_output_tokens?: number | null;
    temperature?: number | null;
    warnings: string[];
    provider_notes?: string | null;
    error?: string | null;
}

export interface ModelParamQueryResult {
    capabilities: Array<{ value: LlmCapability; source: "provider" | "model" }>;
    context_window?: { value: number; source: "provider" | "model" } | null;
    max_output_tokens?: { value: number; source: "provider" | "model" } | null;
    temperature?: { value: number; source: "provider" | "model" } | null;
    warnings: string[];
    provider_notes?: string | null;
}

export const ALL_CAPABILITIES: LlmCapability[] = [
    "VISION",
    "JSON_SCHEMA",
    "FUNCTION_CALLING",
    "LONG_CONTEXT",
    "MCP",
];

export function getModelValidationErrors(
    model: LlmModelConfigLike,
): Partial<Record<"name" | "api_key", string>> {
    const errors: Partial<Record<"name" | "api_key", string>> = {};
    if (!model.name?.trim()) errors.name = "请填写显示名称";
    if (!model.api_key?.trim()) errors.api_key = "请填写 API Key";
    return errors;
}

export function normalizeCapabilities(values: unknown): LlmCapability[] {
    if (!Array.isArray(values)) return [];
    return values.filter((v): v is LlmCapability =>
        typeof v === "string" && ALL_CAPABILITIES.includes(v as LlmCapability)
    );
}

export function mergeQueryResult(
    probe: LlmModelProbeResponse,
    supplement: JsonObject,
): ModelParamQueryResult {
    const supplementCaps = normalizeCapabilities(supplement?.capabilities);
    const providerCaps = probe.capabilities ?? [];
    const mergedCaps: Array<
        { value: LlmCapability; source: "provider" | "model" }
    > = [
        ...providerCaps.map((value) => ({
            value,
            source: "provider" as const,
        })),
    ];
    for (const cap of supplementCaps) {
        if (!providerCaps.includes(cap)) {
            mergedCaps.push({ value: cap, source: "model" as const });
        }
    }
    const pickNumber = (
        providerValue: number | null | undefined,
        supplementValue: unknown,
    ) => {
        if (
            typeof providerValue === "number" && Number.isFinite(providerValue)
        ) return { value: providerValue, source: "provider" as const };
        if (
            typeof supplementValue === "number" &&
            Number.isFinite(supplementValue)
        ) return { value: supplementValue, source: "model" as const };
        return null;
    };
    return {
        capabilities: mergedCaps,
        context_window: pickNumber(
            probe.context_window,
            supplement?.context_window,
        ),
        max_output_tokens: pickNumber(
            probe.max_output_tokens,
            supplement?.max_output_tokens,
        ),
        temperature: pickNumber(probe.temperature, supplement?.temperature),
        warnings: probe.warnings ?? [],
        provider_notes: probe.provider_notes ?? null,
    };
}
