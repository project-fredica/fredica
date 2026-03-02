import { useState, useEffect, useCallback } from "react";
import { Loader, ChevronDown, ChevronRight, X, RefreshCw } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";

// ─── Types ────────────────────────────────────────────────────────────────────

interface PipelineInstance {
    id: string;
    material_id: string;
    template: string;
    status: string;
    total_tasks: number;
    done_tasks: number;
    created_at: number;
    completed_at: number | null;
}

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
    created_at: number;
    claimed_at: number | null;
    started_at: number | null;
    completed_at: number | null;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const PIPELINE_STATUS: Record<string, { label: string; className: string }> = {
    pending:   { label: '等待中',  className: 'bg-gray-100 text-gray-600' },
    running:   { label: '运行中',  className: 'bg-blue-100 text-blue-700' },
    completed: { label: '已完成',  className: 'bg-green-100 text-green-700' },
    failed:    { label: '失败',    className: 'bg-red-100 text-red-700' },
    cancelled: { label: '已取消',  className: 'bg-yellow-100 text-yellow-700' },
};

const TASK_STATUS: Record<string, { label: string; dot: string }> = {
    pending:   { label: '等待',   dot: 'bg-gray-400' },
    claimed:   { label: '已认领', dot: 'bg-yellow-400' },
    running:   { label: '运行中', dot: 'bg-blue-500 animate-pulse' },
    completed: { label: '完成',   dot: 'bg-green-500' },
    failed:    { label: '失败',   dot: 'bg-red-500' },
    cancelled: { label: '已取消', dot: 'bg-gray-300' },
};

const POLL_INTERVAL_MS = 3_000;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatTs(ts: number | null): string {
    if (!ts) return '—';
    return new Date(ts * 1000).toLocaleString('zh-CN', { hour12: false });
}

function ProgressBar({ done, total }: { done: number; total: number }) {
    const pct = total > 0 ? Math.round((done / total) * 100) : 0;
    return (
        <div className="flex items-center gap-2">
            <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                <div
                    className="h-full bg-blue-500 rounded-full transition-all duration-500"
                    style={{ width: `${pct}%` }}
                />
            </div>
            <span className="text-xs text-gray-500 tabular-nums w-20 text-right">
                {done}/{total} ({pct}%)
            </span>
        </div>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function ProcessingPage() {
    const [pipelines, setPipelines] = useState<PipelineInstance[]>([]);
    const [tasksMap, setTasksMap] = useState<Map<string, WorkerTask[]>>(new Map());
    const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
    const [cancellingIds, setCancellingIds] = useState<Set<string>>(new Set());
    const [refreshing, setRefreshing] = useState(false);

    const { apiFetch } = useAppFetch();

    // ── Polling ──────────────────────────────────────────────────────────────

    const refresh = useCallback(async () => {
        try {
            const { data: plData } = await apiFetch('/api/v1/PipelineListRoute', { method: 'GET' });
            if (Array.isArray(plData)) {
                setPipelines(plData as PipelineInstance[]);
            }

            const { data: taskData } = await apiFetch('/api/v1/WorkerTaskListRoute', { method: 'GET' });
            if (Array.isArray(taskData)) {
                const m = new Map<string, WorkerTask[]>();
                for (const t of taskData as WorkerTask[]) {
                    const list = m.get(t.pipeline_id) ?? [];
                    list.push(t);
                    m.set(t.pipeline_id, list);
                }
                setTasksMap(m);
            }
        } catch { /* silent on poll failure */ }
    }, [apiFetch]);

    const handleRefresh = async () => {
        setRefreshing(true);
        try { await refresh(); } finally { setRefreshing(false); }
    };

    // Initial load + polling
    useEffect(() => {
        refresh();
        const id = setInterval(refresh, POLL_INTERVAL_MS);
        return () => clearInterval(id);
    }, [refresh]);

    // ── Actions ──────────────────────────────────────────────────────────────

    const handleCancel = async (id: string) => {
        setCancellingIds(prev => new Set([...prev, id]));
        try {
            await apiFetch('/api/v1/PipelineCancelRoute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id }),
            });
            await refresh();
        } finally {
            setCancellingIds(prev => { const n = new Set(prev); n.delete(id); return n; });
        }
    };

    const toggleExpand = (id: string) =>
        setExpandedIds(prev => {
            const n = new Set(prev);
            if (n.has(id)) n.delete(id); else n.add(id);
            return n;
        });

    // ── Render ───────────────────────────────────────────────────────────────

    return (
        <SidebarLayout>
            <div className="max-w-4xl mx-auto p-4 sm:p-6 space-y-4">

                {/* Header */}
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-xl font-semibold text-gray-900">处理中心</h1>
                        <p className="text-sm text-gray-500 mt-0.5">
                            共 {pipelines.length} 个流水线 · 每 {POLL_INTERVAL_MS / 1000}s 自动刷新
                        </p>
                    </div>
                    <button
                        onClick={handleRefresh}
                        disabled={refreshing}
                        className="p-2 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors disabled:opacity-50"
                        title="立即刷新"
                    >
                        <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
                    </button>
                </div>

                {/* Empty state */}
                {pipelines.length === 0 && (
                    <div className="bg-white rounded-lg border border-gray-200 p-10 text-center text-sm text-gray-400">
                        暂无流水线任务
                    </div>
                )}

                {/* Pipeline cards */}
                {pipelines.map(pl => {
                    const badge      = PIPELINE_STATUS[pl.status] ?? { label: pl.status, className: 'bg-gray-100 text-gray-600' };
                    const tasks      = tasksMap.get(pl.id) ?? [];
                    const expanded   = expandedIds.has(pl.id);
                    const cancelling = cancellingIds.has(pl.id);
                    const canCancel  = pl.status === 'pending' || pl.status === 'running';

                    return (
                        <div key={pl.id} className="bg-white rounded-lg border border-gray-200 overflow-hidden">
                            {/* Card header */}
                            <div className="px-4 py-3 flex items-start gap-3">
                                <button
                                    onClick={() => toggleExpand(pl.id)}
                                    className="mt-0.5 text-gray-400 hover:text-gray-600 flex-shrink-0"
                                >
                                    {expanded
                                        ? <ChevronDown className="w-4 h-4" />
                                        : <ChevronRight className="w-4 h-4" />
                                    }
                                </button>

                                <div className="flex-1 min-w-0 space-y-1.5">
                                    <div className="flex items-center gap-2 flex-wrap">
                                        <span className="text-sm font-medium text-gray-900 truncate">
                                            {pl.template}
                                        </span>
                                        <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${badge.className}`}>
                                            {badge.label}
                                        </span>
                                    </div>
                                    <p className="text-xs text-gray-400 font-mono truncate">{pl.material_id}</p>
                                    <ProgressBar done={pl.done_tasks} total={pl.total_tasks} />
                                    <p className="text-xs text-gray-400">
                                        创建于 {formatTs(pl.created_at)}
                                        {pl.completed_at && ` · 完成于 ${formatTs(pl.completed_at)}`}
                                    </p>
                                </div>

                                {canCancel && (
                                    <button
                                        onClick={() => handleCancel(pl.id)}
                                        disabled={cancelling}
                                        className="flex-shrink-0 flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 transition-colors disabled:opacity-50"
                                    >
                                        {cancelling
                                            ? <Loader className="w-3 h-3 animate-spin" />
                                            : <X className="w-3 h-3" />
                                        }
                                        取消
                                    </button>
                                )}
                            </div>

                            {/* Task list (expanded) */}
                            {expanded && tasks.length > 0 && (
                                <div className="border-t border-gray-100 divide-y divide-gray-50">
                                    {tasks.map(t => {
                                        const ts = TASK_STATUS[t.status] ?? { label: t.status, dot: 'bg-gray-300' };
                                        return (
                                            <div key={t.id} className="px-4 py-2 flex items-center gap-3 text-xs">
                                                <span className={`w-2 h-2 rounded-full flex-shrink-0 ${ts.dot}`} />
                                                <span className="font-mono text-gray-700 w-36 flex-shrink-0">{t.type}</span>
                                                <span className="text-gray-500 w-14 flex-shrink-0">{ts.label}</span>
                                                {t.retry_count > 0 && (
                                                    <span className="text-orange-500 flex-shrink-0">
                                                        重试 {t.retry_count}/{t.max_retries}
                                                    </span>
                                                )}
                                                {t.error && (
                                                    <span className="text-red-500 truncate flex-1" title={t.error}>
                                                        [{t.error_type}] {t.error.slice(0, 100)}
                                                    </span>
                                                )}
                                            </div>
                                        );
                                    })}
                                </div>
                            )}

                            {expanded && tasks.length === 0 && (
                                <div className="border-t border-gray-100 px-4 py-3 text-xs text-gray-400">
                                    暂无任务记录
                                </div>
                            )}
                        </div>
                    );
                })}
            </div>
        </SidebarLayout>
    );
}
