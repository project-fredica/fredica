import { type ReactNode, useState } from "react";
import { Trash2, ExternalLink, Plus, X, Loader } from "lucide-react";
import { useAppFetch, useImageProxyUrl } from "~/utils/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { MaterialTaskBadge } from "~/components/MaterialTaskBadge";

// ─── Types ────────────────────────────────────────────────────────────────────

interface MaterialVideo {
    id: string;
    source_type: string;
    source_id: string;
    title: string;
    cover_url: string;
    description: string;
    duration: number;
    pipeline_status: string;
    local_video_path: string;
    local_audio_path: string;
    transcript_path: string;
    extra: string;
    created_at: number;
    updated_at: number;
    category_ids: string[];
}

interface MaterialCategory {
    id: string;
    name: string;
    description: string;
    /** Count of all materials (any type) in this category. */
    material_count: number;
    created_at: number;
    updated_at: number;
}

interface BilibiliExtra {
    upper_name?: string;
    upper_face_url?: string;
    upper_mid?: number;
    cnt_play?: number;
    cnt_collect?: number;
    cnt_danmaku?: number;
    fav_time?: number;
    source_fid?: string;
    page_count?: number;
    bvid?: string;
}

interface MaterialTask {
    id: string;
    material_id: string;
    task_type: string;
    status: string;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const STATUS_TABS = [
    { key: 'all',         label: '全部' },
    { key: 'pending',     label: '未下载' },
    { key: 'local_ready', label: '已就绪' },
    { key: 'local_error', label: '下载失败' },
] as const;
type StatusTab = typeof STATUS_TABS[number]['key'];

const STATUS_BADGE: Record<string, { label: string; className: string }> = {
    pending:     { label: '未下载',   className: 'bg-gray-100 text-gray-600' },
    local_ready: { label: '已就绪',   className: 'bg-green-100 text-green-700' },
    local_error: { label: '下载失败', className: 'bg-red-100 text-red-700' },
};

const SOURCE_BADGE: Record<string, { label: string; className: string }> = {
    bilibili: { label: 'B站',    className: 'bg-pink-100 text-pink-700' },
    youtube:  { label: 'YouTube', className: 'bg-red-100 text-red-700' },
    local:    { label: '本地',    className: 'bg-gray-100 text-gray-600' },
};

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatDuration(seconds: number): string {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    return `${m}:${String(s).padStart(2, '0')}`;
}

function formatCount(n: number): string {
    if (n >= 100_000_000) return `${(n / 100_000_000).toFixed(1)}亿`;
    if (n >= 10_000) return `${(n / 10_000).toFixed(1)}万`;
    return String(n);
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function LibraryPage() {
    const [activeTab, setActiveTab] = useState<StatusTab>('all');
    const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(null);
    const [refreshCounter, setRefreshCounter] = useState(0);

    // Deletion state
    const [deletingVideoIds, setDeletingVideoIds] = useState<Set<string>>(new Set());
    const [deletingCategoryIds, setDeletingCategoryIds] = useState<Set<string>>(new Set());

    // New category creation
    const [newCategoryName, setNewCategoryName] = useState('');
    const [creatingCategory, setCreatingCategory] = useState(false);

    // Task data keyed by material_id
    const [tasksMap, setTasksMap] = useState<Map<string, MaterialTask[]>>(new Map());

    const buildProxyUrl = useImageProxyUrl();

    // ── Data fetching ───────────────────────────────────────────────────────

    const { data: videos, loading: videosLoading, error: videosError, apiFetch } = useAppFetch<MaterialVideo[]>({
        appPath: `/api/v1/MaterialListRoute?_r=${refreshCounter}`,
        init: { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' },
        timeout: 15_000,
    });

    const { data: categories, loading: categoriesLoading } = useAppFetch<MaterialCategory[]>({
        appPath: `/api/v1/MaterialCategoryListRoute?_r=${refreshCounter}`,
        init: { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' },
        timeout: 10_000,
    });

    // Lazily load tasks for videos that are visible
    const loadTasksForMaterial = async (materialId: string) => {
        if (tasksMap.has(materialId)) return;
        try {
            const { resp, data } = await apiFetch('/api/v1/MaterialTaskListRoute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ material_id: materialId }),
            });
            if (resp.ok) {
                setTasksMap(prev => new Map(prev).set(materialId, data as MaterialTask[]));
            }
        } catch { /* ignore */ }
    };

    // ── Imperative actions ──────────────────────────────────────────────────

    const handleDeleteVideo = async (id: string) => {
        setDeletingVideoIds(prev => new Set([...prev, id]));
        try {
            const { resp } = await apiFetch('/api/v1/MaterialDeleteRoute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ids: [id] }),
            });
            if (resp.ok) setRefreshCounter(c => c + 1);
        } finally {
            setDeletingVideoIds(prev => { const n = new Set(prev); n.delete(id); return n; });
        }
    };

    const handleDeleteCategory = async (id: string) => {
        setDeletingCategoryIds(prev => new Set([...prev, id]));
        try {
            const { resp } = await apiFetch('/api/v1/MaterialCategoryDeleteRoute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id }),
            });
            if (resp.ok) {
                if (selectedCategoryId === id) setSelectedCategoryId(null);
                setRefreshCounter(c => c + 1);
            }
        } finally {
            setDeletingCategoryIds(prev => { const n = new Set(prev); n.delete(id); return n; });
        }
    };

    const handleCreateCategory = async () => {
        const name = newCategoryName.trim();
        if (!name || creatingCategory) return;
        setCreatingCategory(true);
        try {
            const { resp } = await apiFetch('/api/v1/MaterialCategoryCreateRoute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name }),
            });
            if (resp.ok) {
                setNewCategoryName('');
                setRefreshCounter(c => c + 1);
            }
        } finally {
            setCreatingCategory(false);
        }
    };

    // ── Filtering ───────────────────────────────────────────────────────────

    const filtered = (videos ?? []).filter(v => {
        const matchCategory = !selectedCategoryId || v.category_ids.includes(selectedCategoryId);
        const matchStatus = activeTab === 'all' || v.pipeline_status === activeTab;
        return matchCategory && matchStatus;
    });

    // Validate that the selected category still exists after a refresh
    const categoryExists = categories?.some(c => c.id === selectedCategoryId) ?? true;
    const effectiveCategoryId = categoryExists ? selectedCategoryId : null;

    // ── Render ──────────────────────────────────────────────────────────────

    return (
        <SidebarLayout>
            <div className="max-w-4xl mx-auto p-4 sm:p-6 space-y-4">

                {/* ── Header ── */}
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-xl font-semibold text-gray-900">素材库</h1>
                        {videos && (
                            <p className="text-sm text-gray-500 mt-0.5">共 {videos.length} 个视频</p>
                        )}
                    </div>
                </div>

                {/* ── Category management ── */}
                <div className="bg-white rounded-lg border border-gray-200 px-4 py-3 space-y-3">
                    <div className="flex items-center justify-between">
                        <span className="text-sm font-medium text-gray-700">分类</span>
                        {categoriesLoading && (
                            <Loader className="w-3.5 h-3.5 animate-spin text-gray-400" />
                        )}
                    </div>

                    {/* Category filter pills */}
                    <div className="flex flex-wrap gap-2">
                        {/* "全部" pill */}
                        <button
                            onClick={() => setSelectedCategoryId(null)}
                            className={`px-3 py-1 text-xs font-medium rounded-full transition-colors ${
                                !effectiveCategoryId
                                    ? 'bg-blue-600 text-white'
                                    : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                            }`}
                        >
                            全部{videos ? ` (${videos.length})` : ''}
                        </button>

                        {/* Category pills */}
                        {(categories ?? []).map(cat => {
                            const isActive = effectiveCategoryId === cat.id;
                            const isDeleting = deletingCategoryIds.has(cat.id);
                            return (
                                <span
                                    key={cat.id}
                                    className={`inline-flex items-center gap-1 pl-3 pr-1.5 py-1 text-xs font-medium rounded-full transition-colors ${
                                        isActive
                                            ? 'bg-blue-600 text-white'
                                            : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                                    } ${isDeleting ? 'opacity-50' : ''}`}
                                >
                                    <button
                                        onClick={() => setSelectedCategoryId(isActive ? null : cat.id)}
                                        className="leading-none"
                                    >
                                        {cat.name} ({cat.material_count})
                                    </button>
                                    <button
                                        onClick={() => handleDeleteCategory(cat.id)}
                                        disabled={isDeleting}
                                        className={`rounded-full p-0.5 transition-colors ${
                                            isActive ? 'hover:bg-blue-500' : 'hover:bg-gray-300'
                                        } disabled:cursor-not-allowed`}
                                        title={`删除分类「${cat.name}」`}
                                    >
                                        <X className="w-3 h-3" />
                                    </button>
                                </span>
                            );
                        })}
                    </div>

                    {/* New category input */}
                    <div className="flex gap-2 pt-1">
                        <input
                            type="text"
                            value={newCategoryName}
                            onChange={e => setNewCategoryName(e.target.value)}
                            onKeyDown={e => e.key === 'Enter' && handleCreateCategory()}
                            placeholder="新建分类名称…"
                            className="flex-1 px-3 py-1.5 text-sm border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                        />
                        <button
                            onClick={handleCreateCategory}
                            disabled={!newCategoryName.trim() || creatingCategory}
                            className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {creatingCategory
                                ? <Loader className="w-3.5 h-3.5 animate-spin" />
                                : <Plus className="w-3.5 h-3.5" />
                            }
                            创建
                        </button>
                    </div>
                </div>

                {/* ── Status tabs ── */}
                <div className="flex gap-1 border-b border-gray-200">
                    {STATUS_TABS.map(tab => {
                        const base = selectedCategoryId
                            ? (videos ?? []).filter(v => v.category_ids.includes(selectedCategoryId))
                            : (videos ?? []);
                        const count = tab.key === 'all'
                            ? base.length
                            : base.filter(v => v.pipeline_status === tab.key).length;
                        return (
                            <button
                                key={tab.key}
                                onClick={() => setActiveTab(tab.key)}
                                className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                                    activeTab === tab.key
                                        ? 'border-blue-600 text-blue-600'
                                        : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                                }`}
                            >
                                {tab.label}
                                {videos && (
                                    <span className="ml-1.5 text-xs text-gray-400">({count})</span>
                                )}
                            </button>
                        );
                    })}
                </div>

                {/* ── Loading / error ── */}
                {videosLoading && (
                    <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-400">
                        加载中…
                    </div>
                )}
                {videosError && !videosLoading && (
                    <div className="bg-white rounded-lg border border-gray-200 p-4">
                        <p className="text-sm text-red-600">{videosError.message}</p>
                    </div>
                )}

                {/* ── Empty state ── */}
                {!videosLoading && !videosError && filtered.length === 0 && (
                    <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-400">
                        {!videos || videos.length === 0
                            ? '素材库为空，前往收藏夹浏览并加入素材吧'
                            : '该筛选条件下暂无素材'}
                    </div>
                )}

                {/* ── Video list ── */}
                {filtered.length > 0 && (
                    <div className="bg-white rounded-lg border border-gray-200 divide-y divide-gray-100">
                        {filtered.map(video => {
                            const sourceBadge = SOURCE_BADGE[video.source_type]
                                ?? { label: video.source_type, className: 'bg-gray-100 text-gray-600' };
                            const statusBadge = STATUS_BADGE[video.pipeline_status]
                                ?? { label: video.pipeline_status, className: 'bg-gray-100 text-gray-600' };
                            const isDeleting = deletingVideoIds.has(video.id);
                            const videoTasks = tasksMap.get(video.id);

                            // Category pills shown on the video row
                            const videoCats = (categories ?? []).filter(c =>
                                video.category_ids.includes(c.id)
                            );

                            let extraInfo: ReactNode = null;
                            try {
                                const ext = JSON.parse(video.extra) as BilibiliExtra;
                                if (video.source_type === 'bilibili') {
                                    extraInfo = (
                                        <div className="flex items-center gap-3 text-xs text-gray-400">
                                            {ext.upper_name && <span>UP: {ext.upper_name}</span>}
                                            {ext.cnt_play !== undefined && <span>播放 {formatCount(ext.cnt_play)}</span>}
                                            {ext.cnt_collect !== undefined && <span>收藏 {formatCount(ext.cnt_collect)}</span>}
                                        </div>
                                    );
                                }
                            } catch { /* ignore */ }

                            const bilibiliUrl = video.source_type === 'bilibili'
                                ? `https://www.bilibili.com/video/${video.source_id}`
                                : null;

                            return (
                                <div
                                    key={video.id}
                                    className={`flex gap-3 p-3 sm:p-4 transition-colors hover:bg-gray-50 ${isDeleting ? 'opacity-50' : ''}`}
                                    onMouseEnter={() => loadTasksForMaterial(video.id)}
                                >
                                    {/* Cover */}
                                    <div className="relative flex-shrink-0">
                                        <img
                                            src={video.cover_url ? buildProxyUrl(video.cover_url) : ''}
                                            alt={video.title}
                                            className="w-32 sm:w-40 h-[72px] sm:h-[90px] object-cover rounded-lg bg-gray-100"
                                        />
                                        {video.duration > 0 && (
                                            <span className="absolute bottom-1 right-1 bg-black/70 text-white text-xs px-1 py-0.5 rounded font-mono leading-none">
                                                {formatDuration(video.duration)}
                                            </span>
                                        )}
                                    </div>

                                    {/* Content */}
                                    <div className="flex-1 min-w-0 flex flex-col justify-between gap-1">
                                        <h3 className="text-sm font-medium text-gray-900 line-clamp-2 leading-snug">
                                            {video.title || video.source_id}
                                        </h3>
                                        {extraInfo}

                                        {/* Category + status badges */}
                                        <div className="flex items-center gap-1.5 flex-wrap">
                                            <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${sourceBadge.className}`}>
                                                {sourceBadge.label}
                                            </span>
                                            <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${statusBadge.className}`}>
                                                {statusBadge.label}
                                            </span>
                                            {videoCats.map(cat => (
                                                <span
                                                    key={cat.id}
                                                    onClick={() => setSelectedCategoryId(cat.id)}
                                                    className="text-[10px] font-medium px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-600 cursor-pointer hover:bg-indigo-100"
                                                >
                                                    {cat.name}
                                                </span>
                                            ))}
                                        </div>

                                        <span className="text-xs text-gray-400 font-mono">{video.source_id}</span>

                                        {/* Task badges */}
                                        {videoTasks && videoTasks.length > 0 && (
                                            <div className="flex items-center gap-1 flex-wrap">
                                                {videoTasks.map(task => (
                                                    <MaterialTaskBadge
                                                        key={task.id}
                                                        taskType={task.task_type}
                                                        status={task.status}
                                                    />
                                                ))}
                                            </div>
                                        )}
                                    </div>

                                    {/* Actions */}
                                    <div className="flex flex-col gap-1.5 flex-shrink-0 justify-center">
                                        {bilibiliUrl && (
                                            <button
                                                onClick={() => window.open(bilibiliUrl, '_blank')}
                                                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors whitespace-nowrap"
                                            >
                                                <ExternalLink className="w-3.5 h-3.5" />
                                                打开
                                            </button>
                                        )}
                                        <button
                                            onClick={() => handleDeleteVideo(video.id)}
                                            disabled={isDeleting}
                                            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                        >
                                            <Trash2 className="w-3.5 h-3.5" />
                                            删除
                                        </button>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                )}
            </div>
        </SidebarLayout>
    );
}
