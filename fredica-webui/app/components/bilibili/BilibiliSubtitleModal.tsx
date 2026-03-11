import { useEffect, useRef, useState } from "react";
import { X, Loader, Captions, RefreshCw, ChevronDown, ChevronUp, AlertCircle } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";

interface SubtitleMetaItem {
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

interface SubtitleMetaResult {
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

function formatTime(seconds: number): string {
    const m = Math.floor(seconds / 60);
    const s = (seconds % 60).toFixed(1);
    return `${String(m).padStart(2, "0")}:${s.padStart(4, "0")}`;
}

const ITEM_HEIGHT = 52;
const OVERSCAN = 5;

function VirtualSubtitleList({ items }: { items: SubtitleBodyItem[] }) {
    const containerRef = useRef<HTMLDivElement>(null);
    const [scrollTop, setScrollTop] = useState(0);
    const [containerHeight, setContainerHeight] = useState(400);

    useEffect(() => {
        const el = containerRef.current;
        if (!el) return;
        setContainerHeight(el.clientHeight);
        const ro = new ResizeObserver(() => setContainerHeight(el.clientHeight));
        ro.observe(el);
        return () => ro.disconnect();
    }, []);

    const totalHeight = items.length * ITEM_HEIGHT;
    const startIdx = Math.max(0, Math.floor(scrollTop / ITEM_HEIGHT) - OVERSCAN);
    const endIdx = Math.min(items.length, Math.ceil((scrollTop + containerHeight) / ITEM_HEIGHT) + OVERSCAN);
    const visibleItems = items.slice(startIdx, endIdx);

    return (
        <div
            ref={containerRef}
            className="flex-1 overflow-y-auto min-h-0"
            onScroll={(e) => setScrollTop((e.target as HTMLDivElement).scrollTop)}
        >
            <div style={{ height: totalHeight, position: "relative" }}>
                <div style={{ position: "absolute", top: startIdx * ITEM_HEIGHT, left: 0, right: 0 }}>
                    {visibleItems.map((item, i) => (
                        <div
                            key={startIdx + i}
                            style={{ height: ITEM_HEIGHT }}
                            className="flex gap-3 items-start px-1 py-2 border-b border-gray-50 last:border-0"
                        >
                            <span className="text-xs text-gray-400 font-mono whitespace-nowrap pt-0.5 w-20 flex-shrink-0">
                                {formatTime(item.from)}
                            </span>
                            <span className="text-sm text-gray-700 leading-snug line-clamp-2">
                                {item.content}
                            </span>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}

function SubtitleBodyPanel({
    metaItem,
    isUpdate,
}: {
    metaItem: SubtitleMetaItem;
    isUpdate: boolean;
}) {
    const { apiFetch } = useAppFetch();
    const [loading, setLoading] = useState(true);
    const [result, setResult] = useState<SubtitleBodyResult | null>(null);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        setResult(null);
        apiFetch("/api/v1/BilibiliVideoSubtitleBodyRoute", {
            method: "POST",
            body: JSON.stringify({ subtitle_url: metaItem.subtitle_url, is_update: isUpdate }),
        }, { timeout: 5 * 60 * 1000 })
            .then(({ data }) => {
                if (!cancelled) setResult(data as SubtitleBodyResult);
            })
            .catch(() => {
                if (!cancelled) setResult({ code: -1, message: "请求失败", body: null });
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [metaItem.subtitle_url, isUpdate]);

    if (loading) {
        return (
            <div className="flex items-center justify-center py-10 text-gray-400 gap-2">
                <Loader className="w-4 h-4 animate-spin" />
                <span className="text-sm">加载字幕内容…</span>
            </div>
        );
    }
    if (!result || result.code !== 0 || !result.body) {
        return (
            <div className="py-6 text-center text-sm text-gray-400">
                {result?.message || "加载失败"}
            </div>
        );
    }
    if (result.body.length === 0) {
        return <div className="py-6 text-center text-sm text-gray-400">该字幕无内容</div>;
    }
    return <VirtualSubtitleList items={result.body} />;
}

export function BilibiliSubtitleModal({
    bvid,
    pageIndex = 0,
    onClose,
}: {
    bvid: string;
    pageIndex?: number;
    onClose: () => void;
}) {
    const { apiFetch } = useAppFetch();
    const [metaLoading, setMetaLoading] = useState(true);
    const [metaResult, setMetaResult] = useState<SubtitleMetaResult | null>(null);
    const [selectedLan, setSelectedLan] = useState<string | null>(null);
    const [refreshCount, setRefreshCount] = useState(0);
    const [showMeta, setShowMeta] = useState(false);

    useEffect(() => {
        let cancelled = false;
        setMetaLoading(true);
        setMetaResult(null);
        setSelectedLan(null);
        apiFetch("/api/v1/BilibiliVideoSubtitleRoute", {
            method: "POST",
            body: JSON.stringify({ bvid, page_index: pageIndex, is_update: refreshCount > 0 }),
        }, { timeout: 60 * 1000 })
            .then(({ data }) => {
                if (!cancelled) {
                    const r = data as SubtitleMetaResult;
                    setMetaResult(r);
                    if (r.subtitles && r.subtitles.length > 0) {
                        setSelectedLan(r.subtitles[0].lan);
                    }
                }
            })
            .catch(() => {
                if (!cancelled) setMetaResult({ code: -1, message: "请求失败", subtitles: null });
            })
            .finally(() => {
                if (!cancelled) setMetaLoading(false);
            });
        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [bvid, pageIndex, refreshCount]);

    const tracks = metaResult?.subtitles ?? [];
    const currentTrack = tracks.find((t) => t.lan === selectedLan) ?? tracks[0] ?? null;
    const isUpdate = refreshCount > 0;

    return (
        <div
            className="fixed inset-0 z-60 flex items-center justify-center bg-black/30 backdrop-blur-sm"
            onClick={(e) => e.target === e.currentTarget && onClose()}
        >
            <div className="bg-white rounded-xl shadow-xl w-full max-w-lg mx-4 flex flex-col max-h-[80vh]">
                {/* Header */}
                <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100 flex-shrink-0">
                    <div className="flex items-center gap-2">
                        <Captions className="w-4 h-4 text-blue-500" />
                        <h2 className="text-base font-semibold text-gray-900">B站字幕</h2>
                        <span className="text-xs text-gray-400 font-mono">{bvid}</span>
                    </div>
                    <div className="flex items-center gap-1">
                        <button
                            onClick={() => setRefreshCount((c) => c + 1)}
                            disabled={metaLoading}
                            className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors disabled:opacity-40"
                            title="刷新（重新从 B 站拉取）"
                        >
                            <RefreshCw className={`w-4 h-4 text-gray-500 ${metaLoading ? "animate-spin" : ""}`} />
                        </button>
                        <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors">
                            <X className="w-4 h-4 text-gray-500" />
                        </button>
                    </div>
                </div>

                {/* Language tabs */}
                {!metaLoading && tracks.length > 0 && (
                    <div className="flex gap-1 px-5 pt-3 flex-shrink-0 flex-wrap">
                        {tracks.map((t) => (
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
                            </button>
                        ))}
                    </div>
                )}

                {/* Body */}
                <div className="flex-1 overflow-hidden flex flex-col px-5 py-3 min-h-0">
                    {metaLoading && (
                        <div className="flex items-center justify-center py-10 text-gray-400 gap-2">
                            <Loader className="w-4 h-4 animate-spin" />
                            <span className="text-sm">获取字幕列表…</span>
                        </div>
                    )}

                    {!metaLoading && metaResult?.code !== 0 && (
                        <div className="py-5 flex flex-col items-center gap-2 text-center">
                            <AlertCircle className="w-8 h-8 text-red-400 flex-shrink-0" />
                            <p className="text-sm font-medium text-red-500">
                                {metaResult?.message || "获取失败"}
                            </p>
                            {metaResult?.message === "账号未登录" && (
                                <p className="text-xs text-gray-400">
                                    请让运维在 <span className="font-medium text-gray-500">服务器设置</span> 中设置 B 站账号。
                                </p>
                            )}
                        </div>
                    )}

                    {!metaLoading && metaResult?.code === 0 && tracks.length === 0 && (
                        <div className="py-6 text-center text-sm text-gray-400">该视频暂无字幕</div>
                    )}

                    {!metaLoading && currentTrack && (
                        <SubtitleBodyPanel
                            key={currentTrack.lan}
                            metaItem={currentTrack}
                            isUpdate={isUpdate}
                        />
                    )}

                    {/* subtitle_meta 详情 */}
                    {!metaLoading && metaResult != null && (
                        <div className="mt-3 flex-shrink-0 border-t border-gray-100 pt-2">
                            <button
                                onClick={() => setShowMeta((v) => !v)}
                                className="flex items-center gap-1 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                            >
                                {showMeta ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
                                字幕元信息
                                {metaResult.allow_submit != null && (
                                    <span className="ml-1 text-gray-300">
                                        · allow_submit: {String(metaResult.allow_submit)}
                                    </span>
                                )}
                            </button>
                            {showMeta && tracks.length > 0 && (
                                <div className="mt-2 space-y-2">
                                    {tracks.map((t) => (
                                        <div key={t.lan} className="text-xs bg-gray-50 rounded-lg p-3 space-y-1 text-gray-600">
                                            <div className="flex gap-4 flex-wrap">
                                                <span><span className="text-gray-400">lan:</span> {t.lan}</span>
                                                <span><span className="text-gray-400">lan_doc:</span> {t.lan_doc}</span>
                                                <span><span className="text-gray-400">type:</span> {t.type}</span>
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
            </div>
        </div>
    );
}
