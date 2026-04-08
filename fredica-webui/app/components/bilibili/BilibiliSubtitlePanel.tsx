import { useEffect, useRef, useState } from "react";
import { Loader, Captions, RefreshCw, ChevronDown, ChevronUp, AlertCircle, Download } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { CommonSubtitlePanel, type CommonSubtitleItem } from "~/components/subtitle/CommonSubtitlePanel";
import { usePlaybackTime } from "~/hooks/usePlaybackTime";
import { convertToSrt, downloadSrt } from "~/util/subtitleExport";

// ─── Types ────────────────────────────────────────────────────────────────────

export interface SubtitleMetaItem {
    lan: string;
    lan_doc: string;
    subtitle_url: string;
    type: number;
    id: number;
    id_str: string;
    is_lock: boolean;
    author_mid: number;
    author_name?: string;
}

export interface SubtitleMetaResult {
    code: number;
    message?: string;
    allow_submit?: boolean;
    subtitles: SubtitleMetaItem[] | null;
}

interface SubtitleBodyItem {
    from: number;
    to: number;
    content: string;
}

interface SubtitleBodyResult {
    code: number;
    message?: string;
    body: SubtitleBodyItem[] | null;
}

// ─── SubtitleBodyPanel ────────────────────────────────────────────────────────

function SubtitleBodyPanel({ materialId, selectedLan, metaItem, isUpdate, currentTime, onSeek }: {
    materialId?: string;
    selectedLan?: string | null;
    metaItem: SubtitleMetaItem;
    isUpdate: boolean;
    currentTime: number;
    onSeek?: (seconds: number) => void;
}) {
    const { apiFetch } = useAppFetch();
    const [loading, setLoading] = useState(true);
    const [result, setResult] = useState<SubtitleBodyResult | null>(null);
    const abortRef = useRef<AbortController | null>(null);

    useEffect(() => {
        abortRef.current?.abort();
        const abort = new AbortController();
        abortRef.current = abort;
        let cancelled = false;

        setLoading(true);
        setResult(null);
        apiFetch<SubtitleBodyResult>("/api/v1/BilibiliVideoSubtitleBodyRoute", {
            method: "POST",
            body: JSON.stringify({ subtitle_url: metaItem.subtitle_url, is_update: isUpdate }),
        }, { timeout: 5 * 60 * 1000, signal: abort.signal })
            .then(({ data }) => { if (!cancelled) setResult(data); })
            .catch((err) => {
                if (!cancelled && err?.name !== "AbortError")
                    setResult({ code: -1, message: "请求失败", body: null });
            })
            .finally(() => { if (!cancelled) setLoading(false); });

        return () => { cancelled = true; abort.abort(); };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [metaItem.subtitle_url, isUpdate]);

    if (loading) return (
        <div className="flex items-center justify-center py-10 text-gray-400 gap-2">
            <Loader className="w-4 h-4 animate-spin" />
            <span className="text-sm">加载字幕内容…</span>
        </div>
    );
    if (!result || result.code !== 0 || !result.body) return (
        <div className="py-6 text-center text-sm text-gray-400">{result?.message || "加载失败"}</div>
    );
    if (result.body.length === 0) return (
        <div className="py-6 text-center text-sm text-gray-400">该字幕无内容</div>
    );

    const items: CommonSubtitleItem[] = result.body;
    const downloadFilename = `${materialId ?? "subtitle"}_${selectedLan ?? metaItem.lan}.srt`;

    return (
        <div className="flex flex-col min-h-0 flex-1 gap-3">
            <div className="flex items-center justify-end">
                <button
                    onClick={() => downloadSrt(convertToSrt(result.body ?? []), downloadFilename)}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-gray-200 bg-white px-3 py-1.5 text-xs font-medium text-gray-600 transition-colors hover:bg-gray-50"
                    title="导出当前字幕轨为 SRT"
                >
                    <Download className="w-3.5 h-3.5" />
                    导出 SRT
                </button>
            </div>
            <CommonSubtitlePanel items={items} currentTime={currentTime} onSeek={onSeek} />
        </div>
    );
}

// ─── BilibiliSubtitlePanel ────────────────────────────────────────────────────
//
// 核心内容面板，不含弹窗 chrome（可嵌入页面或包装成 Modal）。
// 使用方提供 flex 容器，面板内部用 flex-col + min-h-0 撑满。

export function BilibiliSubtitlePanel({
    materialId,
    bvid,
    pageIndex = 0,
    onSeek,
}: {
    /** 素材 ID，用于订阅播放进度 BroadcastChannel（自动跟随字幕） */
    materialId?: string;
    bvid: string;
    pageIndex?: number;
    /** 点击字幕行时触发，参数为该行的起始时间（秒） */
    onSeek?: (seconds: number) => void;
}) {
    const { apiFetch } = useAppFetch();
    const [metaLoading, setMetaLoading] = useState(true);
    const [metaResult, setMetaResult] = useState<SubtitleMetaResult | null>(null);
    const [selectedLan, setSelectedLan] = useState<string | null>(null);
    const [refreshCount, setRefreshCount] = useState(0);
    const [showMeta, setShowMeta] = useState(false);

    const currentTime = usePlaybackTime(materialId);

    useEffect(() => {
        let cancelled = false;
        setMetaLoading(true);
        setMetaResult(null);
        setSelectedLan(null);
        apiFetch<SubtitleMetaResult>("/api/v1/BilibiliVideoSubtitleRoute", {
            method: "POST",
            body: JSON.stringify({ bvid, page_index: pageIndex, is_update: refreshCount > 0 }),
        }, { timeout: 5 * 60 * 1000 })
            .then(({ data }) => {
                if (!cancelled) {
                    setMetaResult(data);
                    if (data && data.subtitles && data.subtitles.length > 0) setSelectedLan(data.subtitles[0].lan);
                }
            })
            .catch(() => {
                if (!cancelled) setMetaResult({ code: -1, message: "请求失败", subtitles: null });
            })
            .finally(() => { if (!cancelled) setMetaLoading(false); });

        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [bvid, pageIndex, refreshCount]);

    const tracks = metaResult?.subtitles ?? [];
    const currentTrack = tracks.find(t => t.lan === selectedLan) ?? tracks[0] ?? null;
    const isUpdate = refreshCount > 0;

    return (
        <div className="flex flex-col min-h-0 h-full">

            {/* Toolbar */}
            <div className="flex items-center gap-2 px-1 pb-3 flex-shrink-0">
                <Captions className="w-4 h-4 text-blue-500" />
                <span className="text-sm font-semibold text-gray-700 flex-1">B站字幕</span>
                <span className="text-xs text-gray-400 font-mono">{bvid}</span>
                <button
                    onClick={() => setRefreshCount(c => c + 1)}
                    disabled={metaLoading}
                    className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors disabled:opacity-40"
                    title="重新从 B站拉取"
                >
                    <RefreshCw className={`w-3.5 h-3.5 text-gray-500 ${metaLoading ? "animate-spin" : ""}`} />
                </button>
            </div>

            {/* Language selector */}
            {!metaLoading && tracks.length > 0 && (
                <div className="flex gap-1.5 pb-3 flex-shrink-0 flex-wrap">
                    {tracks.map(t => (
                        <button
                            key={t.lan}
                            onClick={() => setSelectedLan(t.lan)}
                            className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                                selectedLan === t.lan
                                    ? "bg-blue-500 text-white"
                                    : "bg-gray-100 text-gray-600 hover:bg-gray-200"
                            }`}
                        >
                            {t.lan_doc || t.lan}
                            {t.type === 1 && (
                                <span className="ml-1 opacity-60">AI</span>
                            )}
                        </button>
                    ))}
                </div>
            )}

            {/* States */}
            {metaLoading && (
                <div className="flex items-center justify-center py-10 text-gray-400 gap-2 flex-1">
                    <Loader className="w-4 h-4 animate-spin" />
                    <span className="text-sm">获取字幕列表…</span>
                </div>
            )}

            {!metaLoading && metaResult?.code !== 0 && (
                <div className="py-8 flex flex-col items-center gap-3 text-center flex-1">
                    <AlertCircle className="w-8 h-8 text-red-400" />
                    <p className="text-sm font-medium text-red-500">{metaResult?.message || "获取失败"}</p>
                    {metaResult?.message === "账号未登录" && (
                        <p className="text-xs text-gray-400 max-w-xs">
                            请前往<span className="font-medium text-gray-500">设置 → 账号</span>配置 B 站账号 Cookie。
                        </p>
                    )}
                </div>
            )}

            {!metaLoading && metaResult?.code === 0 && tracks.length === 0 && (
                <div className="py-8 text-center text-sm text-gray-400 flex-1">该视频暂无字幕</div>
            )}

            {/* Subtitle body */}
            {!metaLoading && currentTrack && (
                <SubtitleBodyPanel
                    key={currentTrack.lan}
                    materialId={materialId}
                    selectedLan={selectedLan}
                    metaItem={currentTrack}
                    isUpdate={isUpdate}
                    currentTime={currentTime}
                    onSeek={onSeek}
                />
            )}

            {/* Meta section */}
            {!metaLoading && metaResult != null && (
                <div className="mt-3 flex-shrink-0 border-t border-gray-100 pt-2">
                    <button
                        onClick={() => setShowMeta(v => !v)}
                        className="flex items-center gap-1 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                    >
                        {showMeta ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
                        字幕元信息
                        {metaResult.allow_submit != null && (
                            <span className="ml-1 text-gray-300">· allow_submit: {String(metaResult.allow_submit)}</span>
                        )}
                    </button>
                    {showMeta && tracks.length > 0 && (
                        <div className="mt-2 space-y-2">
                            {tracks.map(t => (
                                <div key={t.lan} className="text-xs bg-gray-50 rounded-lg p-3 space-y-1 text-gray-600">
                                    <div className="flex gap-4 flex-wrap">
                                        <span><span className="text-gray-400">lan:</span> {t.lan}</span>
                                        <span><span className="text-gray-400">lan_doc:</span> {t.lan_doc}</span>
                                        <span><span className="text-gray-400">type:</span> {t.type === 1 ? "1 (AI)" : "0 (人工)"}</span>
                                        <span><span className="text-gray-400">id:</span> {t.id_str || t.id}</span>
                                        <span><span className="text-gray-400">is_lock:</span> {String(t.is_lock)}</span>
                                        {t.author_mid > 0 && (
                                            <span><span className="text-gray-400">author_mid:</span> {t.author_mid}</span>
                                        )}
                                        {t.author_name && (
                                            <span><span className="text-gray-400">author:</span> {t.author_name}</span>
                                        )}
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                    {showMeta && tracks.length === 0 && (
                        <p className="mt-1 text-xs text-gray-400">无字幕轨道</p>
                    )}
                </div>
            )}
        </div>
    );
}
