import { type ReactNode, useState, useEffect } from "react";
import { ExternalLink, Download, Play, Eye, Heart, MessageSquare, Check, Loader, Pencil, ChevronDown, Braces, X } from "lucide-react";
import { useImageProxyUrl, useAppFetch } from "~/utils/app_fetch";
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
    /**
     * Explicitly set DB primary key, present only on items synthesised during
     * multi-P expansion. Original items from the API do not carry this field;
     * their DB ID is derived as `bilibili_bvid__${bvid}__P1`.
     */
    dbId?: string;
}

// ─── Library state ─────────────────────────────────────────────────────────

interface LibraryEntry {
    /** DB primary key */
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
    | { kind: "update"; dbId: string; currentCategoryIds: string[] };

// ─── Helpers ───────────────────────────────────────────────────────────────

/**
 * Returns the DB primary key for a MediaItem, mirroring the server-side
 * `bilibiliVideoId(bvid, page)` function.
 *
 * Expanded sub-pages carry an explicit `dbId`; original items (single-P or
 * unexpanded multi-P) always map to page 1 in the DB.
 */
function computeDbId(media: MediaItem): string {
    return media.dbId ?? `bilibili_bvid__${media.bvid}__P1`;
}

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

    // Raw data modal
    const [rawDataMedia, setRawDataMedia] = useState<MediaItem | null>(null);

    // All selection/loading/expansion sets are keyed by dbId
    const [selectedDbIds, setSelectedDbIds] = useState<Set<string>>(new Set());
    const [importingDbIds, setImportingDbIds] = useState<Set<string>>(new Set());

    // Persistent library state (dbId → DB entry)
    const [libraryMap, setLibraryMap] = useState<Map<string, LibraryEntry>>(new Map());

    // Category picker modal
    const [pickerMode, setPickerMode] = useState<PickerMode | null>(null);

    // Multi-P expansion: dbId of the original item → loading state / expanded pages
    const [expandingDbIds, setExpandingDbIds] = useState<Set<string>>(new Set());
    const [expandedPages, setExpandedPages] = useState<Map<string, MediaItem[]>>(new Map());

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
                    // Key by DB ID so each page of a multi-P video has its own entry
                    map.set(v.id, { id: v.id, categoryIds: v.category_ids ?? [] });
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
        const dbIds = items.map(computeDbId);
        setImportingDbIds(prev => new Set([...prev, ...dbIds]));
        try {
            const { resp } = await apiFetch('/api/v1/MaterialImportRoute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    source_type: 'bilibili',
                    source_fid: fid ?? '',
                    videos: items.map(v => ({
                        ...v,
                        // Expanded sub-pages have an explicit dbId and correct page number.
                        // Original items have page = total-page-count, not actual page → use 1.
                        page: v.dbId !== undefined ? v.page : 1,
                    })),
                    category_ids: categoryIds,
                }),
            });
            if (resp.ok) {
                // Refresh library map to pick up new DB IDs and category state
                const newMap = await fetchLibraryMapData();
                setLibraryMap(newMap);
            }
        } finally {
            setImportingDbIds(prev => {
                const next = new Set(prev);
                dbIds.forEach(id => next.delete(id));
                return next;
            });
        }
    };

    const updateVideoCategories = async (dbId: string, categoryIds: string[]) => {
        const { resp } = await apiFetch('/api/v1/MaterialSetCategoriesRoute', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ material_id: dbId, category_ids: categoryIds }),
        });
        if (resp.ok) {
            setLibraryMap(prev => {
                const next = new Map(prev);
                next.set(dbId, { id: dbId, categoryIds });
                return next;
            });
        }
    };

    const deleteFromLibrary = async (dbId: string) => {
        const { resp } = await apiFetch('/api/v1/MaterialDeleteRoute', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ids: [dbId] }),
        });
        if (resp.ok) {
            setLibraryMap(prev => {
                const next = new Map(prev);
                next.delete(dbId);
                return next;
            });
        }
    };

    const expandPages = async (media: MediaItem) => {
        const dbId = computeDbId(media);
        if (expandingDbIds.has(dbId)) return;
        setExpandingDbIds(prev => new Set([...prev, dbId]));
        try {
            const { resp, data } = await apiFetch('/api/v1/BilibiliVideoGetPagesRoute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ bvid: media.bvid }),
            });
            if (!resp.ok) return;
            type PageInfo = { page: number; title: string; duration: number; cover: string };
            const pages: PageInfo[] = data as PageInfo[];
            const expanded: MediaItem[] = pages.map(p => ({
                ...media,
                dbId: `bilibili_bvid__${media.bvid}__P${p.page}`,
                title: p.title || `${media.title} P${p.page}`,
                duration: p.duration,
                cover: p.cover || media.cover,
                page: p.page,
            }));
            setExpandedPages(prev => new Map(prev).set(dbId, expanded));
            // Auto-select all expanded pages if original was selected
            if (selectedDbIds.has(dbId)) {
                setSelectedDbIds(prev => {
                    const next = new Set(prev);
                    next.delete(dbId);
                    expanded.forEach(e => next.add(computeDbId(e)));
                    return next;
                });
            }
        } finally {
            setExpandingDbIds(prev => { const n = new Set(prev); n.delete(dbId); return n; });
        }
    };

    const openPicker = (media: MediaItem) => {
        const dbId = computeDbId(media);
        const entry = libraryMap.get(dbId);
        if (entry) {
            setPickerMode({ kind: 'update', dbId: entry.id, currentCategoryIds: entry.categoryIds });
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
            updateVideoCategories(pickerMode.dbId, categoryIds);
        }
        setPickerMode(null);
    };

    const handleModalDelete = () => {
        if (!pickerMode || pickerMode.kind !== 'update') return;
        deleteFromLibrary(pickerMode.dbId);
        setPickerMode(null);
    };

    const toggle = (dbId: string) => {
        const next = new Set(selectedDbIds);
        if (next.has(dbId)) next.delete(dbId); else next.add(dbId);
        setSelectedDbIds(next);
    };

    if (!medias || medias.length === 0) {
        return (
            <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-400">
                暂无视频
            </div>
        );
    }

    // Build effective list: replace multi-P items that have been expanded with their pages
    const effectiveMedias: MediaItem[] = medias.flatMap(m => {
        const pages = expandedPages.get(computeDbId(m));
        return pages ? pages : [m];
    });

    const allSelected = effectiveMedias.length > 0 && selectedDbIds.size === effectiveMedias.length;

    const toggleAll = () => {
        setSelectedDbIds(allSelected ? new Set() : new Set(effectiveMedias.map(computeDbId)));
    };

    const scrollToPage = (page: number) => {
        document.getElementById(`bilibili-video-page-${page}`)
            ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    };

    const showPageNav = totalPages !== undefined && totalPages > 1;
    const loadedPage = currentPage ?? 1;
    const selectedMedias = effectiveMedias.filter(v => selectedDbIds.has(computeDbId(v)));

    return (
        <>
            {/* Raw data modal */}
            {rawDataMedia && (
                <div
                    className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-sm"
                    onClick={(e) => e.target === e.currentTarget && setRawDataMedia(null)}
                >
                    <div className="bg-white rounded-xl shadow-xl w-full max-w-lg mx-4 flex flex-col max-h-[80vh]">
                        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
                            <div>
                                <h2 className="text-base font-semibold text-gray-900">原始数据</h2>
                                <p className="text-xs text-gray-500 mt-0.5 font-mono">{rawDataMedia.bvid}</p>
                            </div>
                            <button
                                onClick={() => setRawDataMedia(null)}
                                className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors"
                            >
                                <X className="w-4 h-4 text-gray-500" />
                            </button>
                        </div>
                        <div className="flex-1 overflow-y-auto px-5 py-4">
                            <pre className="text-xs text-gray-800 whitespace-pre-wrap break-all font-mono leading-relaxed">
                                {JSON.stringify(rawDataMedia, null, 2)}
                            </pre>
                        </div>
                    </div>
                </div>
            )}

            {/* Category picker modal */}
            {pickerMode && (
                <CategoryPickerModal
                    videoCount={pickerMode.kind === 'import' ? pickerMode.items.length : 1}
                    existingCategoryIds={pickerMode.kind === 'update' ? pickerMode.currentCategoryIds : undefined}
                    isEditMode={pickerMode.kind === 'update'}
                    onConfirm={handleModalConfirm}
                    onCancel={() => setPickerMode(null)}
                    onDelete={pickerMode.kind === 'update' ? handleModalDelete : undefined}
                />
            )}

            <div className="bg-white rounded-lg border border-gray-200">
                {/* Header */}
                <div className="flex flex-wrap items-center gap-2 px-4 sm:px-6 py-3 border-b border-gray-100">
                    <div className="flex items-center gap-3">
                        <input
                            type="checkbox"
                            checked={allSelected}
                            onChange={toggleAll}
                            className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                        />
                        <span className="text-sm text-gray-500">
                            {totalCount !== undefined
                                ? <>已加载 <span className="font-medium">{effectiveMedias.length}</span> / {totalCount} 个视频</>
                                : <>共 <span className="font-medium">{effectiveMedias.length}</span> 个视频</>
                            }
                            {selectedDbIds.size > 0 && (
                                <span className="text-blue-600 ml-1">· 已选 {selectedDbIds.size} 个</span>
                            )}
                        </span>
                    </div>
                    {selectedDbIds.size > 0 && (
                        <div className="ml-auto flex gap-2">
                            <button
                                onClick={() => openBatchPicker(selectedMedias)}
                                disabled={selectedMedias.some(v => importingDbIds.has(computeDbId(v)))}
                                className="px-3 py-1.5 text-xs font-medium text-green-700 bg-green-50 border border-green-200 rounded-lg hover:bg-green-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                批量加入素材库 ({selectedDbIds.size})
                            </button>
                            <button className="px-3 py-1.5 text-xs font-medium text-purple-700 bg-purple-50 border border-purple-200 rounded-lg hover:bg-purple-100 transition-colors">
                                批量分析 ({selectedDbIds.size})
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
                <div className="divide-y divide-gray-100" role="list" style={{ maxWidth: "92vw" }}>
                    {effectiveMedias.map((media, index) => {
                        const dbId = computeDbId(media);
                        const selected = selectedDbIds.has(dbId);
                        const isImporting = importingDbIds.has(dbId);
                        const inLibrary = libraryMap.has(dbId);
                        // Show expand button only for original multi-page items that haven't been expanded yet
                        const isMultiPage = media.dbId === undefined && media.page > 1 && !expandedPages.has(dbId);
                        const isExpanding = expandingDbIds.has(dbId);
                        const pageAnchorId = index % PAGE_SIZE === 0
                            ? `bilibili-video-page-${Math.floor(index / PAGE_SIZE) + 1}`
                            : undefined;
                        return (
                            <div
                                key={dbId}
                                id={pageAnchorId}
                                className={`flex flex-wrap gap-2 sm:gap-3 p-3 sm:p-4 transition-colors ${selected ? 'bg-blue-50' : 'hover:bg-gray-50'}`}
                            >
                                {/* Checkbox */}
                                <div className="flex-shrink-0 pt-1">
                                    <input
                                        type="checkbox"
                                        checked={selected}
                                        onChange={() => toggle(dbId)}
                                        className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                                    />
                                </div>

                                {/* Cover */}
                                <div className="relative flex-shrink-0 self-start">
                                    <img
                                        src={buildProxyUrl(media.cover)}
                                        alt={"收藏夹 - " + media.title}
                                        className="w-24 sm:w-40 h-[54px] sm:h-[90px] object-cover rounded-lg bg-gray-100"
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
                                    {media.intro && (
                                        <p className="text-xs text-gray-400 truncate whitespace-nowrap">{media.intro}</p>
                                    )}
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

                                {/* Actions — 2-col grid on mobile, fixed-width column on sm+ */}
                                <div className="w-full sm:w-auto sm:flex-shrink-0 grid grid-cols-2 sm:flex sm:flex-col gap-1.5 sm:justify-center">
                                    <button
                                        onClick={() => window.open(`https://www.bilibili.com/video/${media.bvid}${media.page <= 1 ? "" : "?p=" + media.page}`, '_blank')}
                                        className="w-full sm:w-28 flex items-center justify-center gap-1.5 px-3 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors"
                                    >
                                        <ExternalLink className="w-3.5 h-3.5 flex-shrink-0" />
                                        打开
                                    </button>

                                    <button
                                        onClick={() => setRawDataMedia(media)}
                                        className="w-full sm:w-28 flex items-center justify-center gap-1.5 px-3 py-1.5 text-xs font-medium text-gray-600 bg-gray-50 rounded-lg hover:bg-gray-100 transition-colors"
                                    >
                                        <Braces className="w-3.5 h-3.5 flex-shrink-0" />
                                        原始数据
                                    </button>

                                    {/* 展开全部分P（仅多P视频且尚未展开） */}
                                    {isMultiPage && (
                                        <button
                                            onClick={() => expandPages(media)}
                                            disabled={isExpanding}
                                            className="w-full sm:w-28 flex items-center justify-center gap-1.5 px-3 py-1.5 text-xs font-medium text-amber-700 bg-amber-50 rounded-lg hover:bg-amber-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                        >
                                            {isExpanding
                                                ? <Loader className="w-3.5 h-3.5 flex-shrink-0 animate-spin" />
                                                : <ChevronDown className="w-3.5 h-3.5 flex-shrink-0" />
                                            }
                                            展开 {media.page}P
                                        </button>
                                    )}

                                    {/* 加入素材库 / 已加入（可修改分类） */}
                                    {!isMultiPage && <button
                                        onClick={() => !isImporting && openPicker(media)}
                                        disabled={isImporting}
                                        className={`w-full sm:w-28 flex items-center justify-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg transition-colors ${inLibrary
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
                                    </button>}

                                    <button
                                        onClick={() => console.log('analyze', dbId)}
                                        className="w-full sm:w-28 flex items-center justify-center gap-1.5 px-3 py-1.5 text-xs font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors"
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
