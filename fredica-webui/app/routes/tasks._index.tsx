import { useState, useEffect, useCallback } from "react";
import { useSearchParams, Link } from "react-router";
import { Loader, X, RefreshCw, ArrowUpDown, Pause, Play, Library } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { reportHttpError } from "~/util/error_handler";

// ─── Types ────────────────────────────────────────────────────────────────────

interface WorkerTask {
    id: string;
    type: string;
    pipeline_id: string;
    material_id: string;
    status: string;
    priority: number;
    retry_count: number;
    max_retries: number;
    error: string | null;
    error_type: string | null;
    progress: number;
    is_paused: boolean;
    created_at: number;
    claimed_at: number | null;
    started_at: number | null;
    completed_at: number | null;
}

interface TaskListResult {
    items: WorkerTask[];
    total: number;
}

interface MaterialCategory {
    id: string;
    name: string;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const TASK_STATUS: Record<string, { label: string; dot: string; className: string }> = {
    pending:   { label: '等待中',  dot: 'bg-yellow-400',              className: 'bg-yellow-100 text-yellow-700'  },
    claimed:   { label: '排队中',  dot: 'bg-yellow-400',              className: 'bg-yellow-100 text-yellow-700'  },
    running:   { label: '运行中',  dot: 'bg-blue-500 animate-pulse',  className: 'bg-blue-100 text-blue-700'      },
    completed: { label: '已完成',  dot: 'bg-green-500',               className: 'bg-green-100 text-green-700'   },
    failed:    { label: '失败',    dot: 'bg-red-500',                 className: 'bg-red-100 text-red-700'       },
    cancelled: { label: '已取消',  dot: 'bg-gray-300',                className: 'bg-gray-100 text-gray-400'    },
};

const TASK_TYPE_LABELS: Record<string, string> = {
    DOWNLOAD_BILIBILI_VIDEO:    '下载视频',
    DOWNLOAD_BILIBILI_SUBTITLE: '下载字幕',
    EXTRACT_AUDIO:              '提取音频',
    SPLIT_AUDIO:                '切割音频',
    TRANSCRIBE_CHUNK:           '语音识别',
    MERGE_TRANSCRIPTION:        '合并字幕',
    AI_ANALYZE:                 'AI 分析',
};

const STATUS_FILTER_OPTIONS = [
    { key: '',          label: '全部'   },
    { key: 'pending',   label: '等待中' },
    { key: 'running',   label: '运行中' },
    { key: 'paused',    label: '暂停中' },
    { key: 'completed', label: '已完成' },
    { key: 'failed',    label: '失败'   },
    { key: 'cancelled', label: '已取消' },
];

const POLL_INTERVAL_MS = 3_000;
const ACTIVE_STATUSES = new Set(['pending', 'claimed', 'running']);
const PAGE_SIZE = 20;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatTs(ts: number | null): string {
    if (!ts) return '—';
    return new Date(ts * 1000).toLocaleString('zh-CN', { hour12: false });
}

function formatMaterialId(materialId: string): string {
    const match = materialId.match(/bilibili_bvid__(.+)__P(\d+)/);
    if (match) return `${match[1]} P${match[2]}`;
    return materialId;
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function TasksPage() {
    const [searchParams, setSearchParams] = useSearchParams();

    const statusFilter     = searchParams.get('status')      ?? '';
    const materialIdFilter = searchParams.get('material_id') ?? '';
    const categoryFilter   = searchParams.get('category_id') ?? '';
    const page             = Math.max(1, parseInt(searchParams.get('page') ?? '1', 10));
    const sortDesc         = (searchParams.get('sort') ?? 'desc') !== 'asc';

    const [tasks,         setTasks]         = useState<WorkerTask[]>([]);
    const [total,         setTotal]         = useState(0);
    const [categories,    setCategories]    = useState<MaterialCategory[]>([]);
    const [refreshing,    setRefreshing]    = useState(false);
    const [cancellingIds, setCancellingIds] = useState<Set<string>>(new Set());
    const [pausingIds,    setPausingIds]    = useState<Set<string>>(new Set());

    const { apiFetch } = useAppFetch();

    // ── Helpers to update URL params ─────────────────────────────────────────

    const setParam = (key: string, value: string) => {
        setSearchParams(prev => {
            const next = new URLSearchParams(prev);
            if (value) next.set(key, value); else next.delete(key);
            if (key !== 'page') next.delete('page');
            return next;
        });
    };

    const setPage = (p: number) => {
        setSearchParams(prev => {
            const next = new URLSearchParams(prev);
            if (p > 1) next.set('page', String(p)); else next.delete('page');
            return next;
        });
    };

    // ── Fetch categories once ─────────────────────────────────────────────────

    useEffect(() => {
        apiFetch('/api/v1/MaterialCategoryListRoute', { method: 'POST', body: '{}' }, { silent: true })
            .then(({ data }) => { if (Array.isArray(data)) setCategories(data as MaterialCategory[]); })
            .catch(() => {});
    }, [apiFetch]);

    // ── Fetch tasks (polled) ──────────────────────────────────────────────────

    const refresh = useCallback(async () => {
        try {
            const params = new URLSearchParams();
            if (statusFilter)     params.set('status',      statusFilter);
            if (materialIdFilter) params.set('material_id', materialIdFilter);
            if (categoryFilter)   params.set('category_id', categoryFilter);
            params.set('page',      String(page));
            params.set('page_size', String(PAGE_SIZE));
            params.set('sort',      sortDesc ? 'desc' : 'asc');

            const { data } = await apiFetch(
                `/api/v1/WorkerTaskListRoute?${params.toString()}`,
                { method: 'GET' },
                { silent: true },
            );
            const result = data as TaskListResult;
            if (result && Array.isArray(result.items)) {
                setTasks(result.items);
                setTotal(result.total);
            }
        } catch { /* silent on poll failure */ }
    }, [apiFetch, statusFilter, materialIdFilter, categoryFilter, page, sortDesc]);

    useEffect(() => {
        refresh();
        const id = setInterval(refresh, POLL_INTERVAL_MS);
        return () => clearInterval(id);
    }, [refresh]);

    // ── Actions ───────────────────────────────────────────────────────────────

    const handleRefresh = async () => {
        setRefreshing(true);
        try { await refresh(); } finally { setRefreshing(false); }
    };

    const handleCancel = async (taskId: string) => {
        setCancellingIds(prev => new Set([...prev, taskId]));
        try {
            const { resp } = await apiFetch('/api/v1/TaskCancelRoute', {
                method: 'POST', body: JSON.stringify({ task_id: taskId }),
            });
            if (resp.ok) await refresh(); else reportHttpError('取消任务失败', resp);
        } finally {
            setCancellingIds(prev => { const n = new Set(prev); n.delete(taskId); return n; });
        }
    };

    const handlePause = async (taskId: string) => {
        setPausingIds(prev => new Set([...prev, taskId]));
        try {
            const { resp } = await apiFetch('/api/v1/TaskPauseRoute', {
                method: 'POST', body: JSON.stringify({ task_id: taskId }),
            });
            if (resp.ok) await refresh(); else reportHttpError('暂停任务失败', resp);
        } finally {
            setPausingIds(prev => { const n = new Set(prev); n.delete(taskId); return n; });
        }
    };

    const handleResume = async (taskId: string) => {
        setPausingIds(prev => new Set([...prev, taskId]));
        try {
            const { resp } = await apiFetch('/api/v1/TaskResumeRoute', {
                method: 'POST', body: JSON.stringify({ task_id: taskId }),
            });
            if (resp.ok) await refresh(); else reportHttpError('恢复任务失败', resp);
        } finally {
            setPausingIds(prev => { const n = new Set(prev); n.delete(taskId); return n; });
        }
    };

    // ── Derived ───────────────────────────────────────────────────────────────

    const totalPages  = Math.max(1, Math.ceil(total / PAGE_SIZE));
    const statusLabel = STATUS_FILTER_OPTIONS.find(f => f.key === statusFilter)?.label ?? '';

    // ── Render ────────────────────────────────────────────────────────────────

    return (
        <SidebarLayout>
            <div className="max-w-4xl mx-auto p-4 sm:p-6 space-y-4">

                {/* Header */}
                <div className="flex items-center justify-between gap-3">
                    <div>
                        <h1 className="text-xl font-semibold text-gray-900">任务中心</h1>
                        <p className="text-sm text-gray-500 mt-0.5">
                            共 {total} 个任务 · 每 {POLL_INTERVAL_MS / 1000}s 自动刷新
                        </p>
                    </div>
                    <button
                        onClick={handleRefresh}
                        disabled={refreshing}
                        className="p-2 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors disabled:opacity-50 flex-shrink-0"
                        title="立即刷新"
                    >
                        <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
                    </button>
                </div>

                {/* Filter panel */}
                <div className="bg-white rounded-lg border border-gray-200 p-4 space-y-3">

                    {/* Status tabs */}
                    <div className="flex flex-wrap gap-1.5">
                        {STATUS_FILTER_OPTIONS.map(({ key, label }) => (
                            <button
                                key={key}
                                onClick={() => setParam('status', key)}
                                className={`px-3 py-1.5 text-xs rounded-lg font-medium transition-colors ${
                                    statusFilter === key
                                        ? 'bg-blue-600 text-white'
                                        : 'bg-gray-50 border border-gray-200 text-gray-500 hover:bg-gray-100'
                                }`}
                            >
                                {label}
                            </button>
                        ))}
                    </div>

                    {/* Material ID + Category + Sort */}
                    <div className="flex gap-2 flex-wrap">
                        <input
                            type="text"
                            value={materialIdFilter}
                            onChange={e => setParam('material_id', e.target.value)}
                            placeholder="素材 ID 筛选…"
                            className="flex-1 min-w-36 px-3 py-1.5 text-xs border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                        />
                        <select
                            value={categoryFilter}
                            onChange={e => setParam('category_id', e.target.value)}
                            className="min-w-32 px-3 py-1.5 text-xs border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 outline-none bg-white"
                        >
                            <option value="">全部分组</option>
                            {categories.map(cat => (
                                <option key={cat.id} value={cat.id}>{cat.name}</option>
                            ))}
                        </select>
                        <button
                            onClick={() => setParam('sort', sortDesc ? 'asc' : 'desc')}
                            className="flex items-center gap-1.5 px-3 py-1.5 text-xs border border-gray-200 rounded-lg text-gray-500 hover:bg-gray-50 transition-colors whitespace-nowrap"
                            title={sortDesc ? '当前：最新优先' : '当前：最旧优先'}
                        >
                            <ArrowUpDown className="w-3 h-3" />
                            {sortDesc ? '最新优先' : '最旧优先'}
                        </button>
                    </div>

                    {/* Active filter chips */}
                    {(materialIdFilter || categoryFilter) && (
                        <div className="flex flex-wrap gap-1.5 pt-0.5">
                            {materialIdFilter && (
                                <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-50 border border-blue-200 rounded-md text-xs text-blue-700">
                                    素材：{materialIdFilter.slice(0, 20)}{materialIdFilter.length > 20 ? '…' : ''}
                                    <button onClick={() => setParam('material_id', '')}><X className="w-2.5 h-2.5" /></button>
                                </span>
                            )}
                            {categoryFilter && categories.find(c => c.id === categoryFilter) && (
                                <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-indigo-50 border border-indigo-200 rounded-md text-xs text-indigo-700">
                                    分组：{categories.find(c => c.id === categoryFilter)?.name}
                                    <button onClick={() => setParam('category_id', '')}><X className="w-2.5 h-2.5" /></button>
                                </span>
                            )}
                        </div>
                    )}
                </div>

                {/* Empty state */}
                {tasks.length === 0 && (
                    <div className="bg-white rounded-lg border border-gray-200 p-10 text-center text-sm text-gray-400">
                        {statusLabel ? `没有「${statusLabel}」状态的任务` : '暂无任务'}
                    </div>
                )}

                {/* Task list */}
                {tasks.length > 0 && (
                    <div className="bg-white rounded-lg border border-gray-200 divide-y divide-gray-100">
                        {tasks.map(task => {
                            const isPaused    = task.is_paused && task.status === 'running';
                            const ts          = TASK_STATUS[task.status]
                                ?? { label: task.status, dot: 'bg-gray-300', className: 'bg-gray-100 text-gray-600' };
                            const statusInfo  = isPaused
                                ? { label: '已暂停', dot: 'bg-amber-400', className: 'bg-amber-100 text-amber-700' }
                                : ts;
                            const typeLabel   = TASK_TYPE_LABELS[task.type] ?? task.type;
                            const canCancel   = ACTIVE_STATUSES.has(task.status);
                            const canPause    = task.status === 'running' && !task.is_paused;
                            const canResume   = task.status === 'running' && task.is_paused;
                            const isCancelling = cancellingIds.has(task.id);
                            const isPausing   = pausingIds.has(task.id);
                            const showProgress = (task.status === 'running' || task.status === 'claimed') && task.progress > 0;

                            return (
                                <div key={task.id} className="px-4 py-3">
                                    <div className="flex items-start gap-3">
                                        <span className={`mt-1.5 w-2 h-2 rounded-full flex-shrink-0 ${statusInfo.dot}`} />

                                        <div className="flex-1 min-w-0 space-y-1">
                                            {/* Title row */}
                                            <div className="flex items-center gap-2 flex-wrap">
                                                <span className="text-sm font-medium text-gray-900">{typeLabel}</span>
                                                <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${statusInfo.className}`}>
                                                    {statusInfo.label}
                                                    {task.status === 'running' && !task.is_paused && task.progress > 0
                                                        ? ` ${task.progress}%` : ''}
                                                </span>
                                                {task.retry_count > 0 && (
                                                    <span className="text-[10px] text-orange-500">
                                                        重试 {task.retry_count}/{task.max_retries}
                                                    </span>
                                                )}
                                            </div>

                                            {/* Material ID */}
                                            <p className="text-xs text-gray-400 font-mono truncate">
                                                {formatMaterialId(task.material_id)}
                                            </p>

                                            {/* Progress bar */}
                                            {showProgress && (
                                                <div className="flex items-center gap-2 pt-0.5">
                                                    <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                                                        <div
                                                            className={`h-full rounded-full transition-all duration-300 ${isPaused ? 'bg-amber-400' : 'bg-blue-500'}`}
                                                            style={{ width: `${task.progress}%` }}
                                                        />
                                                    </div>
                                                    <span className="text-xs text-gray-500 tabular-nums w-8 text-right">{task.progress}%</span>
                                                </div>
                                            )}

                                            {/* Error */}
                                            {task.error && (
                                                <p className="text-xs text-red-500" title={task.error}>
                                                    [{task.error_type}] {task.error.slice(0, 120)}
                                                </p>
                                            )}

                                            {/* Timestamps */}
                                            <p className="text-xs text-gray-400">
                                                创建于 {formatTs(task.created_at)}
                                                {task.completed_at && ` · 完成于 ${formatTs(task.completed_at)}`}
                                            </p>
                                        </div>

                                        {/* Action buttons */}
                                        <div className="flex flex-col gap-1.5 flex-shrink-0 items-stretch">
                                            {canPause && (
                                                <button
                                                    onClick={() => handlePause(task.id)}
                                                    disabled={isPausing}
                                                    className="flex items-center justify-center gap-1 px-2.5 py-1.5 text-xs font-medium text-amber-700 bg-amber-50 rounded-lg hover:bg-amber-100 transition-colors disabled:opacity-50"
                                                >
                                                    {isPausing ? <Loader className="w-3 h-3 animate-spin" /> : <Pause className="w-3 h-3" />}
                                                    暂停
                                                </button>
                                            )}
                                            {canResume && (
                                                <button
                                                    onClick={() => handleResume(task.id)}
                                                    disabled={isPausing}
                                                    className="flex items-center justify-center gap-1 px-2.5 py-1.5 text-xs font-medium text-green-700 bg-green-50 rounded-lg hover:bg-green-100 transition-colors disabled:opacity-50"
                                                >
                                                    {isPausing ? <Loader className="w-3 h-3 animate-spin" /> : <Play className="w-3 h-3" />}
                                                    恢复
                                                </button>
                                            )}
                                            {canCancel && (
                                                <button
                                                    onClick={() => handleCancel(task.id)}
                                                    disabled={isCancelling}
                                                    className="flex items-center justify-center gap-1 px-2.5 py-1.5 text-xs font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 transition-colors disabled:opacity-50"
                                                >
                                                    {isCancelling ? <Loader className="w-3 h-3 animate-spin" /> : <X className="w-3 h-3" />}
                                                    取消
                                                </button>
                                            )}
                                            <Link
                                                to={`/material-library?task_id=${encodeURIComponent(task.id)}`}
                                                className="flex items-center justify-center gap-1 px-2.5 py-1.5 text-xs font-medium text-gray-600 bg-gray-50 border border-gray-200 rounded-lg hover:bg-gray-100 transition-colors"
                                            >
                                                <Library className="w-3 h-3" />
                                                素材库
                                            </Link>
                                        </div>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                )}

                {/* Pagination */}
                {totalPages > 1 && (
                    <div className="flex items-center justify-center gap-3">
                        <button
                            onClick={() => setPage(page - 1)}
                            disabled={page <= 1}
                            className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                        >
                            ← 上一页
                        </button>
                        <span className="text-xs text-gray-500 tabular-nums">
                            第 {page} / {totalPages} 页（共 {total} 个）
                        </span>
                        <button
                            onClick={() => setPage(page + 1)}
                            disabled={page >= totalPages}
                            className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                        >
                            下一页 →
                        </button>
                    </div>
                )}

            </div>
        </SidebarLayout>
    );
}
