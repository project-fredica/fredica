import { useEffect, useRef, useState } from "react";
import { X, Loader, Sparkles, RefreshCw } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";

interface OutlineItem {
    timestamp: number;
    content: string;
}

interface OutlineSection {
    title: string;
    part_outline: OutlineItem[];
}

interface AiConclusionResult {
    code: number;
    message?: string;
    model_result: {
        summary: string;
        outline: OutlineSection[];
    } | null;
}

function formatTimestamp(seconds: number): string {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

export function BilibiliAiConclusionModal({
    bvid,
    pageIndex = 0,
    onClose,
}: {
    bvid: string;
    pageIndex?: number;
    onClose: () => void;
}) {
    const { apiFetch } = useAppFetch();
    const [loading, setLoading] = useState(true);
    const [result, setResult] = useState<AiConclusionResult | null>(null);
    const [rawData, setRawData] = useState<unknown>(null);
    const [showRaw, setShowRaw] = useState(false);
    const [refreshCount, setRefreshCount] = useState(0);

    function setResult2(d: AiConclusionResult) {
        setResult(d)
        if (d.code !== 0 && showRaw === false) {
            setShowRaw(true)
        }
    }

    const abortRef = useRef<AbortController | null>(null);

    useEffect(() => {
        abortRef.current?.abort();
        const abort = new AbortController();
        abortRef.current = abort;

        let cancelled = false;
        setLoading(true);
        apiFetch('/api/v1/BilibiliVideoAiConclusionRoute', {
            method: 'POST',
            body: JSON.stringify({ bvid, page_index: pageIndex, is_update: refreshCount > 0 }),
        }, { signal: abort.signal }).then(({ data }) => {
            if (!cancelled) {
                setRawData(data);
                setResult2(data as AiConclusionResult);
            }
        }).catch((err) => {
            if (!cancelled && err?.name !== 'AbortError') {
                setResult({ code: -1, message: '请求失败', model_result: null });
                setRawData(`${err}`)
            }
        }).finally(() => {
            if (!cancelled) setLoading(false);
        });
        return () => { cancelled = true; abort.abort(); };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [bvid, pageIndex, refreshCount]);

    const hasContent = result?.code === 0 && result.model_result != null;

    return (
        <div
            className="fixed inset-0 z-60 flex items-center justify-center bg-black/30 backdrop-blur-sm"
            onClick={(e) => e.target === e.currentTarget && onClose()}
        >
            <div className="bg-white rounded-xl shadow-xl w-full max-w-lg mx-4 flex flex-col max-h-[80vh]">
                {/* Header */}
                <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100 flex-shrink-0">
                    <div className="flex items-center gap-2">
                        <Sparkles className="w-4 h-4 text-purple-500" />
                        <h2 className="text-base font-semibold text-gray-900">B站 AI 总结</h2>
                        <span className="text-xs text-gray-400 font-mono">{bvid}</span>
                    </div>
                    <div className="flex items-center gap-1">
                        <button
                            onClick={() => setRefreshCount(c => c + 1)}
                            disabled={loading}
                            className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors disabled:opacity-40"
                            title="刷新（重新从 B 站拉取）"
                        >
                            <RefreshCw className={`w-4 h-4 text-gray-500 ${loading ? 'animate-spin' : ''}`} />
                        </button>
                        <button
                            onClick={onClose}
                            className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors"
                        >
                            <X className="w-4 h-4 text-gray-500" />
                        </button>
                    </div>
                </div>

                {/* Body */}
                <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
                    {loading && (
                        <div className="flex items-center justify-center py-10 text-gray-400 gap-2">
                            <Loader className="w-4 h-4 animate-spin" />
                            <span className="text-sm">获取中…</span>
                        </div>
                    )}

                    {!loading && !hasContent && (
                        <div className="py-6 text-center text-sm text-gray-400">
                            该视频暂无 B 站 AI 总结
                        </div>
                    )}

                    {!loading && rawData != null && (
                        <div>
                            <button
                                onClick={() => setShowRaw(v => !v)}
                                className="text-xs text-gray-400 hover:text-gray-600 transition-colors"
                            >
                                {showRaw ? '▲ 收起原始数据' : '▼ 查看原始数据'}
                            </button>
                            {showRaw && (
                                <pre className="mt-2 text-xs bg-gray-50 rounded-lg p-3 overflow-x-auto text-gray-600 leading-relaxed">
                                    {JSON.stringify(rawData, null, 2)}
                                </pre>
                            )}
                        </div>
                    )}

                    {!loading && hasContent && (() => {
                        const { summary, outline } = result!.model_result!;
                        return (
                            <>
                                {/* 概述 */}
                                {summary && (
                                    <div className="bg-purple-50 rounded-lg p-4">
                                        <p className="text-sm text-gray-700 leading-relaxed">{summary}</p>
                                    </div>
                                )}

                                {/* 分段大纲 */}
                                {outline && outline.length > 0 && (
                                    <div className="space-y-3">
                                        {outline.map((section, si) => (
                                            <div key={si}>
                                                <h3 className="text-sm font-semibold text-gray-800 mb-1.5">
                                                    {section.title}
                                                </h3>
                                                <div className="space-y-1">
                                                    {section.part_outline.map((item, ii) => (
                                                        <div key={ii} className="flex gap-2 text-xs text-gray-600">
                                                            <span className="font-mono text-purple-600 shrink-0 w-10">
                                                                {formatTimestamp(item.timestamp)}
                                                            </span>
                                                            <span className="leading-relaxed">{item.content}</span>
                                                        </div>
                                                    ))}
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </>
                        );
                    })()}
                </div>
            </div>
        </div>
    );
}
