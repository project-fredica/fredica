import { useState, useEffect } from "react";
import { ArrowLeft, Download, Loader, AlertTriangle, Wand2 } from "lucide-react";
import { Link, useParams, useNavigate } from "react-router";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { useAppFetch } from "~/util/app_fetch";
import { useFloatingPlayerCtx } from "~/context/floatingPlayer";
import { BilibiliSubtitlePanel } from "~/components/bilibili/BilibiliSubtitlePanel";
import { CommonSubtitlePanel, type CommonSubtitleItem } from "~/components/subtitle/CommonSubtitlePanel";
import { convertToSrt, downloadSrt } from "~/util/subtitleExport";
import { parseSrt } from "~/util/srtParser";
import { type BilibiliExtra } from "~/components/material-library/materialTypes";
import { json_parse } from "~/util/json";
import { print_error } from "~/util/error_handler";

// ─── Subtitle ID helpers ────────────────────────────────────────────────────

function parseSubtitleId(id: string): { source: string; key: string } {
    const dot = id.indexOf(".");
    if (dot < 0) return { source: "unknown", key: id };
    return { source: id.slice(0, dot), key: id.slice(dot + 1) };
}

// ─── Bilibili helpers ───────────────────────────────────────────────────────

function getBilibiliInfo(material: { source_type: string; source_id: string; id: string; extra: string }) {
    if (material.source_type !== "bilibili") return null;
    let bvid = material.source_id;
    const ext = json_parse<BilibiliExtra>(material.extra);
    if (ext?.bvid) bvid = ext.bvid;
    const pageMatch = material.id.match(/__P(\d+)$/);
    const pageIndex = pageMatch ? parseInt(pageMatch[1], 10) - 1 : 0;
    return { bvid, pageIndex };
}

// ─── ASR types ──────────────────────────────────────────────────────────────

interface AsrSubtitleResponse {
    segments: Array<{ from: number; to: number; content: string }>;
    language?: string | null;
    model_size?: string | null;
    segment_count: number;
    total_chunks: number;
    done_chunks: number;
    partial: boolean;
}

// ─── Postprocess types ──────────────────────────────────────────────────────

interface SubtitleContentResponse {
    text: string;
    word_count: number;
    segment_count: number;
    source: string;
    subtitle_url: string;
    error?: string;
}

// ─── Bilibili Preview Mode ──────────────────────────────────────────────────

function BilibiliPreviewMode({ lan }: { lan: string | null }) {
    const { material } = useWorkspaceContext();
    const { openFloatingPlayer } = useFloatingPlayerCtx();
    const bilibiliInfo = getBilibiliInfo(material);

    const handleSeek = (seconds: number) => {
        openFloatingPlayer(material.id, seconds);
    };

    if (!bilibiliInfo) {
        return (
            <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center py-16">
                <p className="text-sm font-medium text-gray-600">该素材不是 B站 来源</p>
                <p className="text-xs text-gray-400">仅 Bilibili 素材支持平台字幕查询</p>
                <Link
                    to="../"
                    relative="path"
                    className="mt-2 text-xs text-violet-600 hover:text-violet-800 transition-colors"
                >
                    ← 返回字幕提取
                </Link>
            </div>
        );
    }

    return (
        <div className="flex-1 bg-white rounded-xl border border-gray-200 p-4 sm:p-5 flex flex-col min-h-0">
            <BilibiliSubtitlePanel
                materialId={material.id}
                bvid={bilibiliInfo.bvid}
                pageIndex={bilibiliInfo.pageIndex}
                initialLan={lan ?? undefined}
                onSeek={handleSeek}
            />
        </div>
    );
}

// ─── ASR Preview Mode ───────────────────────────────────────────────────────

function AsrPreviewMode({ modelSize }: { modelSize: string }) {
    const { material } = useWorkspaceContext();
    const { apiFetch } = useAppFetch();
    const { openFloatingPlayer } = useFloatingPlayerCtx();

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
        downloadSrt(srt, `subtitle___${material.id}___asr.${modelSize}.srt`);
    };

    const items: CommonSubtitleItem[] = data?.segments ?? [];

    if (loading) {
        return (
            <div className="flex-1 flex items-center justify-center gap-2 text-gray-400">
                <Loader className="w-4 h-4 animate-spin" />
                <span className="text-sm">加载 ASR 字幕…</span>
            </div>
        );
    }

    if (error) {
        return (
            <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center py-16">
                <p className="text-sm font-medium text-red-600">{error}</p>
                <Link
                    to="../"
                    relative="path"
                    className="mt-2 text-xs text-violet-600 hover:text-violet-800 transition-colors"
                >
                    ← 返回字幕提取
                </Link>
            </div>
        );
    }

    if (items.length === 0) {
        return (
            <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center py-16">
                <p className="text-sm font-medium text-gray-600">暂无 ASR 转录结果</p>
                <p className="text-xs text-gray-400">请先在字幕提取页面启动语音识别</p>
                <Link
                    to="../"
                    relative="path"
                    className="mt-2 text-xs text-violet-600 hover:text-violet-800 transition-colors"
                >
                    ← 返回字幕提取
                </Link>
            </div>
        );
    }

    return (
        <div className="flex flex-col gap-4 flex-1 min-h-0">
            {data!.partial && (
                <div className="flex items-start gap-2 p-3 bg-amber-50 rounded-lg border border-amber-200">
                    <AlertTriangle className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
                    <p className="text-sm text-amber-700">
                        转录未完成（已完成 {data!.done_chunks}/{data!.total_chunks} 段）
                    </p>
                </div>
            )}

            <div className="flex items-center justify-between flex-wrap gap-2">
                <div className="flex items-center gap-3 text-xs text-gray-500">
                    {data!.model_size && (
                        <span className="px-1.5 py-0.5 bg-violet-50 text-violet-600 rounded font-medium">
                            {data!.model_size}
                        </span>
                    )}
                    {data!.language && (
                        <span>语言: {data!.language}</span>
                    )}
                    <span>{data!.segment_count} 段</span>
                    {data!.total_chunks > 1 && (
                        <span>Chunks: {data!.done_chunks}/{data!.total_chunks}</span>
                    )}
                </div>
                <div className="flex items-center gap-2">
                    <button
                        onClick={handleExport}
                        className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-violet-600 bg-violet-50 hover:bg-violet-100 rounded-lg transition-colors"
                    >
                        <Download className="w-3.5 h-3.5" />
                        导出 SRT
                    </button>
                    <Link
                        to={`../../subtitle-asr-postprocess?model_size=${encodeURIComponent(modelSize)}`}
                        relative="path"
                        className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-violet-600 bg-violet-50 hover:bg-violet-100 rounded-lg transition-colors"
                    >
                        <Wand2 className="w-3.5 h-3.5" />
                        后处理
                    </Link>
                </div>
            </div>

            <div className="flex-1 bg-white rounded-xl border border-gray-200 p-4 sm:p-5 flex flex-col min-h-0">
                <CommonSubtitlePanel
                    items={items}
                    materialId={material.id}
                    onSeek={handleSeek}
                />
            </div>
        </div>
    );
}

function PostprocessPreviewMode({ filenameStem }: { filenameStem: string }) {
    const { material } = useWorkspaceContext();
    const { apiFetch } = useAppFetch();
    const { openFloatingPlayer } = useFloatingPlayerCtx();

    const [segments, setSegments] = useState<CommonSubtitleItem[] | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [meta, setMeta] = useState<{ wordCount: number; segmentCount: number } | null>(null);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        setError(null);
        setSegments(null);

        // First, scan subtitle list to find the matching postprocess item's subtitle_url
        apiFetch<Array<{ lan: string; source: string; subtitle_url: string }>>(
            `/api/v1/MaterialSubtitleListRoute?material_id=${encodeURIComponent(material.id)}`,
            { method: "GET" },
            { silent: true },
        ).then(({ data: list }) => {
            if (cancelled) return;
            if (!Array.isArray(list)) { setError("获取字幕列表失败"); return; }

            const match = list.find(item =>
                item.source === "asr_postprocess" &&
                item.subtitle_url.replace(/\\/g, "/").endsWith(`/${filenameStem}.srt`)
            );
            if (!match) { setError(`未找到后处理字幕文件: ${filenameStem}`); return; }

            // Fetch content
            return apiFetch<SubtitleContentResponse>(
                "/api/v1/MaterialSubtitleContentRoute",
                {
                    method: "POST",
                    body: JSON.stringify({
                        subtitle_url: match.subtitle_url,
                        source: "asr_postprocess",
                        is_update: false,
                    }),
                },
            ).then(({ data }) => {
                if (cancelled) return;
                if (data?.error) { setError(data.error); return; }
                if (!data?.text) { setError("字幕内容为空"); return; }
                const parsed = parseSrt(data.text);
                setSegments(parsed);
                setMeta({ wordCount: data.word_count, segmentCount: data.segment_count });
            });
        }).catch(e => {
            if (!cancelled) {
                print_error({ reason: "获取后处理字幕失败", err: e });
                setError("获取字幕内容失败");
            }
        }).finally(() => { if (!cancelled) setLoading(false); });

        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [material.id, filenameStem]);

    const handleSeek = (seconds: number) => {
        openFloatingPlayer(material.id, seconds);
    };

    const handleExport = () => {
        if (!segments || segments.length === 0) return;
        const srt = convertToSrt(segments);
        downloadSrt(srt, `subtitle___${material.id}___pp.${filenameStem}.srt`);
    };

    if (loading) {
        return (
            <div className="flex-1 flex items-center justify-center gap-2 text-gray-400">
                <Loader className="w-4 h-4 animate-spin" />
                <span className="text-sm">加载后处理字幕…</span>
            </div>
        );
    }

    if (error) {
        return (
            <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center py-16">
                <p className="text-sm font-medium text-red-600">{error}</p>
                <Link
                    to="../"
                    relative="path"
                    className="mt-2 text-xs text-violet-600 hover:text-violet-800 transition-colors"
                >
                    ← 返回字幕提取
                </Link>
            </div>
        );
    }

    if (!segments || segments.length === 0) {
        return (
            <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center py-16">
                <p className="text-sm font-medium text-gray-600">后处理字幕内容为空</p>
                <Link
                    to="../"
                    relative="path"
                    className="mt-2 text-xs text-violet-600 hover:text-violet-800 transition-colors"
                >
                    ← 返回字幕提取
                </Link>
            </div>
        );
    }

    return (
        <div className="flex flex-col gap-4 flex-1 min-h-0">
            <div className="flex items-center justify-between flex-wrap gap-2">
                <div className="flex items-center gap-3 text-xs text-gray-500">
                    <span className="px-1.5 py-0.5 bg-emerald-50 text-emerald-600 rounded font-medium">
                        LLM 后处理
                    </span>
                    {meta && (
                        <>
                            <span>{meta.segmentCount} 段</span>
                            <span>{meta.wordCount} 字</span>
                        </>
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

            <div className="flex-1 bg-white rounded-xl border border-gray-200 p-4 sm:p-5 flex flex-col min-h-0">
                <CommonSubtitlePanel
                    items={segments}
                    materialId={material.id}
                    onSeek={handleSeek}
                />
            </div>
        </div>
    );
}

// ─── Source labels ───────────────────────────────────────────────────────────

const SOURCE_BREADCRUMB: Record<string, string> = {
    bili: "B站平台字幕",
    asr: "ASR 识别字幕",
    pp: "LLM 后处理字幕",
};

// ─── Page ───────────────────────────────────────────────────────────────────

export default function SubtitlePreviewPage() {
    const { subtitleId = "" } = useParams<{ subtitleId: string }>();
    const { source, key } = parseSubtitleId(subtitleId);
    const navigate = useNavigate();

    return (
        <div className="max-w-2xl mx-auto p-4 sm:p-6 flex flex-col gap-4 h-full">

            {/* Breadcrumb */}
            <div className="flex items-center gap-2">
                <button
                    onClick={() => navigate(-1)}
                    className="flex items-center gap-1.5 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                >
                    <ArrowLeft className="w-3.5 h-3.5" />
                    字幕提取
                </button>
                <span className="text-xs text-gray-300">/</span>
                <span className="text-xs text-gray-500 font-medium">
                    {SOURCE_BREADCRUMB[source] ?? "字幕预览"}
                </span>
            </div>

            {/* Dispatch by source */}
            {source === "bili" && (
                <BilibiliPreviewMode lan={key === "list" ? null : key} />
            )}
            {source === "asr" && (
                <AsrPreviewMode modelSize={key} />
            )}
            {source === "pp" && (
                <PostprocessPreviewMode filenameStem={key} />
            )}
            {!["bili", "asr", "pp"].includes(source) && (
                <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center py-16">
                    <p className="text-sm font-medium text-gray-600">未知的字幕来源: {source}</p>
                    <Link
                        to="../"
                        relative="path"
                        className="mt-2 text-xs text-violet-600 hover:text-violet-800 transition-colors"
                    >
                        ← 返回字幕提取
                    </Link>
                </div>
            )}
        </div>
    );
}
