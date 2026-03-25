import type { BuildPromptResult, VariableResolution, VariableResolver } from "./types";

const VARIABLE_PATTERN = /\$\{([\w.]+)\}/g;

export function extractPromptVariableKeys(template: string): string[] {
    return [...new Set([...template.matchAll(VARIABLE_PATTERN)].map(match => match[1]))];
}

function getResolutionReason(resolution?: VariableResolution): string {
    return resolution?.unavailableReason ?? "不可用";
}

export async function buildPrompt(
    template: string,
    resolver: VariableResolver,
    options: { mode: "preview" | "submit" },
): Promise<BuildPromptResult> {
    const keys = extractPromptVariableKeys(template);
    const resolved = new Map<string, VariableResolution>();

    await Promise.allSettled(
        keys.map(async key => {
            const result = await resolver(key);
            resolved.set(key, result);
        }),
    );

    const warnings: BuildPromptResult["warnings"] = [];
    const text = template.replace(VARIABLE_PATTERN, (_, key: string) => {
        const resolution = resolved.get(key);
        if (!resolution || resolution.status !== "ok") {
            const reason = getResolutionReason(resolution);
            warnings.push({ key, reason });
            return options.mode === "preview" ? `[${key}: ${reason}]` : "";
        }
        return resolution.kind === "slot"
            ? (resolution.preview ?? resolution.value ?? "")
            : (resolution.value ?? "");
    });

    return {
        text,
        charCount: text.length,
        blocked: options.mode === "submit" && warnings.length > 0,
        warnings,
    };
}
