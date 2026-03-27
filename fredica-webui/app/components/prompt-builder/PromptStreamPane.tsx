import { PromptPaneShell } from "./PromptPaneShell";

export function PromptStreamPane({
    text,
    error,
    actions,
}: {
    text: string;
    error?: string | null;
    actions?: React.ReactNode;
}) {
    return (
        <PromptPaneShell title="LLM 输出" actions={actions}>
            <div className="h-full min-h-[360px] overflow-auto p-4 space-y-3">
                {error ? (
                    <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
                ) : null}
                {text ? (
                    <pre className="whitespace-pre-wrap break-words text-sm leading-6 text-gray-800 font-mono">{text}</pre>
                ) : (
                    <div className="text-sm text-gray-400">点击“生成”后，在这里查看 LLM 的原始流式输出。</div>
                )}
            </div>
        </PromptPaneShell>
    );
}
