import { useEffect, useRef, useState } from "react";
import { CheckCircle, XCircle, Clock, Loader, AlertTriangle, Settings, Pause, Play, X, SkipForward, Library, ChevronDown } from "lucide-react";
import { Link } from "react-router";
import { useAppFetch } from "~/util/app_fetch";
import { json_parse } from "~/util/json";

// ─── Types ───────────────────────────────────────────────────────────────────

export interface WorkerTask {
    id: string;
    type: string;
    status: string;
    progress: number;
    status_text?: string | null;
    error: string | null;
    error_type: string | null;
    result?: string | null;
    is_paused?: boolean;
    is_pausable?: boolean;
    retry_count?: number;
    max_retries?: number;
    workflow_run_id?: string;
    material_id?: string;
    created_at?: number;
    claimed_at?: number | null;
    started_at?: number | null;
    completed_at?: number | null;
}

export interface TaskActions {
    pausingTaskId?: string | null;
    cancellingTaskId?: string | null;
    onPause?: (id: string) => void;
    onResume?: (id: string) => void;
    onCancel?: (id: string) => void;
}

// ─── ProgressBar ─────────────────────────────────────────────────────────────

export function ProgressBar({ value, color = 'bg-blue-500', height = 'h-1.5' }: {
    value: number;
    color?: string;
    height?: string;
}) {
    return (
        <div className={`${height} bg-gray-100 rounded-full overflow-hidden`}>
            <div
                className={`h-full rounded-full transition-all duration-500 ${color}`}
                style={{ width: `${Math.min(100, Math.max(0, value))}%` }}
            />
        </div>
    );
}

// ─── Constants ───────────────────────────────────────────────────────────────

export const TASK_TYPE_LABELS: Record<string, string> = {
    FETCH_SUBTITLE:          '获取字幕',
    WEBEN_CONCEPT_EXTRACT:   '知识提取',
    DOWNLOAD_BILIBILI_VIDEO: '下载视频',
    TRANSCODE_MP4:           '转码 MP4',
    EXTRACT_AUDIO:           '提取音频',
    TRANSCRIBE:              '语音识别',
    DOWNLOAD_WHISPER_MODEL:  '下载 ASR 模型',
    DOWNLOAD_TORCH:          '下载 PyTorch',
    SUBTITLE_EXPORT_ASR:     '生成 ASR 字幕',
};

const TASK_STATUS_CONFIG: Record<string, { label: string; icon: React.ReactNode; color: string; bg: string }> = {
    pending:   { label: '等待中', icon: <Clock className="w-3.5 h-3.5" />,               color: 'text-gray-400',   bg: 'bg-gray-50'   },
    claimed:   { label: '排队中', icon: <Clock className="w-3.5 h-3.5" />,               color: 'text-yellow-500', bg: 'bg-yellow-50' },
    running:   { label: '进行中', icon: <Loader className="w-3.5 h-3.5 animate-spin" />, color: 'text-blue-500',   bg: 'bg-blue-50'   },
    completed: { label: '已完成', icon: <CheckCircle className="w-3.5 h-3.5" />,         color: 'text-green-500',  bg: 'bg-green-50'  },
    failed:    { label: '失败',   icon: <XCircle className="w-3.5 h-3.5" />,             color: 'text-red-500',    bg: 'bg-red-50'    },
    cancelled: { label: '已取消', icon: <XCircle className="w-3.5 h-3.5" />,             color: 'text-gray-400',   bg: 'bg-gray-50'   },
};

const PAUSED_STATUS  = { label: '已暂停',  icon: <Pause className="w-3.5 h-3.5" />,         color: 'text-amber-500', bg: 'bg-amber-50'  };
const SKIPPED_STATUS = { label: '已跳过',  icon: <SkipForward className="w-3.5 h-3.5" />,   color: 'text-green-400', bg: 'bg-green-50'  };
const AWAITING_ASR   = { label: '等待配置', icon: <AlertTriangle className="w-3.5 h-3.5" />, color: 'text-amber-500', bg: 'bg-amber-50'  };

function getStatus(task: WorkerTask) {
    if (task.error_type === 'AWAITING_ASR_CONFIG') return AWAITING_ASR;
    if (task.is_paused && task.status === 'running') return PAUSED_STATUS;
    if (task.status === 'completed') {
        if ((json_parse<Record<string, unknown>>(task.result ?? '{}') as Record<string, unknown>)?.skipped === true) return SKIPPED_STATUS;
    }
    return TASK_STATUS_CONFIG[task.status] ?? TASK_STATUS_CONFIG.pending;
}

function taskBarColor(task: WorkerTask): string {
    if (task.is_paused && task.status === 'running') return 'bg-amber-400';
    if (task.status === 'completed') return 'bg-green-500';
    if (task.status === 'running')   return 'bg-blue-500';
    if (task.error_type === 'AWAITING_ASR_CONFIG') return 'bg-amber-300';
    return 'bg-gray-200';
}

// ─── ActiveTaskState ─────────────────────────────────────────────────────────

export interface ActiveTaskState {
    hasActiveTranscode: boolean;
    activeDownloadId: string | null;
    runningPausableTaskId: string | null;
    runningPausableTaskIsPaused: boolean;
    anyActiveTaskId: string | null;
}

const PAUSABLE_TYPES  = new Set(['DOWNLOAD_BILIBILI_VIDEO', 'TRANSCRIBE']);
const TRANSCODE_TYPES = new Set(['TRANSCODE_MP4', 'EXTRACT_AUDIO']);

function deriveActiveState(tasks: WorkerTask[]): ActiveTaskState {
    const active = tasks.filter(t => t.status === 'running' || t.status === 'pending' || t.status === 'claimed');
    const anyActiveTaskId             = active[0]?.id ?? null;
    const hasActiveTranscode          = active.some(t => TRANSCODE_TYPES.has(t.type));
    const activeDownloadId            = active.find(t => t.type === 'DOWNLOAD_BILIBILI_VIDEO')?.id ?? null;
    const pausable                    = active.find(t => PAUSABLE_TYPES.has(t.type) && t.status === 'running');
    const runningPausableTaskId       = pausable?.id ?? null;
    const runningPausableTaskIsPaused = pausable?.is_paused ?? false;
    return { hasActiveTranscode, activeDownloadId, runningPausableTaskId, runningPausableTaskIsPaused, anyActiveTaskId };
}

// ─── TaskRow (internal) ──────────────────────────────────────────────────────

function taskAccentColor(task: WorkerTask): string {
    if (task.error_type === 'AWAITING_ASR_CONFIG') return 'bg-amber-400';
    if (task.is_paused && task.status === 'running') return 'bg-amber-400';
    if (task.status === 'running')   return 'bg-blue-500';
    if (task.status === 'completed') return 'bg-green-500';
    if (task.status === 'failed')    return 'bg-red-400';
    if (task.status === 'cancelled') return 'bg-gray-300';
    return 'bg-gray-300';
}

function TaskRow({ task, onPause, onResume, onCancel, pausingId, cancellingId, compact = false }: {
    task: WorkerTask;
    onPause: (id: string) => void;
    onResume: (id: string) => void;
    onCancel: (id: string) => void;
    pausingId: string | null;
    cancellingId: string | null;
    compact?: boolean;
}) {
    const label         = TASK_TYPE_LABELS[task.type] ?? task.type;
    const status        = getStatus(task);
    const isActive      = task.status === 'running' || task.status === 'pending' || task.status === 'claimed';
    const isRunning     = task.status === 'running' && !task.is_paused;
    const pct           = task.status === 'completed' ? 100 : task.progress;
    const isAwaitingAsr = task.error_type === 'AWAITING_ASR_CONFIG';
    const statusText    = task.status_text?.trim() || null;
    const canPause      = task.status === 'running' && !task.is_paused && !!task.is_pausable;
    const canResume     = task.status === 'running' && !!task.is_paused;
    const canCancel     = isActive;
    const isPausing     = pausingId === task.id;
    const isCancelling  = cancellingId === task.id;
    const accent        = taskAccentColor(task);

    if (compact) {
        // 收起态：紧凑单行卡片
        return (
            <div className="flex items-center gap-3 min-w-0">
                <div className={`w-1 self-stretch rounded-full flex-shrink-0 ${accent}`} />
                <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between gap-2 mb-1.5">
                        <div className="flex items-center gap-1.5 min-w-0">
                            <span className={`flex-shrink-0 ${status.color}`}>{status.icon}</span>
                            <span className="text-sm font-medium text-gray-700 truncate">{label}</span>
                            {(task.retry_count ?? 0) > 0 && (
                                <span className="flex-shrink-0 text-[10px] text-orange-400 tabular-nums">×{task.retry_count}</span>
                            )}
                        </div>
                        <div className="flex items-center gap-2 flex-shrink-0">
                            {isRunning && pct > 0 && (
                                <span className="tabular-nums text-xs text-gray-400">{pct}%</span>
                            )}
                            <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${status.color} ${status.bg}`}>
                                {status.label}
                            </span>
                        </div>
                    </div>
                    <ProgressBar value={pct} color={taskBarColor(task)} height="h-1" />
                    {statusText && !isAwaitingAsr && !task.error && (
                        <p className="mt-1 text-xs text-gray-400 whitespace-pre-wrap break-all">{statusText}</p>
                    )}
                    {isAwaitingAsr && (
                        <Link to="/settings?section=asr" className="inline-flex items-center gap-1 mt-1.5 text-xs text-amber-600 hover:text-amber-800">
                            <Settings className="w-3 h-3" />前往配置 ASR
                        </Link>
                    )}
                    {!isAwaitingAsr && task.error && (
                        <p className="mt-1 text-xs text-red-400 whitespace-pre-wrap break-all">{task.error}</p>
                    )}
                </div>
                {(canPause || canResume || canCancel) && (
                    <div className="flex items-center gap-1 flex-shrink-0">
                        {canResume && (
                            <button onClick={() => onResume(task.id)} disabled={isPausing} title="恢复"
                                className="p-1.5 rounded-md text-green-600 hover:bg-green-50 disabled:opacity-40 transition-colors">
                                {isPausing ? <Loader className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />}
                            </button>
                        )}
                        {canPause && (
                            <button onClick={() => onPause(task.id)} disabled={isPausing} title="暂停"
                                className="p-1.5 rounded-md text-amber-600 hover:bg-amber-50 disabled:opacity-40 transition-colors">
                                {isPausing ? <Loader className="w-3.5 h-3.5 animate-spin" /> : <Pause className="w-3.5 h-3.5" />}
                            </button>
                        )}
                        {canCancel && (
                            <button onClick={() => onCancel(task.id)} disabled={isCancelling} title="取消"
                                className="p-1.5 rounded-md text-gray-400 hover:bg-gray-100 disabled:opacity-40 transition-colors">
                                {isCancelling ? <Loader className="w-3.5 h-3.5 animate-spin" /> : <X className="w-3.5 h-3.5" />}
                            </button>
                        )}
                    </div>
                )}
            </div>
        );
    }

    // 展开态：带时间轴竖线的完整行
    return (
        <div className="flex gap-3 py-3">
            <div className="flex flex-col items-center flex-shrink-0 pt-0.5">
                <span className={`${status.color}`}>{status.icon}</span>
                <div className="w-px flex-1 mt-1.5 bg-gray-100" />
            </div>
            <div className="flex-1 min-w-0 pb-1">
                <div className="flex items-center justify-between gap-2 mb-1.5">
                    <div className="flex items-center gap-1.5 min-w-0">
                        <span className="text-sm font-medium text-gray-700 truncate">{label}</span>
                        {(task.retry_count ?? 0) > 0 && (
                            <span className="text-[10px] text-orange-400 tabular-nums bg-orange-50 px-1 rounded">
                                重试 {task.retry_count}/{task.max_retries}
                            </span>
                        )}
                    </div>
                    <div className="flex items-center gap-2 flex-shrink-0">
                        {isRunning && pct > 0 && (
                            <span className="tabular-nums text-xs text-gray-400">{pct}%</span>
                        )}
                        <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${status.color} ${status.bg}`}>
                            {status.label}
                        </span>
                        {(canPause || canResume || canCancel) && (
                            <div className="flex items-center gap-0.5">
                                {canResume && (
                                    <button onClick={() => onResume(task.id)} disabled={isPausing} title="恢复"
                                        className="p-1 rounded text-green-600 hover:bg-green-50 disabled:opacity-40 transition-colors">
                                        {isPausing ? <Loader className="w-3 h-3 animate-spin" /> : <Play className="w-3 h-3" />}
                                    </button>
                                )}
                                {canPause && (
                                    <button onClick={() => onPause(task.id)} disabled={isPausing} title="暂停"
                                        className="p-1 rounded text-amber-600 hover:bg-amber-50 disabled:opacity-40 transition-colors">
                                        {isPausing ? <Loader className="w-3 h-3 animate-spin" /> : <Pause className="w-3 h-3" />}
                                    </button>
                                )}
                                {canCancel && (
                                    <button onClick={() => onCancel(task.id)} disabled={isCancelling} title="取消"
                                        className="p-1 rounded text-gray-400 hover:bg-gray-100 disabled:opacity-40 transition-colors">
                                        {isCancelling ? <Loader className="w-3 h-3 animate-spin" /> : <X className="w-3 h-3" />}
                                    </button>
                                )}
                            </div>
                        )}
                    </div>
                </div>
                <ProgressBar value={pct} color={taskBarColor(task)} height="h-1" />
                {statusText && !isAwaitingAsr && !task.error && (
                    <p className="mt-1 text-xs text-gray-400 whitespace-pre-wrap break-all">{statusText}</p>
                )}
                {isAwaitingAsr ? (
                    <div className="mt-2 rounded-lg bg-amber-50 border border-amber-200 px-3 py-2">
                        <p className="text-xs text-amber-800 mb-1.5">该视频无 Bilibili 字幕，需配置本地语音识别（ASR）才能继续分析。</p>
                        <Link to="/settings?section=asr"
                            className="inline-flex items-center gap-1 text-xs font-medium text-amber-700 hover:text-amber-900 transition-colors">
                            <Settings className="w-3 h-3" />前往 ASR 设置
                        </Link>
                    </div>
                ) : task.error ? (
                    <p className="mt-1 text-xs text-red-400 whitespace-pre-wrap break-all">
                        {task.error_type ? <span className="font-medium">[{task.error_type}] </span> : null}{task.error}
                    </p>
                ) : null}
            </div>
        </div>
    );
}

// ─── InternalTaskList ────────────────────────────────────────────────────────

const LIST_HEIGHT = 240;

function InternalTaskList({ tasks, onPause, onResume, onCancel, pausingId, cancellingId }: {
    tasks: WorkerTask[];
    onPause: (id: string) => void;
    onResume: (id: string) => void;
    onCancel: (id: string) => void;
    pausingId: string | null;
    cancellingId: string | null;
}) {
    const scrollRef = useRef<HTMLDivElement>(null);
    const [atTop,    setAtTop]    = useState(true);
    const [atBottom, setAtBottom] = useState(false);

    const onScroll = () => {
        const el = scrollRef.current;
        if (!el) return;
        setAtTop(el.scrollTop <= 4);
        setAtBottom(el.scrollTop + el.clientHeight >= el.scrollHeight - 4);
    };

    // 初始化时检查是否需要滚动
    useEffect(() => { onScroll(); }, [tasks]);

    return (
        <div className="relative">
            {/* 顶部遮罩 */}
            {!atTop && (
                <div className="absolute top-0 left-0 right-0 h-10 bg-gradient-to-b from-white via-white/80 to-transparent z-10 pointer-events-none rounded-t" />
            )}
            <div
                ref={scrollRef}
                onScroll={onScroll}
                style={{ maxHeight: LIST_HEIGHT, overflowY: 'auto' }}
                className="pr-1 scrollbar-thin scrollbar-thumb-gray-200 scrollbar-track-transparent"
            >
                {tasks.map(task => (
                    <TaskRow
                        key={task.id}
                        task={task}
                        onPause={onPause}
                        onResume={onResume}
                        onCancel={onCancel}
                        pausingId={pausingId}
                        cancellingId={cancellingId}
                    />
                ))}
            </div>
            {/* 底部遮罩 */}
            {!atBottom && tasks.length > 0 && (
                <div className="absolute bottom-0 left-0 right-0 h-10 bg-gradient-to-t from-white via-white/80 to-transparent z-10 pointer-events-none rounded-b" />
            )}
        </div>
    );
}

// ─── WorkflowInfoPanel ───────────────────────────────────────────────────────

const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled']);

function allTerminal(tasks: WorkerTask[]): boolean {
    return tasks.length > 0 && tasks.every(t => TERMINAL_STATUSES.has(t.status));
}

const POLL_INTERVAL_MS = 2_000;

export function WorkflowInfoPanel({ workflowRunId, materialId, active = true, onActiveState, defaultExpanded = false }: {
    workflowRunId?: string | null;
    materialId?: string | null;
    active?: boolean;
    onActiveState?: (state: ActiveTaskState) => void;
    defaultExpanded?: boolean;
}) {
    const [expanded,     setExpanded]     = useState(defaultExpanded ?? false);
    const [tasks,        setTasks]        = useState<WorkerTask[]>([]);
    const [loading,      setLoading]      = useState(true);
    const [error,        setError]        = useState<string | null>(null);
    const [pausingId,    setPausingId]    = useState<string | null>(null);
    const [cancellingId, setCancellingId] = useState<string | null>(null);

    const { apiFetch } = useAppFetch();
    const cancelledRef     = useRef(false);
    const onActiveStateRef = useRef(onActiveState);
    onActiveStateRef.current = onActiveState;

    const hasQuery = !!(workflowRunId || materialId);

    useEffect(() => {
        if (!hasQuery) { setLoading(false); return; }
        cancelledRef.current = false;
        setLoading(true);

        const doFetch = async () => {
            if (cancelledRef.current) return;
            try {
                const params = new URLSearchParams({ page_size: '20' });
                if (workflowRunId) params.set('workflow_run_id', workflowRunId);
                else if (materialId) params.set('material_id', materialId);
                const { data } = await apiFetch(
                    `/api/v1/WorkerTaskListRoute?${params.toString()}`,
                    { method: 'GET' },
                    { silent: true },
                );
                if (cancelledRef.current) return;
                const d = data as { items: WorkerTask[] } | null;
                if (d?.items) {
                    setTasks(d.items);
                    onActiveStateRef.current?.(deriveActiveState(d.items));
                    if (allTerminal(d.items)) cancelledRef.current = true;
                }
                setError(null);
            } catch {
                if (!cancelledRef.current) setError('获取任务信息失败');
            } finally {
                setLoading(false);
            }
        };

        doFetch();
        if (!active) return () => { cancelledRef.current = true; };
        const timer = setInterval(doFetch, POLL_INTERVAL_MS);
        return () => { cancelledRef.current = true; clearInterval(timer); };
    }, [workflowRunId, materialId, active, apiFetch, hasQuery]);

    const handlePause = async (taskId: string) => {
        setPausingId(taskId);
        try { await apiFetch('/api/v1/TaskPauseRoute', { method: 'POST', body: JSON.stringify({ task_id: taskId }) }, { silent: true }); }
        finally { setPausingId(null); }
    };

    const handleResume = async (taskId: string) => {
        setPausingId(taskId);
        try { await apiFetch('/api/v1/TaskResumeRoute', { method: 'POST', body: JSON.stringify({ task_id: taskId }) }, { silent: true }); }
        finally { setPausingId(null); }
    };

    const handleCancel = async (taskId: string) => {
        setCancellingId(taskId);
        try { await apiFetch('/api/v1/TaskCancelRoute', { method: 'POST', body: JSON.stringify({ task_id: taskId }) }, { silent: true }); }
        finally { setCancellingId(null); }
    };

    const inferredMaterialId = materialId ?? tasks.find(t => t.material_id)?.material_id ?? null;

    if (!hasQuery) return (
        <div className="py-8 text-center text-sm text-gray-400">暂无工作流信息</div>
    );
    if (loading) return (
        <div className="py-8 flex items-center justify-center gap-2 text-sm text-gray-400">
            <Loader className="w-4 h-4 animate-spin" />
            加载任务信息…
        </div>
    );
    if (error) return (
        <div className="py-8 text-center text-sm text-red-400">{error}</div>
    );
    if (tasks.length === 0) return (
        <div className="py-8 text-center text-sm text-gray-400">暂无任务数据</div>
    );

    const activeCount    = tasks.filter(t => t.status === 'running' || t.status === 'pending' || t.status === 'claimed').length;
    const pausedCount    = tasks.filter(t => t.status === 'running' && t.is_paused).length;
    const completedCount = tasks.filter(t => t.status === 'completed').length;
    const failedCount    = tasks.filter(t => t.status === 'failed').length;
    const cancelledCount = tasks.filter(t => t.status === 'cancelled').length;

    // 整体状态色
    const headerAccent = activeCount > 0 ? 'border-blue-400' : failedCount > 0 ? 'border-red-400' : pausedCount > 0 ? 'border-amber-400' : cancelledCount > 0 ? 'border-gray-300' : 'border-green-400';
    const collapsedTask = tasks.find(t => t.status === 'failed') ??
        tasks.reduce((latest, t) => {
            const ts = t.completed_at ?? t.started_at ?? t.claimed_at ?? t.created_at ?? 0;
            const lts = latest.completed_at ?? latest.started_at ?? latest.claimed_at ?? latest.created_at ?? 0;
            return ts > lts ? t : latest;
        });

    return (
        <div className={`rounded-lg border-l-2 ${headerAccent} bg-white px-3 py-2.5`}>
            {/* 摘要行 */}
            <div className="flex items-center justify-between gap-2 mb-2">
                <div className="flex items-center gap-2 text-xs">
                    {activeCount > 0   && <span className="font-medium text-blue-600">{activeCount} 进行中</span>}
                    {pausedCount > 0   && <span className="font-medium text-amber-500">{pausedCount} 已暂停</span>}
                    {failedCount > 0   && <span className="font-medium text-red-500">{failedCount} 失败</span>}
                    {cancelledCount > 0 && <span className="font-medium text-gray-400">{cancelledCount} 已取消</span>}
                    {activeCount === 0 && pausedCount === 0 && failedCount === 0 && cancelledCount === 0 && (
                        <span className="font-medium text-green-600">全部完成</span>
                    )}
                    <span className="text-gray-300">·</span>
                    <span className="text-gray-400">共 {tasks.length} 步</span>
                </div>
                <div className="flex items-center gap-2">
                    {inferredMaterialId && (
                        <Link
                            to={`/material/${inferredMaterialId}/tasks`}
                            className="inline-flex items-center gap-1 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                        >
                            <Library className="w-3 h-3" />
                            工作区
                        </Link>
                    )}
                    {tasks.length > 1 && (
                        <button
                            onClick={() => setExpanded(v => !v)}
                            className="flex items-center gap-0.5 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                        >
                            <ChevronDown className={`w-3.5 h-3.5 transition-transform duration-200 ${expanded ? 'rotate-180' : ''}`} />
                            {expanded ? '收起' : `${tasks.length} 步`}
                        </button>
                    )}
                </div>
            </div>

            {/* 任务内容 */}
            {expanded ? (
                <InternalTaskList
                    tasks={tasks}
                    onPause={handlePause}
                    onResume={handleResume}
                    onCancel={handleCancel}
                    pausingId={pausingId}
                    cancellingId={cancellingId}
                />
            ) : (
                <TaskRow
                    task={collapsedTask}
                    onPause={handlePause}
                    onResume={handleResume}
                    onCancel={handleCancel}
                    pausingId={pausingId}
                    cancellingId={cancellingId}
                    compact
                />
            )}
        </div>
    );
}

// ─── TaskList (exported, with lifecycle) ─────────────────────────────────────

export function TaskList({ workflowRunId, materialId, active = true, onActiveState, filterStatuses }: {
    workflowRunId?: string | null;
    materialId?: string | null;
    active?: boolean;
    onActiveState?: (state: ActiveTaskState) => void;
    filterStatuses?: string[];
}) {
    const [tasks,        setTasks]        = useState<WorkerTask[]>([]);
    const [loading,      setLoading]      = useState(true);
    const [error,        setError]        = useState<string | null>(null);
    const [pausingId,    setPausingId]    = useState<string | null>(null);
    const [cancellingId, setCancellingId] = useState<string | null>(null);

    const { apiFetch } = useAppFetch();
    const cancelledRef     = useRef(false);
    const onActiveStateRef = useRef(onActiveState);
    onActiveStateRef.current = onActiveState;

    const hasQuery = !!(workflowRunId || materialId);

    useEffect(() => {
        if (!hasQuery) { setLoading(false); return; }
        cancelledRef.current = false;
        setLoading(true);

        const doFetch = async () => {
            if (cancelledRef.current) return;
            try {
                const params = new URLSearchParams({ page_size: '20' });
                if (workflowRunId) params.set('workflow_run_id', workflowRunId);
                else if (materialId) params.set('material_id', materialId);
                const { data } = await apiFetch(
                    `/api/v1/WorkerTaskListRoute?${params.toString()}`,
                    { method: 'GET' },
                    { silent: true },
                );
                if (cancelledRef.current) return;
                const d = data as { items: WorkerTask[] } | null;
                if (d?.items) {
                    setTasks(d.items);
                    onActiveStateRef.current?.(deriveActiveState(d.items));
                    if (allTerminal(d.items)) cancelledRef.current = true;
                }
                setError(null);
            } catch {
                if (!cancelledRef.current) setError('获取任务信息失败');
            } finally {
                setLoading(false);
            }
        };

        doFetch();
        if (!active) return () => { cancelledRef.current = true; };
        const timer = setInterval(doFetch, POLL_INTERVAL_MS);
        return () => { cancelledRef.current = true; clearInterval(timer); };
    }, [workflowRunId, materialId, active, apiFetch, hasQuery]);

    const handlePause = async (taskId: string) => {
        setPausingId(taskId);
        try { await apiFetch('/api/v1/TaskPauseRoute', { method: 'POST', body: JSON.stringify({ task_id: taskId }) }, { silent: true }); }
        finally { setPausingId(null); }
    };
    const handleResume = async (taskId: string) => {
        setPausingId(taskId);
        try { await apiFetch('/api/v1/TaskResumeRoute', { method: 'POST', body: JSON.stringify({ task_id: taskId }) }, { silent: true }); }
        finally { setPausingId(null); }
    };
    const handleCancel = async (taskId: string) => {
        setCancellingId(taskId);
        try { await apiFetch('/api/v1/TaskCancelRoute', { method: 'POST', body: JSON.stringify({ task_id: taskId }) }, { silent: true }); }
        finally { setCancellingId(null); }
    };

    if (!hasQuery) return <div className="py-6 text-center text-sm text-gray-400">暂无工作流信息</div>;
    if (loading)   return <div className="py-6 flex items-center justify-center gap-2 text-sm text-gray-400"><Loader className="w-4 h-4 animate-spin" />加载中…</div>;
    if (error)     return <div className="py-6 text-center text-sm text-red-400">{error}</div>;
    if (tasks.length === 0) return <div className="py-6 text-center text-sm text-gray-400">暂无任务数据</div>;

    const visibleTasks = filterStatuses ? tasks.filter(t => filterStatuses.includes(t.status)) : tasks;

    if (visibleTasks.length === 0) return <div className="py-6 text-center text-sm text-gray-400">该分类暂无任务</div>;

    return (
        <InternalTaskList
            tasks={visibleTasks}
            onPause={handlePause}
            onResume={handleResume}
            onCancel={handleCancel}
            pausingId={pausingId}
            cancellingId={cancellingId}
        />
    );
}
