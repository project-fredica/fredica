import { useState, useEffect, useCallback } from "react";
import { AlertCircle, Check, ChevronDown, ChevronRight, Loader, RefreshCw, X } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { reportHttpError } from "~/util/error_handler";

// ─── Types ────────────────────────────────────────────────────────────────────

interface RestartTaskLog {
    id: string;
    session_id: string;
    task_id: string;
    task_type: string;
    workflow_run_id: string;
    material_id: string;
    status_at_restart: string;
    payload: string;
    progress: number;
    disposition: string;
    new_workflow_run_id: string | null;
    created_at: number;
    resolved_at: number | null;
}

interface RestartTaskLogListResult {
    items: RestartTaskLog[];
    pending_review_count: number;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const TASK_TYPE_LABELS: Record<string, string> = {
    DOWNLOAD_BILIBILI_VIDEO:    '下载视频',
    TRANSCODE_MP4:              '转码 MP4',
    FETCH_SUBTITLE:             '获取字幕',
    WEBEN_CONCEPT_EXTRACT:      '知识提取',
    EXTRACT_AUDIO:              '提取音频',
    TRANSCRIBE:                 '语音识别',
};

const STATUS_AT_RESTART_LABELS: Record<string, { label: string; className: string }> = {
    running: { label: '执行中',  className: 'bg-blue-100 text-blue-700'   },
    claimed: { label: '已认领',  className: 'bg-yellow-100 text-yellow-700' },
    pending: { label: '等待中',  className: 'bg-gray-100 text-gray-600'   },
};

const DISPOSITION_LABELS: Record<string, { label: string; className: string }> = {
    pending_review: { label: '待处置', className: 'bg-red-100 text-red-700'    },
    dismissed:      { label: '已忽略', className: 'bg-gray-100 text-gray-500'  },
    recreated:      { label: '已重建', className: 'bg-green-100 text-green-700' },
    superseded:     { label: '已覆盖', className: 'bg-gray-50 text-gray-400'   },
};

const DISPOSITION_FILTER_OPTIONS = [
    { key: 'pending_review', label: '待处置' },
    { key: '',               label: '全部'   },
];

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatTs(ts: number | null): string {
    if (!ts) return '—';
    return new Date(ts * 1000).toLocaleString('zh-CN', { hour12: false });
}

function groupBySession(items: RestartTaskLog[]): Map<string, RestartTaskLog[]> {
    const map = new Map<string, RestartTaskLog[]>();
    for (const item of items) {
        const arr = map.get(item.session_id) ?? [];
        arr.push(item);
        map.set(item.session_id, arr);
    }
    return map;
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function TasksRestartPage() {
    const [items,              setItems]              = useState<RestartTaskLog[]>([]);
    const [pendingReviewCount, setPendingReviewCount] = useState(0);
    const [dispositionFilter,  setDispositionFilter]  = useState<string>('pending_review');
    const [refreshing,         setRefreshing]         = useState(false);
    const [dismissingIds,      setDismissingIds]      = useState<Set<string>>(new Set());
    const [dismissingSession,  setDismissingSession]  = useState<string | null>(null);
    const [collapsedSessions,  setCollapsedSessions]  = useState<Set<string>>(new Set());

    const { apiFetch } = useAppFetch();

    // ── Fetch ─────────────────────────────────────────────────────────────────

    const refresh = useCallback(async () => {
        try {
            const params = new URLSearchParams();
            if (dispositionFilter) params.set('disposition', dispositionFilter);
            const { data } = await apiFetch(
                `/api/v1/RestartTaskLogListRoute?${params.toString()}`,
                { method: 'GET' },
                { silent: true },
            );
            const result = data as RestartTaskLogListResult;
            if (result) {
                setItems(result.items ?? []);
                setPendingReviewCount(result.pending_review_count ?? 0);
            }
        } catch { /* silent */ }
    }, [apiFetch, dispositionFilter]);

    useEffect(() => { refresh(); }, [refresh]);

    const handleRefresh = async () => {
        setRefreshing(true);
        try { await refresh(); } finally { setRefreshing(false); }
    };

    // ── Dismiss one ───────────────────────────────────────────────────────────

    const handleDismissOne = async (id: string) => {
        setDismissingIds(prev => new Set([...prev, id]));
        try {
            const { resp } = await apiFetch('/api/v1/RestartTaskLogUpdateDispositionRoute', {
                method: 'POST',
                body: JSON.stringify({ ids: [id] }),
            });
            if (resp.ok) await refresh(); else reportHttpError('忽略失败', resp);
        } finally {
            setDismissingIds(prev => { const n = new Set(prev); n.delete(id); return n; });
        }
    };

    // ── Dismiss session ───────────────────────────────────────────────────────

    const handleDismissSession = async (sessionId: string) => {
        setDismissingSession(sessionId);
        try {
            const { resp } = await apiFetch('/api/v1/RestartTaskLogUpdateDispositionRoute', {
                method: 'POST',
                body: JSON.stringify({ session_id: sessionId }),
            });
            if (resp.ok) await refresh(); else reportHttpError('批量忽略失败', resp);
        } finally {
            setDismissingSession(null);
        }
    };

    // ── Toggle session collapse ───────────────────────────────────────────────

    const toggleSession = (sessionId: string) => {
        setCollapsedSessions(prev => {
            const next = new Set(prev);
            if (next.has(sessionId)) next.delete(sessionId); else next.add(sessionId);
            return next;
        });
    };

    // ── Derived ───────────────────────────────────────────────────────────────

    const grouped = groupBySession(items);
    const sessionIds = Array.from(grouped.keys());

    // ── Render ────────────────────────────────────────────────────────────────

    return (
        <SidebarLayout>
            <div className="max-w-4xl mx-auto p-4 sm:p-6 space-y-4">

                {/* Header */}
                <div className="flex items-center justify-between gap-3">
                    <div>
                        <h1 className="text-xl font-semibold text-gray-900">重启中断日志</h1>
                        <p className="text-sm text-gray-500 mt-0.5">
                            应用强杀重启时被取消的任务记录
                            {pendingReviewCount > 0 && (
                                <span className="ml-2 inline-flex items-center gap-1 px-1.5 py-0.5 bg-red-100 text-red-700 rounded text-xs font-semibold">
                                    <AlertCircle className="w-3 h-3" />
                                    {pendingReviewCount} 条待处置
                                </span>
                            )}
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

                {/* Filter */}
                <div className="flex gap-1.5">
                    {DISPOSITION_FILTER_OPTIONS.map(({ key, label }) => (
                        <button
                            key={key}
                            onClick={() => setDispositionFilter(key)}
                            className={`px-3 py-1.5 text-xs rounded-lg font-medium transition-colors ${
                                dispositionFilter === key
                                    ? 'bg-blue-600 text-white'
                                    : 'bg-gray-50 border border-gray-200 text-gray-500 hover:bg-gray-100'
                            }`}
                        >
                            {label}
                        </button>
                    ))}
                </div>

                {/* Empty state */}
                {items.length === 0 && (
                    <div className="bg-white rounded-lg border border-gray-200 p-10 text-center text-sm text-gray-400">
                        {dispositionFilter === 'pending_review'
                            ? '没有待处置的中断任务'
                            : '暂无重启中断记录'}
                    </div>
                )}

                {/* Sessions */}
                {sessionIds.map(sessionId => {
                    const sessionItems = grouped.get(sessionId)!;
                    const isCollapsed  = collapsedSessions.has(sessionId);
                    const firstItem    = sessionItems[0];
                    const restartTime  = formatTs(firstItem.created_at);
                    const hasPending   = sessionItems.some(i => i.disposition === 'pending_review');
                    const isDismissingThisSession = dismissingSession === sessionId;

                    return (
                        <div key={sessionId} className="bg-white rounded-lg border border-gray-200 overflow-hidden">
                            {/* Session header */}
                            <div
                                className="flex items-center gap-3 px-4 py-3 cursor-pointer hover:bg-gray-50 transition-colors"
                                onClick={() => toggleSession(sessionId)}
                            >
                                {isCollapsed
                                    ? <ChevronRight className="w-4 h-4 text-gray-400 flex-shrink-0" />
                                    : <ChevronDown  className="w-4 h-4 text-gray-400 flex-shrink-0" />
                                }
                                <div className="flex-1 min-w-0">
                                    <div className="flex items-center gap-2 flex-wrap">
                                        <span className="text-sm font-medium text-gray-900">
                                            重启于 {restartTime}
                                        </span>
                                        <span className="text-xs text-gray-400">
                                            {sessionItems.length} 个任务被中断
                                        </span>
                                        {hasPending && (
                                            <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded bg-red-100 text-red-700">
                                                待处置
                                            </span>
                                        )}
                                    </div>
                                    <p className="text-xs text-gray-400 font-mono mt-0.5">
                                        session: {sessionId.slice(0, 8)}…
                                    </p>
                                </div>

                                {/* Dismiss all in session */}
                                {hasPending && (
                                    <button
                                        onClick={e => { e.stopPropagation(); handleDismissSession(sessionId); }}
                                        disabled={isDismissingThisSession}
                                        className="flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-gray-600 bg-gray-50 border border-gray-200 rounded-lg hover:bg-gray-100 transition-colors disabled:opacity-50 flex-shrink-0"
                                    >
                                        {isDismissingThisSession
                                            ? <Loader className="w-3 h-3 animate-spin" />
                                            : <Check className="w-3 h-3" />
                                        }
                                        全部忽略
                                    </button>
                                )}
                            </div>

                            {/* Session items */}
                            {!isCollapsed && (
                                <div className="divide-y divide-gray-100 border-t border-gray-100">
                                    {sessionItems.map(item => {
                                        const typeLabel   = TASK_TYPE_LABELS[item.task_type] ?? item.task_type;
                                        const statusInfo  = STATUS_AT_RESTART_LABELS[item.status_at_restart]
                                            ?? { label: item.status_at_restart, className: 'bg-gray-100 text-gray-600' };
                                        const dispInfo    = DISPOSITION_LABELS[item.disposition]
                                            ?? { label: item.disposition, className: 'bg-gray-100 text-gray-600' };
                                        const isDismissing = dismissingIds.has(item.id);
                                        const isPending   = item.disposition === 'pending_review';

                                        return (
                                            <div key={item.id} className="px-4 py-3 pl-11">
                                                <div className="flex items-start gap-3">
                                                    <div className="flex-1 min-w-0 space-y-1">
                                                        {/* Title row */}
                                                        <div className="flex items-center gap-2 flex-wrap">
                                                            <span className="text-sm font-medium text-gray-900">{typeLabel}</span>
                                                            <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${statusInfo.className}`}>
                                                                {statusInfo.label}
                                                                {item.status_at_restart === 'running' && item.progress > 0
                                                                    ? ` ${item.progress}%` : ''}
                                                            </span>
                                                            <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${dispInfo.className}`}>
                                                                {dispInfo.label}
                                                            </span>
                                                        </div>

                                                        {/* Material */}
                                                        <p className="text-xs text-gray-500 font-mono truncate">
                                                            素材：{item.material_id}
                                                        </p>

                                                        {/* Task ID */}
                                                        <p className="text-xs text-gray-400 font-mono truncate">
                                                            任务：{item.task_id}
                                                        </p>

                                                        {/* Recreated link */}
                                                        {item.disposition === 'recreated' && item.new_workflow_run_id && (
                                                            <p className="text-xs text-green-600">
                                                                已重建工作流：{item.new_workflow_run_id.slice(0, 8)}…
                                                            </p>
                                                        )}
                                                    </div>

                                                    {/* Dismiss button */}
                                                    {isPending && (
                                                        <button
                                                            onClick={() => handleDismissOne(item.id)}
                                                            disabled={isDismissing}
                                                            className="flex items-center gap-1 px-2 py-1.5 text-xs font-medium text-gray-500 bg-gray-50 border border-gray-200 rounded-lg hover:bg-gray-100 transition-colors disabled:opacity-50 flex-shrink-0"
                                                        >
                                                            {isDismissing
                                                                ? <Loader className="w-3 h-3 animate-spin" />
                                                                : <X className="w-3 h-3" />
                                                            }
                                                            忽略
                                                        </button>
                                                    )}
                                                </div>
                                            </div>
                                        );
                                    })}
                                </div>
                            )}
                        </div>
                    );
                })}

            </div>
        </SidebarLayout>
    );
}
