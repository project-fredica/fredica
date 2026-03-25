import type { PromptResolver, VariableMeta, VariableResolver, VariableResolution } from "./types";

function buildUnavailable(meta?: VariableMeta, reason?: string): VariableResolution {
    return {
        kind: meta?.kind ?? "text",
        status: "unavailable",
        unavailableReason: reason ?? `变量 ${meta?.key ?? "unknown"} 不可用`,
    };
}

export function createVariableResolver(options: {
    variables: VariableMeta[];
    resolve: PromptResolver;
}): VariableResolver {
    const variableMap = new Map(options.variables.map(variable => [variable.key, variable]));

    return async (key: string) => {
        const meta = variableMap.get(key);
        if (!meta) {
            return buildUnavailable(undefined, `未知变量: ${key}`);
        }

        try {
            const result = await options.resolve(key);
            return {
                ...result,
                kind: result.kind ?? meta.kind,
            };
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            return buildUnavailable(meta, message || `变量 ${key} 解析失败`);
        }
    };
}
