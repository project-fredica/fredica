import { type ReactNode, useState, useEffect } from "react";
import { ExternalLink, Download, Play, Eye, Heart, MessageSquare, Check, Loader, Pencil } from "lucide-react";
import { useImageProxyUrl, useAppFetch } from "~/utils/requests";
import { CategoryPickerModal } from "~/components/bilibili/CategoryPickerModal";

export interface MediaItem {
    id: number;
    title: string;
    cover: string;
    intro: string;
    page: number;
    duration: number; // seconds
    upper: {
        name: string;
        face: string;
    };
    cnt_info: {
        collect: number;
        play: number;
        danmaku: number;
        view_text_1: string;
    };
    fav_time: number; // unix timestamp
    bvid: string;
}

// ─── Library state ─────────────────────────────────────────────────────────

interface LibraryEntry {
    /** DB UUID */
    id: string;
    categoryIds: string[];
}

interface MaterialVideoItem {
    id: string;
    source_type: string;
    source_id: string;
    category_ids: string[];
}

// ─── Picker mode ───────────────────────────────────────────────────────────

type PickerMode =
    | { kind: "import"; items: MediaItem[] }
    | { kind: "update"; bvid: string; dbId: string; currentCategoryIds: string[] };

// ─── Helpers ───────────────────────────────────────────────────────────────

function formatDuration(seconds: number): string {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    if (h > 0) {
        return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    }
    return `${m}:${String(s).padStart(2, '0')}`;
}

function formatCount(n: number): string {
    if (n >= 10000) return `${(n / 10000).toFixed(1)}万`;
    return String(n);
}

function formatFavDate(ts: number): string {
    const d = new Date(ts * 1000);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const PAGE_SIZE = 20;

function buildPageWindows(totalPages: number, loadedPage: number): number[] {
    const show = new Set<number>();
    show.add(1);
    show.add(2);
    show.add(totalPages - 1);
    show.add(totalPages);
    for (let d = -2; d <= 2; d++) {
        const p = loadedPage + d;
        if (p >= 1 && p <= totalPages) show.add(p);
    }
    const sorted = Array.from(show).sort((a, b) => a - b);
    const result: number[] = [];
    let prev = 0;
    for (const p of sorted) {
        if (prev > 0 && p > prev + 1) result.push(0);
        result.push(p);
        prev = p;
    }
    return result;
}

// ─── Component ─────────────────────────────────────────────────────────────

export function BilibiliVideoList(param: {
    medias?: MediaItem[];
    nextPageSlot?: ReactNode;
    currentPage?: number;
    totalPages?: number;
    totalCount?: number;
    onJumpToPage?: (page: number) => void;
    pageLoading?: boolean;
    fid?: string;
}) {
    const { medias, nextPageSlot, currentPage, totalPages, totalCount, onJumpToPage, pageLoading, fid } = param;
    const buildProxyUrl = useImageProxyUrl();
    const { apiFetch } = useAppFetch();
    const [selectedBvids, setSelectedBvids] = useState<Set<string>>(new Set());
    const [importingBvids, setImportingBvids] = useState<Set<string>>(new Set());

    // Persistent library state (bvid → DB entry)
    const [libraryMap, setLibraryMap] = useState<Map<string, LibraryEntry>>(new Map());

    // Category picker modal
    const [pickerMode, setPickerMode] = useState<PickerMode | null>(null);

    // ── Library map helpers ──────────────────────────────────────────────

    const fetchLibraryMapData = async (): Promise<Map<string, LibraryEntry>> => {
        try {
            const { resp, data } = await apiFetch('/api/v1/MaterialListRoute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: '{}',
            });
            if (!resp.ok) return new Map();
            const map = new Map<string, LibraryEntry>();
            for (const v of data as MaterialVideoItem[]) {
                if (v.source_type === 'bilibili') {
                    map.set(v.source_id, { id: v.id, categoryIds: v.category_ids ?? [] });
                }
            }
            return map;
        } catch {
            return new Map();
        }
    };

    // Fetch library state on mount
    useEffect(() => {
        let cancelled = false;
        fetchLibraryMapData().then(map => {
            if (!cancelled) setLibraryMap(map);
        });
        return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [apiFetch]);

    // ── Actions ─────────────────────────────────────────────────────────

    const importVideos = async (items: MediaItem[], categoryIds: string[]) => {
        const bvids = items.map(v => v.bvid);
        setImportingBvids(prev => new Set([...prev, ...bvids]));
        try {
            const { resp } = await apiFetch('/api/v1/MaterialImportRoute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    source_type: 'bilibili',
                    source_fid: fid ?? '',
                    videos: items,
                    category_ids: categoryIds,
                }),
            });
            if (resp.ok) {
                // Refresh library map to pick up new DB UUIDs and category state
                const newMap = await fetchLibraryMapData();
                setLibraryMap(newMap);
            }
        } finally {
            setImportingBvids(prev => {
                const next = new Set(prev);
                bvids.forEach(b => next.delete(b));
                return next;
            });
        }
    };

    const updateVideoCategories = async (bvid: string, dbId: string, categoryIds: string[]) => {
        const { resp } = await apiFetch('/api/v1/MaterialSetCategoriesRoute', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ video_id: dbId, category_ids: categoryIds }),
        });
        if (resp.ok) {
            // Optimistic update
            setLibraryMap(prev => {
                const next = new Map(prev);
                next.set(bvid, { id: dbId, categoryIds });
                return next;
            });
        }
    };

    const openPicker = (media: MediaItem) => {
        const entry = libraryMap.get(media.bvid);
        if (entry) {
            setPickerMode({ kind: 'update', bvid: media.bvid, dbId: entry.id, currentCategoryIds: entry.categoryIds });
        } else {
            setPickerMode({ kind: 'import', items: [media] });
        }
    };

    const openBatchPicker = (items: MediaItem[]) => {
        setPickerMode({ kind: 'import', items });
    };

    const handleModalConfirm = (categoryIds: string[]) => {
        if (!pickerMode) return;
        if (pickerMode.kind === 'import') {
            importVideos(pickerMode.items, categoryIds);
        } else {
            updateVideoCategories(pickerMode.bvid, pickerMode.dbId, categoryIds);
        }
        setPickerMode(null);
    };

    const toggle = (bvid: string) => {
        const next = new Set(selectedBvids);
        if (next.has(bvid)) next.delete(bvid); else next.add(bvid);
        setSelectedBvids(next);
    };

    if (!medias || medias.length === 0) {
        return (
            <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-400">
                暂无视频
            </div>
        );
    }

    const allSelected = medias.length > 0 && selectedBvids.size === medias.length;

    const toggleAll = () => {
        setSelectedBvids(allSelected ? new Set() : new Set(medias.map(v => v.bvid)));
    };

    const scrollToPage = (page: number) => {
        document.getElementById(`bilibili-video-page-${page}`)
            ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    };

    const showPageNav = totalPages !== undefined && totalPages > 1;
    const loadedPage = currentPage ?? 1;
    const selectedMedias = medias.filter(v => selectedBvids.has(v.bvid));

    return (
        <>
            {/* Category picker modal */}
            {pickerMode && (
                <CategoryPickerModal
                    videoCount={pickerMode.kind === 'import' ? pickerMode.items.length : 1}
                    existingCategoryIds={pickerMode.kind === 'update' ? pickerMode.currentCategoryIds : undefined}
                    isEditMode={pickerMode.kind === 'update'}
                    onConfirm={handleModalConfirm}
                    onCancel={() => setPickerMode(null)}
                />
            )}

            <div className="bg-white rounded-lg border border-gray-200">
                {/* Header */}
                <div className="flex items-center justify-between px-4 sm:px-6 py-3 border-b border-gray-100">
                    <div className="flex items-center gap-3">
                        <input
                            type="checkbox"
                            checked={allSelected}
                            onChange={toggleAll}
                            className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                        />
                        <span className="text-sm text-gray-500">
                            {totalCount !== undefined
                                ? <>已加载 <span className="font-medium">{medias.length}</span> / {totalCount} 个视频</>
                                : <>共 <span className="font-medium">{medias.length}</span> 个视频</>
                            }
                            {selectedBvids.size > 0 && (
                                <span className="text-blue-600 ml-1">· 已选 {selectedBvids.size} 个</span>
                            )}
                        </span>
                    </div>
                    {selectedBvids.size > 0 && (
                        <div className="flex gap-2">
                            <button
                                onClick={() => openBatchPicker(selectedMedias)}
                                disabled={selectedMedias.some(v => importingBvids.has(v.bvid))}
                                className="px-3 py-1.5 text-xs font-medium text-green-700 bg-green-50 border border-green-200 rounded-lg hover:bg-green-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                批量加入素材库 ({selectedBvids.size})
                            </button>
                            <button className="px-3 py-1.5 text-xs font-medium text-purple-700 bg-purple-50 border border-purple-200 rounded-lg hover:bg-purple-100 transition-colors">
                                批量分析 ({selectedBvids.size})
                            </button>
                        </div>
                    )}
                </div>

                {/* Sticky page nav bar */}
                {showPageNav && (
                    <div className="sticky top-0 z-10 bg-white border-b border-gray-100 px-4 sm:px-6 py-2 flex items-center gap-1.5 flex-wrap shadow-sm">
                        <span className="text-xs text-gray-400 mr-1 shrink-0">
                            第 {loadedPage}/{totalPages} 页
                        </span>
                        {buildPageWindows(totalPages!, loadedPage).map((p, i) =>
                            p === 0 ? (
                                <span key={`ellipsis-${i}`} className="text-xs text-gray-300 select-none px-0.5">…</span>
                            ) : (
                                <button
                                    key={p}
                                    onClick={() => p <= loadedPage ? scrollToPage(p) : onJumpToPage?.(p)}
                                    disabled={pageLoading === true && p > loadedPage}
                                    title={p <= loadedPage ? `跳转到第 ${p} 页` : `加载第 ${p} 页`}
                                    className={
                                        `min-w-7 h-6 px-1.5 text-xs rounded font-mono transition-colors ` +
                                        (p <= loadedPage
                                            ? 'bg-blue-100 text-blue-700 hover:bg-blue-200 cursor-pointer'
                                            : 'bg-gray-50 text-gray-400 border border-gray-200 hover:bg-blue-50 hover:text-blue-500 hover:border-blue-200 cursor-pointer') +
                                        (pageLoading && p > loadedPage ? ' opacity-40 pointer-events-none' : '')
                                    }
                                >
                                    {p}
                                </button>
                            )
                        )}
                        <select
                            value=""
                            onChange={(e) => {
                                const p = Number(e.target.value);
                                if (p) p <= loadedPage ? scrollToPage(p) : onJumpToPage?.(p);
                            }}
                            disabled={pageLoading}
                            className="ml-1 h-6 text-xs rounded border border-gray-200 bg-gray-50 text-gray-500 px-1 cursor-pointer hover:border-blue-300 disabled:opacity-40"
                        >
                            <option value="" disabled>跳转到…</option>
                            {Array.from({ length: totalPages! }, (_, i) => i + 1).map(p => (
                                <option key={p} value={p}>
                                    第 {p} 页{p <= loadedPage ? '（已加载）' : ''}
                                </option>
                            ))}
                        </select>
                        {pageLoading && (
                            <span className="text-xs text-blue-500 ml-1 animate-pulse">加载中…</span>
                        )}
                    </div>
                )}

                {/* List */}
                <div className="divide-y divide-gray-100" role="list">
                    {medias.map((media, index) => {
                        const selected = selectedBvids.has(media.bvid);
                        const isImporting = importingBvids.has(media.bvid);
                        const inLibrary = libraryMap.has(media.bvid);
                        const pageAnchorId = index % PAGE_SIZE === 0
                            ? `bilibili-video-page-${Math.floor(index / PAGE_SIZE) + 1}`
                            : undefined;
                        return (
                            <div
                                key={media.bvid}
                                id={pageAnchorId}
                                className={`flex gap-3 p-3 sm:p-4 transition-colors ${selected ? 'bg-blue-50' : 'hover:bg-gray-50'}`}
                            >
                                {/* Checkbox */}
                                <div className="flex-shrink-0 pt-1">
                                    <input
                                        type="checkbox"
                                        checked={selected}
                                        onChange={() => toggle(media.bvid)}
                                        className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                                    />
                                </div>

                                {/* Cover */}
                                <div className="relative flex-shrink-0">
                                    <img
                                        src={buildProxyUrl(media.cover)}
                                        alt={media.title}
                                        className="w-32 sm:w-40 h-[72px] sm:h-[90px] object-cover rounded-lg bg-gray-100"
                                    />
                                    <span className="absolute bottom-1 right-1 bg-black/70 text-white text-xs px-1 py-0.5 rounded font-mono leading-none">
                                        {formatDuration(media.duration)}
                                    </span>
                                    {media.page > 1 && (
                                        <span className="absolute top-1 left-1 bg-blue-600/90 text-white text-xs px-1.5 py-0.5 rounded leading-none">
                                            {media.page}P
                                        </span>
                                    )}
                                </div>

                                {/* Content */}
                                <div className="flex-1 min-w-0 flex flex-col justify-between gap-1">
                                    <h3 className="text-sm font-medium text-gray-900 line-clamp-2 leading-snug">
                                        {media.title}
                                    </h3>
                                    <div className="flex items-center gap-1.5">
                                        <img
                                            src={buildProxyUrl(media.upper.face)}
                                            alt={media.upper.name}
                                            className="w-4 h-4 rounded-full flex-shrink-0"
                                        />
                                        <span className="text-xs text-gray-500 truncate">{media.upper.name}</span>
                                    </div>
                                    <div className="flex items-center gap-3 text-xs text-gray-400">
                                        <span className="flex items-center gap-0.5">
                                            <Eye className="w-3 h-3" />
                                            {media.cnt_info.view_text_1 || formatCount(media.cnt_info.play)}
                                        </span>
                                        <span className="flex items-center gap-0.5">
                                            <Heart className="w-3 h-3" />
                                            {formatCount(media.cnt_info.collect)}
                                        </span>
                                        <span className="flex items-center gap-0.5">
                                            <MessageSquare className="w-3 h-3" />
                                            {formatCount(media.cnt_info.danmaku)}
                                        </span>
                                    </div>
                                    <div className="flex items-center justify-between">
                                        <span className="text-xs text-gray-400 font-mono">{media.bvid}</span>
                                        <span className="text-xs text-gray-400 hidden sm:block">
                                            收藏于 {formatFavDate(media.fav_time)}
                                        </span>
                                    </div>
                                </div>

                                {/* Actions — all buttons have fixed width w-28 */}
                                <div className="flex flex-col gap-1.5 flex-shrink-0 justify-center">
                                    <button
                                        onClick={() => window.open(`https://www.bilibili.com/video/${media.bvid}`, '_blank')}
                                        className="w-28 flex items-center justify-center gap-1.5 px-3 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors"
                                    >
                                        <ExternalLink className="w-3.5 h-3.5 flex-shrink-0" />
                                        打开
                                    </button>

                                    {/* 加入素材库 / 已加入（可修改分类） */}
                                    <button
                                        onClick={() => !isImporting && openPicker(media)}
                                        disabled={isImporting}
                                        className={`w-28 flex items-center justify-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg transition-colors ${
                                            inLibrary
                                                ? 'text-green-700 bg-green-100 hover:bg-green-200'
                                                : 'text-green-700 bg-green-50 hover:bg-green-100 disabled:opacity-50 disabled:cursor-not-allowed'
                                        }`}
                                    >
                                        {isImporting ? (
                                            <Loader className="w-3.5 h-3.5 flex-shrink-0 animate-spin" />
                                        ) : inLibrary ? (
                                            <Pencil className="w-3.5 h-3.5 flex-shrink-0" />
                                        ) : (
                                            <Download className="w-3.5 h-3.5 flex-shrink-0" />
                                        )}
                                        {inLibrary ? '已加入' : '加入素材库'}
                                    </button>

                                    <button
                                        onClick={() => console.log('analyze', media.bvid)}
                                        className="w-28 flex items-center justify-center gap-1.5 px-3 py-1.5 text-xs font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors"
                                    >
                                        <Play className="w-3.5 h-3.5 flex-shrink-0" />
                                        分析
                                    </button>
                                </div>
                            </div>
                        );
                    })}
                </div>

                {/* Next page slot */}
                {nextPageSlot && (
                    <div className="px-4 sm:px-6 py-3 border-t border-gray-100 flex justify-center">
                        {nextPageSlot}
                    </div>
                )}
            </div>
        </>
    );
}
