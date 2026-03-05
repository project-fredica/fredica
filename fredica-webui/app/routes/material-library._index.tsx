import { type ReactNode, useState, useEffect, useCallback } from "react";
import { Trash2, ExternalLink, Plus, X, Loader, Settings, Download, RefreshCw, Pause, Play } from "lucide-react";
import { Link, useSearchParams } from "react-router";
import { useAppFetch, useImageProxyUrl } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { MaterialTaskBadge } from "~/components/MaterialTaskBadge";
import { print_error, reportHttpError } from "~/util/error_handler";

// ─── Types ────────────────────────────────────────────────────────────────────

interface MaterialVideo {
    id: string;
    source_type: string;
    source_id: string;
    title: string;
    cover_url: string;
    description: string;
    duration: number;
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

/** Task from the Phase-1 worker engine (task table). */
interface WorkerTask {
    id: string;
    type: string;
    material_id: string;
    pipeline_id: string;
    status: string;
    error: string | null;
    error_type: string | null;
    progress: number;
    is_paused: boolean;
    created_at: number;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const POLL_INTERVAL_MS = 5_000;
const PAGE_SIZE = 20;

const SOURCE_BADGE: Record<string, { label: string; className: string }> = {
    bilibili: { label: 'B站', className: 'bg-pink-100 text-pink-700' },
    youtube: { label: 'YouTube', className: 'bg-red-100 text-red-700' },
    local: { label: '本地', className: 'bg-gray-100 text-gray-600' },
};

const MODAL_TABS = [
    { key: 'basics' as const, label: '初级功能' },
    { key: 'pipeline' as const, label: '一键流程' },
];

const WORKER_TASK_STATUS: Record<string, { label: string; className: string }> = {
    pending: { label: '排队中', className: 'bg-yellow-100 text-yellow-700' },
    claimed: { label: '执行中', className: 'bg-blue-100 text-blue-700' },
    running: { label: '执行中', className: 'bg-blue-100 text-blue-700' },
    completed: { label: '已完成', className: 'bg-green-100 text-green-700' },
    failed: { label: '失败', className: 'bg-red-100 text-red-700' },
    cancelled: { label: '已取消', className: 'bg-gray-100 text-gray-400' },
};

const TASK_TYPE_LABELS: Record<string, string> = {
    DOWNLOAD_BILIBILI_VIDEO: '下载视频',
};

const MODAL_TASK_POLL_MS = 2_000;

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
    const [searchParams, setSearchParams] = useSearchParams();
    const taskIdParam = searchParams.get('task_id') ?? '';

    const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(null);
    const [page, setPage] = useState(1);
    const [refreshing, setRefreshing] = useState(false);

    // ── List data (kept across polls — never reset to null mid-flight) ───────
    const [videos,           setVideos]           = useState<MaterialVideo[] | null>(null);
    const [categories,       setCategories]       = useState<MaterialCategory[] | null>(null);
    const [downloadStatusMap,setDownloadStatusMap]= useState<Record<string, boolean>>({});
    const [videosLoading,    setVideosLoading]    = useState(true);
    const [categoriesLoading,setCategoriesLoading]= useState(true);
    const [videosError,      setVideosError]      = useState<Error | null>(null);

    // Deletion state
    const [deletingVideoIds, setDeletingVideoIds] = useState<Set<string>>(new Set());
    const [deletingCategoryIds, setDeletingCategoryIds] = useState<Set<string>>(new Set());

    // Action modal state
    const [actionTarget, setActionTarget] = useState<MaterialVideo | null>(null);
    const [modalTab, setModalTab] = useState<'basics' | 'pipeline'>('basics');
    const [modalWorkerTasks, setModalWorkerTasks] = useState<WorkerTask[]>([]);
    const [modalTasksLoading, setModalTasksLoading] = useState(false);
    const [runningTaskType, setRunningTaskType] = useState<string | null>(null);
    const [cancellingPipelineId, setCancellingPipelineId] = useState<string | null>(null);
    const [pausingTaskId,        setPausingTaskId]        = useState<string | null>(null);

    // New category creation
    const [newCategoryName, setNewCategoryName] = useState('');
    const [creatingCategory, setCreatingCategory] = useState(false);

    // Task data keyed by material_id
    const [tasksMap, setTasksMap] = useState<Map<string, MaterialTask[]>>(new Map());

    // task_id URL filter: resolve to material_id via API
    // undefined = resolving, null = no filter / not found, string = matched material_id
    const [taskFilterMaterialId, setTaskFilterMaterialId] = useState<string | null | undefined>(
        taskIdParam ? undefined : null
    );

    const buildProxyUrl = useImageProxyUrl();

    // ── Data fetching ───────────────────────────────────────────────────────
    // apiFetch is stable (only changes when host/token changes).
    // We never pass appPath to useAppFetch, so it won't fire declarative requests
    // or call setData(null). All polling is manual via fetchData().
    const { apiFetch } = useAppFetch();

    const fetchData = useCallback(async () => {
        try {
            const [videosResp, categoriesResp] = await Promise.all([
                apiFetch('/api/v1/MaterialListRoute', { method: 'POST', body: '{}' }, { timeout: 15_000, silent: true }),
                apiFetch('/api/v1/MaterialCategoryListRoute', { method: 'POST', body: '{}' }, { silent: true }),
            ]);
            const fetchedVideos = videosResp.data as MaterialVideo[];
            setVideos(fetchedVideos);
            setVideosError(null);
            setCategories(categoriesResp.data as MaterialCategory[]);

            // Batch-check local download status for all bilibili videos
            const bilibiliIds = fetchedVideos.filter(v => v.source_type === 'bilibili').map(v => v.id);
            if (bilibiliIds.length > 0) {
                const { resp: dsResp, data: dsData } = await apiFetch(
                    '/api/v1/MaterialDownloadStatusRoute',
                    { method: 'POST', body: JSON.stringify({ material_ids: bilibiliIds }) },
                    { silent: true },
                );
                if (dsResp.ok) setDownloadStatusMap(dsData as Record<string, boolean>);
            }
        } catch (err) {
            setVideosError(err instanceof Error ? err : new Error(String(err)));
        } finally {
            setVideosLoading(false);
            setCategoriesLoading(false);
        }
    }, [apiFetch]);

    // Initial load + auto-refresh polling
    useEffect(() => {
        fetchData();
        const id = setInterval(fetchData, POLL_INTERVAL_MS);
        return () => clearInterval(id);
    }, [fetchData]);

    // Resolve task_id URL param → material_id
    useEffect(() => {
        if (!taskIdParam) {
            setTaskFilterMaterialId(null);
            return;
        }
        setTaskFilterMaterialId(undefined); // resolving
        const params = new URLSearchParams({ id: taskIdParam, page_size: '1' });
        apiFetch(`/api/v1/WorkerTaskListRoute?${params.toString()}`, { method: 'GET' }, { silent: true })
            .then(({ data }) => {
                const result = data as { items: Array<{ material_id: string }>; total: number };
                const mid = result?.items?.[0]?.material_id;
                setTaskFilterMaterialId(mid ?? null);
            })
            .catch(() => setTaskFilterMaterialId(null));
    }, [taskIdParam, apiFetch]);

    const handleManualRefresh = async () => {
        setRefreshing(true);
        try { await fetchData(); } finally { setRefreshing(false); }
    };

    // Lazily load tasks for videos that are visible
    const loadTasksForMaterial = async (materialId: string) => {
        if (tasksMap.has(materialId)) return;
        try {
            const { resp, data } = await apiFetch('/api/v1/MaterialTaskListRoute', {
                method: 'POST',
                body: JSON.stringify({ material_id: materialId }),
            });
            if (resp.ok) {
                setTasksMap(prev => new Map(prev).set(materialId, data as MaterialTask[]));
            } else {
                reportHttpError('加载任务状态失败', resp);
            }
        } catch (err) {
            print_error({ reason: '加载任务状态失败', err });
        }
    };

    // Fetch latest task per type for the currently open modal (MaterialActiveTasksRoute)
    const fetchModalTasks = useCallback(async (materialId: string) => {
        try {
            const { resp, data } = await apiFetch(
                `/api/v1/MaterialActiveTasksRoute?material_id=${encodeURIComponent(materialId)}`,
                { method: 'GET' },
                { silent: true },
            );
            if (resp.ok) {
                setModalWorkerTasks(data as WorkerTask[]);
            }
        } catch { /* ignore polling errors */ }
    }, [apiFetch]);

    // Auto-poll task status while the modal is open
    useEffect(() => {
        if (!actionTarget) return;
        setModalTasksLoading(true);
        fetchModalTasks(actionTarget.id).finally(() => setModalTasksLoading(false));
        const id = setInterval(() => fetchModalTasks(actionTarget.id), MODAL_TASK_POLL_MS);
        return () => clearInterval(id);
    }, [actionTarget, fetchModalTasks]);

    // ── Imperative actions ──────────────────────────────────────────────────

    const handleDeleteVideo = async (id: string) => {
        setDeletingVideoIds(prev => new Set([...prev, id]));
        try {
            const { resp } = await apiFetch('/api/v1/MaterialDeleteRoute', {
                method: 'POST',
                body: JSON.stringify({ ids: [id] }),
            });
            if (resp.ok) {
                await fetchData();
            } else {
                reportHttpError('删除素材失败', resp);
            }
        } finally {
            setDeletingVideoIds(prev => { const n = new Set(prev); n.delete(id); return n; });
        }
    };

    const handleDeleteCategory = async (id: string) => {
        setDeletingCategoryIds(prev => new Set([...prev, id]));
        try {
            const { resp } = await apiFetch('/api/v1/MaterialCategoryDeleteRoute', {
                method: 'POST',
                body: JSON.stringify({ id }),
            });
            if (resp.ok) {
                if (selectedCategoryId === id) setSelectedCategoryId(null);
                await fetchData();
            } else {
                reportHttpError('删除分类失败', resp);
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
                body: JSON.stringify({ name }),
            });
            if (resp.ok) {
                setNewCategoryName('');
                await fetchData();
            } else {
                reportHttpError('创建分类失败', resp);
            }
        } finally {
            setCreatingCategory(false);
        }
    };

    const handleOpenAction = (video: MaterialVideo) => {
        setActionTarget(video);
        setModalTab('basics');
        setModalWorkerTasks([]);
        setRunningTaskType(null);
        // Polling is handled by the useEffect that watches actionTarget
    };

    const handleCloseAction = () => {
        setActionTarget(null);
        setRunningTaskType(null);
        setCancellingPipelineId(null);
        setPausingTaskId(null);
    };

    const handleRunTask = async (taskType: string) => {
        if (!actionTarget || runningTaskType) return;
        setRunningTaskType(taskType);
        try {
            const { resp } = await apiFetch('/api/v1/MaterialRunTaskRoute', {
                method: 'POST',
                body: JSON.stringify({ material_id: actionTarget.id, task_type: taskType }),
            });
            if (resp.ok) {
                await fetchModalTasks(actionTarget.id);
            } else {
                reportHttpError('提交任务失败', resp);
            }
        } finally {
            setRunningTaskType(null);
        }
    };

    const handleCancelDownload = async (taskId: string) => {
        if (cancellingPipelineId) return;
        setCancellingPipelineId(taskId);
        try {
            const { resp } = await apiFetch('/api/v1/TaskCancelRoute', {
                method: 'POST',
                body: JSON.stringify({ task_id: taskId }),
            });
            if (resp.ok) {
                if (actionTarget) await fetchModalTasks(actionTarget.id);
            } else {
                reportHttpError('取消下载失败', resp);
            }
        } finally {
            setCancellingPipelineId(null);
        }
    };

    const handlePauseTask = async (taskId: string) => {
        if (pausingTaskId) return;
        setPausingTaskId(taskId);
        try {
            const { resp } = await apiFetch('/api/v1/TaskPauseRoute', {
                method: 'POST',
                body: JSON.stringify({ task_id: taskId }),
            });
            if (resp.ok) {
                if (actionTarget) await fetchModalTasks(actionTarget.id);
            } else {
                reportHttpError('暂停任务失败', resp);
            }
        } finally {
            setPausingTaskId(null);
        }
    };

    const handleResumeTask = async (taskId: string) => {
        if (pausingTaskId) return;
        setPausingTaskId(taskId);
        try {
            const { resp } = await apiFetch('/api/v1/TaskResumeRoute', {
                method: 'POST',
                body: JSON.stringify({ task_id: taskId }),
            });
            if (resp.ok) {
                if (actionTarget) await fetchModalTasks(actionTarget.id);
            } else {
                reportHttpError('恢复任务失败', resp);
            }
        } finally {
            setPausingTaskId(null);
        }
    };

    // ── Filtering ───────────────────────────────────────────────────────────

    const filtered = (videos ?? []).filter(v => {
        if (selectedCategoryId && !v.category_ids.includes(selectedCategoryId)) return false;
        // task_id filter: undefined = resolving (show all); null = no filter OR task not found
        if (taskIdParam && taskFilterMaterialId !== null && taskFilterMaterialId !== undefined) {
            if (v.id !== taskFilterMaterialId) return false;
        }
        if (taskIdParam && taskFilterMaterialId === null) return false; // task not found
        return true;
    });

    // Reset to page 1 when category filter changes
    useEffect(() => { setPage(1); }, [selectedCategoryId]);

    // Client-side pagination
    const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
    const pagedVideos = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

    // Validate that the selected category still exists after a refresh
    const categoryExists = categories?.some(c => c.id === selectedCategoryId) ?? true;
    const effectiveCategoryId = categoryExists ? selectedCategoryId : null;

    // ── Render ──────────────────────────────────────────────────────────────

    return (
        <>
            {/* ── Action modal ── */}
            {actionTarget && (
                <div className="fixed inset-0 z-50 flex items-center justify-center">
                    <div className="absolute inset-0 bg-black/40" onClick={handleCloseAction} />
                    <div className="relative bg-white rounded-xl shadow-xl w-full max-w-md mx-4">

                        {/* Header */}
                        <div className="flex items-start justify-between gap-2 px-5 pt-5 pb-3">
                            <div className="min-w-0">
                                <p className="text-xs text-gray-400 mb-0.5">操作</p>
                                <h2 className="text-sm font-semibold text-gray-900 line-clamp-2">
                                    {actionTarget.title || actionTarget.source_id}
                                </h2>
                            </div>
                            <button
                                onClick={handleCloseAction}
                                className="flex-shrink-0 p-1 rounded-lg hover:bg-gray-100 transition-colors"
                            >
                                <X className="w-4 h-4 text-gray-500" />
                            </button>
                        </div>

                        {/* Tab bar */}
                        <div className="flex gap-1 border-b border-gray-200 px-4">
                            {MODAL_TABS.map(tab => (
                                <button
                                    key={tab.key}
                                    onClick={() => setModalTab(tab.key)}
                                    className={`px-3 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${modalTab === tab.key
                                        ? 'border-blue-600 text-blue-600'
                                        : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                                        }`}
                                >
                                    {tab.label}
                                </button>
                            ))}
                        </div>

                        {/* Body */}
                        <div className="px-5 py-4">
                            {modalTab === 'basics' ? (
                                modalTasksLoading ? (
                                    <div className="flex justify-center py-6">
                                        <Loader className="w-5 h-5 animate-spin text-gray-400" />
                                    </div>
                                ) : (
                                    <div className="space-y-2">
                                        {/* Bilibili: 仅下载 */}
                                        {actionTarget.source_type === 'bilibili' && (() => {
                                            const activeDownload = modalWorkerTasks.find(
                                                t => t.type === 'DOWNLOAD_BILIBILI_VIDEO' &&
                                                    ['pending', 'claimed', 'running'].includes(t.status)
                                            );
                                            const isDisabled = !!activeDownload || runningTaskType === 'DOWNLOAD_BILIBILI_VIDEO';
                                            return (
                                                <div className="flex items-center justify-between gap-3 py-1.5">
                                                    <span className="text-sm text-gray-700">视频下载</span>
                                                    <div className="flex items-center gap-2">
                                                        {activeDownload && activeDownload.status === 'running' && (
                                                            activeDownload.is_paused ? (
                                                                <button
                                                                    onClick={() => handleResumeTask(activeDownload.id)}
                                                                    disabled={pausingTaskId === activeDownload.id}
                                                                    className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-green-700 bg-green-50 rounded-lg hover:bg-green-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                                                >
                                                                    {pausingTaskId === activeDownload.id
                                                                        ? <Loader className="w-3 h-3 animate-spin" />
                                                                        : <Play className="w-3 h-3" />
                                                                    }
                                                                    恢复
                                                                </button>
                                                            ) : (
                                                                <button
                                                                    onClick={() => handlePauseTask(activeDownload.id)}
                                                                    disabled={pausingTaskId === activeDownload.id}
                                                                    className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-amber-700 bg-amber-50 rounded-lg hover:bg-amber-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                                                >
                                                                    {pausingTaskId === activeDownload.id
                                                                        ? <Loader className="w-3 h-3 animate-spin" />
                                                                        : <Pause className="w-3 h-3" />
                                                                    }
                                                                    暂停
                                                                </button>
                                                            )
                                                        )}
                                                        {activeDownload && (
                                                            <button
                                                                onClick={() => handleCancelDownload(activeDownload.id)}
                                                                disabled={cancellingPipelineId === activeDownload.id}
                                                                className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                                            >
                                                                {cancellingPipelineId === activeDownload.id
                                                                    ? <Loader className="w-3 h-3 animate-spin" />
                                                                    : <X className="w-3 h-3" />
                                                                }
                                                                取消
                                                            </button>
                                                        )}
                                                        <button
                                                            onClick={() => handleRunTask('DOWNLOAD_BILIBILI_VIDEO')}
                                                            disabled={isDisabled}
                                                            className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                                        >
                                                            {isDisabled
                                                                ? <Loader className="w-3 h-3 animate-spin" />
                                                                : <Download className="w-3 h-3" />
                                                            }
                                                            {activeDownload ? '下载中…' : '仅下载'}
                                                        </button>
                                                    </div>
                                                </div>
                                            );
                                        })()}

                                        {/* Task progress list — only show known task types */}
                                        {modalWorkerTasks.some(t => t.type in TASK_TYPE_LABELS) && (
                                            <div className="space-y-1.5 pt-1">
                                                {modalWorkerTasks.filter(t => t.type in TASK_TYPE_LABELS).map(task => {
                                                    const statusInfo = WORKER_TASK_STATUS[task.status]
                                                        ?? { label: task.status, className: 'bg-gray-100 text-gray-600' };
                                                    const typeLabel = TASK_TYPE_LABELS[task.type] ?? task.type;
                                                    const showBar = task.status === 'running' || task.status === 'claimed' || task.status === 'completed';
                                                    const barPct = task.status === 'completed' ? 100 : task.progress;
                                                    return (
                                                        <div key={task.id} className="space-y-0.5">
                                                            <div className="flex items-center justify-between gap-2">
                                                                <span className="text-xs text-gray-600">{typeLabel}</span>
                                                                <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded whitespace-nowrap ${statusInfo.className}`}>
                                                                    {statusInfo.label}{task.status === 'running' && task.progress > 0 ? ` ${task.progress}%` : ''}
                                                                </span>
                                                            </div>
                                                            {showBar && (
                                                                <div className="h-1 bg-gray-100 rounded-full overflow-hidden">
                                                                    <div
                                                                        className={`h-full rounded-full transition-all ${task.status === 'completed' ? 'bg-green-500' : 'bg-blue-500'}`}
                                                                        style={{ width: `${barPct}%` }}
                                                                    />
                                                                </div>
                                                            )}
                                                            {task.status === 'failed' && task.error && (
                                                                <p className="text-[10px] text-red-500 truncate">{task.error}</p>
                                                            )}
                                                        </div>
                                                    );
                                                })}
                                            </div>
                                        )}

                                        <div className="pt-3 border-t border-gray-100">
                                            <Link
                                                to={`/tasks?material_id=${encodeURIComponent(actionTarget.id)}`}
                                                className="text-xs text-blue-600 hover:underline"
                                                onClick={handleCloseAction}
                                            >
                                                前往任务中心查看详情 →
                                            </Link>
                                        </div>

                                        {/* Danger zone */}
                                        <div className="pt-3 border-t border-red-100">
                                            <button
                                                onClick={async () => {
                                                    handleCloseAction();
                                                    await handleDeleteVideo(actionTarget.id);
                                                }}
                                                disabled={deletingVideoIds.has(actionTarget.id)}
                                                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                            >
                                                {deletingVideoIds.has(actionTarget.id)
                                                    ? <Loader className="w-3 h-3 animate-spin" />
                                                    : <Trash2 className="w-3 h-3" />
                                                }
                                                移除素材库（但不删除数据）
                                            </button>
                                        </div>
                                    </div>
                                )
                            ) : (
                                <div className="py-8 text-center text-sm text-gray-400">
                                    一键流程功能即将推出
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            )}
            <SidebarLayout>
                <div className="max-w-4xl mx-auto p-4 sm:p-6 space-y-4">

                    {/* ── Header ── */}
                    <div className="flex items-center justify-between">
                        <div>
                            <h1 className="text-xl font-semibold text-gray-900">素材库</h1>
                            {videos && (
                                <p className="text-sm text-gray-500 mt-0.5">共 {videos.length} 个视频 · 每 {POLL_INTERVAL_MS / 1000}s 自动刷新</p>
                            )}
                        </div>
                        <button
                            onClick={handleManualRefresh}
                            disabled={refreshing}
                            className="p-2 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors disabled:opacity-50"
                            title="立即刷新"
                        >
                            <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
                        </button>
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
                                className={`px-3 py-1 text-xs font-medium rounded-full transition-colors ${!effectiveCategoryId
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
                                        className={`inline-flex items-center gap-1 pl-3 pr-1.5 py-1 text-xs font-medium rounded-full transition-colors ${isActive
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
                                            className={`rounded-full p-0.5 transition-colors ${isActive ? 'hover:bg-blue-500' : 'hover:bg-gray-300'
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

                        {/* Task ID filter */}
                        <div className="flex gap-2 pt-1 border-t border-gray-100">
                            <input
                                type="text"
                                value={taskIdParam}
                                onChange={e => {
                                    setSearchParams(prev => {
                                        const n = new URLSearchParams(prev);
                                        if (e.target.value) n.set('task_id', e.target.value);
                                        else n.delete('task_id');
                                        return n;
                                    });
                                }}
                                placeholder="按任务 ID 筛选素材…"
                                className="flex-1 px-3 py-1.5 text-sm border border-gray-200 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none"
                            />
                            {taskIdParam && (
                                <button
                                    onClick={() => setSearchParams(prev => {
                                        const n = new URLSearchParams(prev);
                                        n.delete('task_id');
                                        return n;
                                    })}
                                    className="flex items-center gap-1 px-3 py-1.5 text-sm text-gray-500 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors"
                                >
                                    <X className="w-3.5 h-3.5" />
                                    清除
                                </button>
                            )}
                        </div>
                        {taskIdParam && (
                            <p className="text-xs text-purple-600 mt-0.5">
                                {taskFilterMaterialId === undefined
                                    ? '正在解析任务…'
                                    : taskFilterMaterialId === null
                                        ? '未找到该任务对应的素材'
                                        : `已筛选至素材：${taskFilterMaterialId}`
                                }
                            </p>
                        )}
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
                            {pagedVideos.map(video => {
                                const sourceBadge = SOURCE_BADGE[video.source_type]
                                    ?? { label: video.source_type, className: 'bg-gray-100 text-gray-600' };
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
                                } catch { /* TODO */ }

                                const bilibiliPage = video.source_type === 'bilibili'
                                    ? parseInt(video.id.match(/__P(\d+)$/)?.[1] ?? '1', 10)
                                    : 1;
                                const bilibiliUrl = video.source_type === 'bilibili'
                                    ? `https://www.bilibili.com/video/${video.source_id}${bilibiliPage > 1 ? `?p=${bilibiliPage}` : ''}`
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

                                            {/* Source + category badges */}
                                            <div className="flex items-center gap-1.5 flex-wrap">
                                                <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${sourceBadge.className}`}>
                                                    {sourceBadge.label}
                                                </span>
                                                {video.source_type === 'bilibili' && video.id in downloadStatusMap && (
                                                    <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${
                                                        downloadStatusMap[video.id]
                                                            ? 'bg-green-100 text-green-700'
                                                            : 'bg-gray-100 text-gray-500'
                                                    }`}>
                                                        {downloadStatusMap[video.id] ? '已下载' : '未下载'}
                                                    </span>
                                                )}
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
                                                onClick={() => handleOpenAction(video)}
                                                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors whitespace-nowrap"
                                            >
                                                <Settings className="w-3.5 h-3.5" />
                                                操作
                                            </button>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    )}

                    {/* ── Pagination ── */}
                    {totalPages > 1 && (
                        <div className="flex items-center justify-center gap-3">
                            <button
                                onClick={() => setPage(p => Math.max(1, p - 1))}
                                disabled={page <= 1}
                                className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                            >
                                ← 上一页
                            </button>
                            <span className="text-xs text-gray-500 tabular-nums">
                                第 {page} / {totalPages} 页（共 {filtered.length} 个）
                            </span>
                            <button
                                onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                                disabled={page >= totalPages}
                                className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                            >
                                下一页 →
                            </button>
                        </div>
                    )}
                </div>
            </SidebarLayout>
        </>
    );
}
