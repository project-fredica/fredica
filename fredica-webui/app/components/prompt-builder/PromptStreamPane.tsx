import { useEffect, useRef } from "react";
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
        <PromptPaneShell title="LLM 输出" actions={actions}>
            <div
                ref={containerRef}
                onScroll={handleScroll}
                className="h-full min-h-[360px] overflow-auto p-4 space-y-3"
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
        </PromptPaneShell>
    );
}


