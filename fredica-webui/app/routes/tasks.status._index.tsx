import { useState, useEffect, useCallback } from "react";
import { useSearchParams } from "react-router";
import { X, RefreshCw, ArrowUpDown } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { WorkflowInfoPanel } from "~/components/ui/WorkflowInfoPanel";

// ─── Types ────────────────────────────────────────────────────────────────────

interface TaskSummary {
    workflow_run_id?: string;
    material_id?: string;
    status: string;
}

interface TaskListResult {
    items: TaskSummary[];
    total: number;
}

interface MaterialCategory {
    id: string;
    name: string;
}

// ─── Constants ────────────────────────────────────────────────────────────────

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
const PAGE_SIZE = 20;

function formatMaterialId(materialId: string): string {
    const match = materialId.match(/bilibili_bvid__(.+)__P(\d+)/);
    if (match) return `${match[1]} P${match[2]}`;
    return materialId;
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function TasksStatusPage() {
    const [searchParams, setSearchParams] = useSearchParams();

    const statusFilter     = searchParams.get('status')      ?? '';
    const materialIdFilter = searchParams.get('material_id') ?? '';
    const categoryFilter   = searchParams.get('category_id') ?? '';
    const page             = Math.max(1, parseInt(searchParams.get('page') ?? '1', 10));
    const sortDesc         = (searchParams.get('sort') ?? 'desc') !== 'asc';

    const [tasks,         setTasks]         = useState<TaskSummary[]>([]);
    const [total,         setTotal]         = useState(0);
    const [categories,    setCategories]    = useState<MaterialCategory[]>([]);
    const [materialTitles, setMaterialTitles] = useState<Record<string, string>>({});
    const [refreshing,    setRefreshing]    = useState(false);

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

    // ── Fetch categories + material titles once ───────────────────────────────

    useEffect(() => {
        apiFetch('/api/v1/MaterialCategoryListRoute', { method: 'POST', body: '{}' }, { silent: true })
            .then(({ data }) => { if (Array.isArray(data)) setCategories(data as MaterialCategory[]); })
            .catch(() => {});

        apiFetch('/api/v1/MaterialListRoute', { method: 'POST', body: '{}' }, { silent: true })
            .then(({ data }) => {
                if (Array.isArray(data)) {
                    const map: Record<string, string> = {};
                    (data as Array<{ id: string; title: string }>).forEach(m => { map[m.id] = m.title; });
                    setMaterialTitles(map);
                }
            })
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


    // ── Derived ───────────────────────────────────────────────────────────────

    const totalPages  = Math.max(1, Math.ceil(total / PAGE_SIZE));
    const statusLabel = STATUS_FILTER_OPTIONS.find(f => f.key === statusFilter)?.label ?? '';

    // 按 workflow_run_id 分组；无 wf 的按 material_id 分组
    const workflowGroups = new Map<string, { materialId: string; isActive: boolean }>();
    const materialGroups = new Map<string, { isActive: boolean }>();
    for (const task of tasks) {
        const active = task.status === 'running' || task.status === 'pending' || task.status === 'claimed';
        if (task.workflow_run_id) {
            const prev = workflowGroups.get(task.workflow_run_id);
            workflowGroups.set(task.workflow_run_id, {
                materialId: task.material_id as string ?? '',
                isActive: (prev?.isActive ?? false) || active,
            });
        } else if (task.material_id) {
            const prev = materialGroups.get(task.material_id);
            materialGroups.set(task.material_id, { isActive: (prev?.isActive ?? false) || active });
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    return (
        <SidebarLayout>
            <div className="max-w-4xl mx-auto p-4 sm:p-6 space-y-4">

                {/* Header */}
                <div className="flex items-center justify-between gap-3">
                    <div>
                        <h1 className="text-xl font-semibold text-gray-900">任务状态</h1>
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
                    <div className="space-y-3">
                        {[...workflowGroups.entries()].map(([wfId, { materialId, isActive }]) => (
                            <div key={wfId} className="bg-white rounded-lg border border-gray-200">
                                <div className="px-4 pt-3 pb-1 border-b border-gray-100">
                                    <p className="text-xs font-medium text-gray-500 truncate">
                                        {materialTitles[materialId] ?? formatMaterialId(materialId)}
                                    </p>
                                    <p className="text-[10px] text-gray-300 font-mono truncate mt-0.5">{wfId}</p>
                                </div>
                                <div className="px-4">
                                    <WorkflowInfoPanel workflowRunId={wfId} active={isActive} />
                                </div>
                            </div>
                        ))}
                        {[...materialGroups.entries()].map(([materialId, { isActive }]) => (
                            <div key={materialId} className="bg-white rounded-lg border border-gray-200">
                                <div className="px-4 pt-3 pb-1 border-b border-gray-100">
                                    <p className="text-xs font-medium text-gray-500 truncate">
                                        {materialTitles[materialId] ?? formatMaterialId(materialId)}
                                    </p>
                                </div>
                                <div className="px-4">
                                    <WorkflowInfoPanel materialId={materialId} active={isActive} />
                                </div>
                            </div>
                        ))}
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
