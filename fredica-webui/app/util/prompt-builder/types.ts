export interface VariableMeta {
    key: string;
    label: string;
    description: string;
    kind: "text" | "slot";
    required?: boolean;
}

export interface VariableResolution {
    kind: "text" | "slot";
    status: "ok" | "unavailable" | "unimplemented";
    value?: string;
    preview?: string;
    charCount?: number;
    unavailableReason?: string;
}

export type VariableResolver = (key: string) => Promise<VariableResolution>;

export interface BuildPromptWarning {
    key: string;
    reason: string;
}

export interface BuildPromptResult {
    text: string;
    charCount: number;
    blocked: boolean;
    warnings: BuildPromptWarning[];
}

export interface PromptResolverContext {
    signal?: AbortSignal;
}

export type PromptResolver = (
    key: string,
    context?: PromptResolverContext,
) => Promise<VariableResolution>;
