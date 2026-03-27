import type { BuildPromptResult } from "~/util/prompt-builder/types";
import { PromptPaneShell } from "./PromptPaneShell";

function WarningList({ warnings }: { warnings: BuildPromptResult["warnings"] }) {
    if (warnings.length === 0) return null;

    return (
        <div className="px-4 py-3 border-b border-amber-100 bg-amber-50">
            <div className="text-xs font-medium text-amber-800">以下变量当前不可完整展开：</div>
            <ul className="mt-2 space-y-1 text-xs text-amber-700 list-disc pl-4">
                {warnings.map((warning, index) => (
                    <li key={`${warning.key}-${index}`}>
                        <span className="font-medium">{warning.key}</span>
                        <span className="ml-1">{warning.reason}</span>
                    </li>
                ))}
            </ul>
        </div>
    );
}

export function PromptPreviewPane({
    result,
    loading = false,
    actions,
}: {
    result: BuildPromptResult | null;
    loading?: boolean;
    actions?: React.ReactNode;
}) {
    return (
        <PromptPaneShell title="Prompt 预览" actions={actions}>
            <div className="h-full flex flex-col min-h-[360px]">
                {loading && (
                    <div className="px-4 py-3 border-b border-gray-100 text-sm text-gray-500">正在构建预览…</div>
                )}
                {result ? <WarningList warnings={result.warnings} /> : null}
                <div className="flex-1 overflow-auto p-4">
                    {result ? (
                        <pre className="whitespace-pre-wrap break-words text-sm leading-6 text-gray-800 font-mono">{result.text}</pre>
                    ) : (
                        <div className="text-sm text-gray-400">点击“预览”后，在这里查看展开后的 prompt。</div>
                    )}
                </div>
            </div>
        </PromptPaneShell>
    );
}
