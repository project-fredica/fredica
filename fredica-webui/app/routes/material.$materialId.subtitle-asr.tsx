import { useState, useEffect } from "react";
import { ArrowLeft, Download, Loader, AlertTriangle } from "lucide-react";
import { Link, useSearchParams } from "react-router";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { useAppFetch } from "~/util/app_fetch";
import { useFloatingPlayerCtx } from "~/context/floatingPlayer";
import { CommonSubtitlePanel, type CommonSubtitleItem } from "~/components/subtitle/CommonSubtitlePanel";
import { convertToSrt, downloadSrt } from "~/util/subtitleExport";
import { print_error } from "~/util/error_handler";

// ─── Types ───────────────────────────────────────────────────────────────────

interface AsrSubtitleResponse {
    segments: Array<{ from: number; to: number; content: string }>;
    language?: string | null;
    model_size?: string | null;
    segment_count: number;
    total_chunks: number;
    done_chunks: number;
    partial: boolean;
}

// ─── Page ────────────────────────────────────────────────────────────────────

export default function SubtitleAsrPage() {
    const { material } = useWorkspaceContext();
    const { apiFetch } = useAppFetch();
    const { openFloatingPlayer } = useFloatingPlayerCtx();
    const [searchParams] = useSearchParams();
    const modelSize = searchParams.get("model_size") || "large-v3";

    const [data, setData] = useState<AsrSubtitleResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        setError(null);
        apiFetch<AsrSubtitleResponse>(
            `/api/v1/MaterialAsrSubtitleRoute?material_id=${encodeURIComponent(material.id)}&model_size=${encodeURIComponent(modelSize)}`,
            { method: "GET" },
            { silent: true },
        )
            .then(({ data: d }) => {
                if (cancelled) return;
                if (d) setData(d);
            })
            .catch(e => {
                if (!cancelled) {
                    print_error({ reason: "加载 ASR 字幕失败", err: e });
                    setError("加载失败，请检查服务器连接。");
                }
            })
            .finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [material.id, modelSize]);

    const handleSeek = (seconds: number) => {
        openFloatingPlayer(material.id, seconds);
    };

    const handleExport = () => {
        if (!data || data.segments.length === 0) return;
        const srt = convertToSrt(data.segments);
        const lang = data.language || "und";
        const model = data.model_size || "unknown";
        const src = material.source_id
            ? `${material.source_type}__${material.source_id}`
            : material.id;
        downloadSrt(srt, `${src}_asr_${lang}_${model}.srt`);
    };

    const items: CommonSubtitleItem[] = data?.segments ?? [];

    return (
        <div className="max-w-2xl mx-auto p-4 sm:p-6 flex flex-col gap-4 h-full">

            {/* Breadcrumb */}
            <div className="flex items-center gap-2">
                <Link
                    to="../subtitle"
                    relative="path"
                    className="flex items-center gap-1.5 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                >
                    <ArrowLeft className="w-3.5 h-3.5" />
                    字幕提取
                </Link>
                <span className="text-xs text-gray-300">/</span>
                <span className="text-xs text-gray-500 font-medium">ASR 识别字幕</span>
            </div>

            {/* Loading */}
            {loading && (
                <div className="flex-1 flex items-center justify-center gap-2 text-gray-400">
                    <Loader className="w-4 h-4 animate-spin" />
                    <span className="text-sm">加载 ASR 字幕…</span>
                </div>
            )}

            {/* Error */}
            {error && !loading && (
                <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center py-16">
                    <p className="text-sm font-medium text-red-600">{error}</p>
                    <Link
                        to="../subtitle"
                        relative="path"
                        className="mt-2 text-xs text-violet-600 hover:text-violet-800 transition-colors"
                    >
                        ← 返回字幕提取
                    </Link>
                </div>
            )}

            {/* Empty */}
            {!loading && !error && items.length === 0 && (
                <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center py-16">
                    <p className="text-sm font-medium text-gray-600">暂无 ASR 转录结果</p>
                    <p className="text-xs text-gray-400">请先在字幕提取页面启动语音识别</p>
                    <Link
                        to="../subtitle"
                        relative="path"
                        className="mt-2 text-xs text-violet-600 hover:text-violet-800 transition-colors"
                    >
                        ← 返回字幕提取
                    </Link>
                </div>
            )}

            {/* Content */}
            {!loading && !error && data && items.length > 0 && (
                <>
                    {/* Partial warning */}
                    {data.partial && (
                        <div className="flex items-start gap-2 p-3 bg-amber-50 rounded-lg border border-amber-200">
                            <AlertTriangle className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
                            <p className="text-sm text-amber-700">
                                转录未完成（已完成 {data.done_chunks}/{data.total_chunks} 段）
                            </p>
                        </div>
                    )}

                    {/* Meta info + export */}
                    <div className="flex items-center justify-between flex-wrap gap-2">
                        <div className="flex items-center gap-3 text-xs text-gray-500">
                            {data.model_size && (
                                <span className="px-1.5 py-0.5 bg-violet-50 text-violet-600 rounded font-medium">
                                    {data.model_size}
                                </span>
                            )}
                            {data.language && (
                                <span>语言: {data.language}</span>
                            )}
                            <span>{data.segment_count} 段</span>
                            {data.total_chunks > 1 && (
                                <span>Chunks: {data.done_chunks}/{data.total_chunks}</span>
                            )}
                        </div>
                        <button
                            onClick={handleExport}
                            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-violet-600 bg-violet-50 hover:bg-violet-100 rounded-lg transition-colors"
                        >
                            <Download className="w-3.5 h-3.5" />
                            导出 SRT
                        </button>
                    </div>

                    {/* Subtitle panel */}
                    <div className="flex-1 bg-white rounded-xl border border-gray-200 p-4 sm:p-5 flex flex-col min-h-0">
                        <CommonSubtitlePanel
                            items={items}
                            onSeek={handleSeek}
                        />
                    </div>
                </>
            )}
        </div>
    );
}
