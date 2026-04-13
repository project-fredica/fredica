import { useCallback, useRef } from "react";
import { ChevronLeft, ChevronRight, Copy, Check } from "lucide-react";
import { useState, useEffect } from "react";
import type { BuildPromptResult } from "~/util/prompt-builder/types";
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

function SegmentedPreview({ texts }: { texts: string[] }) {
    const [currentIndex, setCurrentIndex] = useState(0);
    const segmentRefs = useRef<(HTMLDivElement | null)[]>([]);

    // Reset index when texts change
    useEffect(() => { setCurrentIndex(0); }, [texts]);

    const scrollToSegment = useCallback((index: number) => {
        setCurrentIndex(index);
        segmentRefs.current[index]?.scrollIntoView({ behavior: "smooth", block: "start" });
    }, []);

    return (
        <div className="space-y-3">
            {/* Navigation bar */}
            <div className="sticky top-0 z-10 bg-white/95 backdrop-blur-sm border-b border-gray-100 -mx-4 -mt-4 px-4 py-2.5 flex items-center gap-2">
                <span className="text-xs text-gray-500 font-medium">
                    共 {texts.length} 段 · {texts.reduce((s, t) => s + t.length, 0)} 字符
                </span>
                <div className="flex-1" />
                <button
                    type="button"
                    onClick={() => scrollToSegment(Math.max(0, currentIndex - 1))}
                    disabled={currentIndex === 0}
                    className="p-1 rounded text-gray-500 hover:bg-gray-100 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                    title="上一段"
                >
                    <ChevronLeft className="w-4 h-4" />
                </button>
                <div className="flex items-center gap-1">
                    {texts.map((_, index) => (
                        <button
                            key={index}
                            type="button"
                            onClick={() => scrollToSegment(index)}
                            className={`min-w-[24px] h-6 px-1.5 text-xs font-medium rounded transition-colors ${
                                index === currentIndex
                                    ? "bg-violet-100 text-violet-700"
                                    : "text-gray-400 hover:bg-gray-100 hover:text-gray-600"
                            }`}
                        >
                            {index + 1}
                        </button>
                    ))}
                </div>
                <button
                    type="button"
                    onClick={() => scrollToSegment(Math.min(texts.length - 1, currentIndex + 1))}
                    disabled={currentIndex === texts.length - 1}
                    className="p-1 rounded text-gray-500 hover:bg-gray-100 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                    title="下一段"
                >
                    <ChevronRight className="w-4 h-4" />
                </button>
            </div>

            {/* Segments */}
            {texts.map((text, index) => (
                <div
                    key={index}
                    ref={el => { segmentRefs.current[index] = el; }}
                    className="border border-gray-200 rounded-lg overflow-hidden"
                >
                    <div className="px-3 py-1.5 bg-gray-50 border-b border-gray-200 text-xs text-gray-500 font-medium">
                        分段 {index + 1}/{texts.length}
                        <span className="ml-2 text-gray-400">{text.length} 字符</span>
                    </div>
                    <pre className="whitespace-pre-wrap break-words text-sm leading-6 text-gray-800 font-mono p-3">{text}</pre>
                </div>
            ))}
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
    const isSegmented = result?.texts && result.texts.length > 0;
    const copyText = isSegmented ? result!.texts!.join("\n\n") : (result?.text ?? "");

    return (
        <PromptPaneShell title="Prompt 预览" actions={<>{copyText ? <CopyButton text={copyText} /> : null}{actions}</>}>
            <div className="h-full flex flex-col min-h-[360px]">
                {loading && (
                    <div className="px-4 py-3 border-b border-gray-100 text-sm text-gray-500">正在构建预览…</div>
                )}
                {result ? <WarningList warnings={result.warnings} /> : null}
                <div className="flex-1 overflow-auto p-4">
                    {result ? (
                        isSegmented
                            ? <SegmentedPreview texts={result.texts!} />
                            : <pre className="whitespace-pre-wrap break-words text-sm leading-6 text-gray-800 font-mono">{result.text}</pre>
                    ) : (
                        <div className="text-sm text-gray-400">点击"预览"后，在这里查看展开后的 prompt。</div>
                    )}
                </div>
            </div>
        </PromptPaneShell>
    );
}
