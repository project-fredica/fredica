import { useCallback, useEffect, useRef, useState } from "react";
import { Copy, Check } from "lucide-react";
import { PromptPaneShell } from "./PromptPaneShell";

function CopyButton({ text }: { text: string }) {
    const [copied, setCopied] = useState(false);
    const timerRef = useRef<ReturnType<typeof setTimeout>>(undefined);

    const handleCopy = useCallback(() => {
        navigator.clipboard.writeText(text).then(() => {
            setCopied(true);
            clearTimeout(timerRef.current);
            timerRef.current = setTimeout(() => setCopied(false), 2000);
        });
    }, [text]);

    useEffect(() => () => clearTimeout(timerRef.current), []);

    if (!text) return null;

    return (
        <button
            type="button"
            onClick={handleCopy}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg border border-gray-200 bg-white text-gray-600 hover:bg-gray-50 transition-colors"
        >
            {copied ? <><Check className="w-3.5 h-3.5 text-green-500" />已复制</> : <><Copy className="w-3.5 h-3.5" />复制</>}
        </button>
    );
}

export interface SegmentProgress {
    index: number;
    total: number;
}

export function PromptStreamPane({
    text,
    error,
    actions,
    segmentProgress,
}: {
    text: string;
    error?: string | null;
    actions?: React.ReactNode;
    segmentProgress?: SegmentProgress | null;
}) {
    const containerRef = useRef<HTMLDivElement>(null);
    const userScrolledRef = useRef(false);
    const textLengthRef = useRef(0);

    useEffect(() => {
        if (!text) {
            userScrolledRef.current = false;
            textLengthRef.current = 0;
            return;
        }

        const isNewContent = text.length > textLengthRef.current;
        textLengthRef.current = text.length;

        if (!isNewContent || userScrolledRef.current) return;

        requestAnimationFrame(() => {
            if (containerRef.current && !userScrolledRef.current) {
                containerRef.current.scrollTop = containerRef.current.scrollHeight;
            }
        });
    }, [text]);

    const handleScroll = () => {
        if (!containerRef.current) return;
        const { scrollTop, scrollHeight, clientHeight } = containerRef.current;
        const distanceFromBottom = scrollHeight - scrollTop - clientHeight;
        userScrolledRef.current = distanceFromBottom > 100;
    };

    return (
        <PromptPaneShell title="LLM 输出" actions={<>{text ? <CopyButton text={text} /> : null}{actions}</>}>
            <div className="h-full flex flex-col min-h-[360px]">
                {segmentProgress && segmentProgress.total > 1 ? (
                    <div className="px-4 py-2 border-b border-violet-100 bg-violet-50 flex items-center gap-3">
                        <span className="text-xs font-medium text-violet-700">
                            分段 {segmentProgress.index + 1}/{segmentProgress.total}
                        </span>
                        <div className="flex-1 h-1.5 bg-violet-100 rounded-full overflow-hidden">
                            <div
                                className="h-full bg-violet-500 rounded-full transition-all duration-300"
                                style={{ width: `${((segmentProgress.index + 1) / segmentProgress.total) * 100}%` }}
                            />
                        </div>
                    </div>
                ) : null}
                <div
                    ref={containerRef}
                    onScroll={handleScroll}
                    className="flex-1 min-h-0 overflow-auto p-4 space-y-3"
                >
                    {error ? (
                        <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
                    ) : null}
                    {text ? (
                        <pre className="whitespace-pre-wrap break-words text-sm leading-6 text-gray-800 font-mono">{text}</pre>
                    ) : (
                        <div className="text-sm text-gray-400">点击"生成"后，在这里查看 LLM 的原始流式输出。</div>
                    )}
                </div>
            </div>
        </PromptPaneShell>
    );
}


