import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { X, Activity, CheckCircle, XCircle, Clock, Loader, AlertTriangle, Settings } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import type { WebenSource } from "~/util/weben";
import { getAnalysisStatusInfo } from "~/util/weben";

// ─── Types ─────────────────────────────────────────────────────────────────────

interface WorkerTask {
    id: string;
    type: string;
    workflow_run_id: string;
    status: string;
    progress: number;
    error: string | null;
    error_type: string | null;
    started_at: number | null;
    completed_at: number | null;
}

interface TaskListResult {
    items: WorkerTask[];
    total: number;
}

// ─── Constants ─────────────────────────────────────────────────────────────────

/** 已知任务类型的显示名称，未知类型直接展示原始 type 字段。 */
const TASK_TYPE_LABELS: Record<string, string> = {
    FETCH_SUBTITLE:           '获取字幕',
    WEBEN_CONCEPT_EXTRACT:    '知识提取',
    DOWNLOAD_BILIBILI_VIDEO:  '下载视频',
    TRANSCODE_MP4:            '转码 MP4',
    EXTRACT_AUDIO:            '提取音频',
    TRANSCRIBE:               '语音识别',
    DOWNLOAD_WHISPER_MODEL:   '下载 ASR 模型',
};

const TASK_STATUS_CONFIG: Record<string, { label: string; icon: React.ReactNode; color: string }> = {
    pending:   { label: '等待中',   icon: <Clock className="w-3.5 h-3.5"   />, color: 'text-gray-400'   },
    claimed:   { label: '排队中',   icon: <Clock className="w-3.5 h-3.5"   />, color: 'text-yellow-500' },
    running:   { label: '进行中',   icon: <Loader className="w-3.5 h-3.5 animate-spin" />, color: 'text-blue-500'   },
    completed: { label: '已完成',   icon: <CheckCircle className="w-3.5 h-3.5" />, color: 'text-green-500'  },
    failed:    { label: '失败',     icon: <XCircle className="w-3.5 h-3.5"  />, color: 'text-red-500'    },
    cancelled: { label: '已取消',   icon: <XCircle className="w-3.5 h-3.5"  />, color: 'text-gray-400'   },
};

// ─── Sub-components ─────────────────────────────────────────────────────────────

function ProgressBar({ value, color = 'bg-blue-500' }: { value: number; color?: string }) {
    return (
        <div className="h-1.5 bg-gray-100 rounded-full overflow-hidden">
            <div
                className={`h-full rounded-full transition-all duration-500 ${color}`}
                style={{ width: `${Math.min(100, Math.max(0, value))}%` }}
            />
        </div>
    );
}

function TaskRow({ task }: { task: WorkerTask }) {
    const label  = TASK_TYPE_LABELS[task.type] ?? task.type;
    const isAwaitingAsr = task.error_type === 'AWAITING_ASR_CONFIG';
    const status = isAwaitingAsr
        ? { label: '等待配置', icon: <AlertTriangle className="w-3.5 h-3.5" />, color: 'text-amber-500' }
        : (TASK_STATUS_CONFIG[task.status] ?? TASK_STATUS_CONFIG.pending);
    const isRunning   = task.status === 'running';
    const isCompleted = task.status === 'completed';
    const barColor = isCompleted ? 'bg-green-500' : isRunning ? 'bg-blue-500' : isAwaitingAsr ? 'bg-amber-300' : 'bg-gray-300';
    const pct = isCompleted ? 100 : task.progress;

    return (
        <div className="py-3">
            <div className="flex items-center justify-between mb-1.5">
                <span className="text-sm font-medium text-gray-700">{label}</span>
                <span className={`flex items-center gap-1 text-xs ${status.color}`}>
                    {status.icon}
                    {status.label}
                    {isRunning && pct > 0 && (
                        <span className="ml-1 tabular-nums text-gray-400">{pct}%</span>
                    )}
                </span>
            </div>
            <ProgressBar value={pct} color={barColor} />
            {isAwaitingAsr ? (
                <div className="mt-2 rounded-lg bg-amber-50 border border-amber-200 px-3 py-2.5">
                    <p className="text-xs text-amber-800 mb-2">该视频无 Bilibili 字幕，需配置本地语音识别（ASR）才能继续分析。</p>
                    <Link
                        to="/settings?section=asr"
                        className="inline-flex items-center gap-1.5 text-xs font-medium text-amber-700 hover:text-amber-900 transition-colors"
                    >
                        <Settings className="w-3.5 h-3.5" />
                        前往 ASR 设置
                    </Link>
                </div>
            ) : task.error ? (
                <p className="mt-1 text-xs text-red-500 truncate" title={task.error}>{task.error}</p>
            ) : null}
        </div>
    );
}

// ─── Modal ──────────────────────────────────────────────────────────────────────

interface Props {
    /**
     * 父组件（WebenIndexPage）每 5 秒轮询一次 WebenSourceListRoute，
     * 将最新的 source 数据（含 progress / analysis_status）向下传递，
     * 模态框始终展示最新整体进度，无需自行管理 source 状态。
     */
    source: WebenSource;
    onClose: () => void;
}

/**
 * WebenSource 分析进度模态框
 *
 * 展示：
 * - 整体进度条（来自 source.progress，由后端从工作流任务图动态计算）
 * - 各任务的实时状态和进度条（通过 WorkerTaskListRoute 独立轮询，更实时）
 *
 * 轮询策略：
 * - source.analysis_status === 'analyzing' 时每 2s 轮询一次任务列表
 * - 状态变为终态（completed / failed）后停止轮询
 * - workflow_run_id 为 null 时显示占位提示
 */
export function WebenSourceAnalysisModal({ source, onClose }: Props) {
    const [tasks,   setTasks]   = useState<WorkerTask[]>([]);
    const [loading, setLoading] = useState(true);
    const [error,   setError]   = useState<string | null>(null);

    const { apiFetch } = useAppFetch();
    const cancelledRef = useRef(false);

    const isAnalyzing = source.analysis_status === 'analyzing' || source.analysis_status === 'pending';
    const wfId = source.workflow_run_id;
    const statusInfo = getAnalysisStatusInfo(source.analysis_status);

    // 获取任务列表
    const fetchTasks = async () => {
        if (!wfId || cancelledRef.current) return;
        try {
            const res = await apiFetch(
                `/api/v1/WorkerTaskListRoute?workflow_run_id=${encodeURIComponent(wfId)}&page_size=20`,
                { method: 'GET' },
                { silent: true },
            );
            if (cancelledRef.current) return;
            const data = res.data as TaskListResult | null;
            if (data?.items) setTasks(data.items);
            setError(null);
        } catch {
            if (!cancelledRef.current) setError('获取任务信息失败');
        } finally {
            if (!cancelledRef.current) setLoading(false);
        }
    };

    useEffect(() => {
        cancelledRef.current = false;
        setLoading(true);
        fetchTasks();

        if (!isAnalyzing) return;

        // 分析中：每 2s 轮询一次任务进度
        const timer = setInterval(fetchTasks, 2000);
        return () => {
            cancelledRef.current = true;
            clearInterval(timer);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [wfId, isAnalyzing]);

    // ESC 关闭
    useEffect(() => {
        const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
        document.addEventListener('keydown', handler);
        return () => document.removeEventListener('keydown', handler);
    }, [onClose]);

    return (
        <div className="fixed inset-0 z-60 flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm">
            <div className="bg-white rounded-2xl shadow-xl w-full max-w-md flex flex-col max-h-[80vh]">

                {/* Header */}
                <div className="flex items-center gap-3 px-5 py-4 border-b border-gray-100">
                    <Activity className="w-4 h-4 text-blue-500 flex-shrink-0" />
                    <div className="flex-1 min-w-0">
                        <h2 className="text-sm font-semibold text-gray-900">分析进度</h2>
                        <p className="text-xs text-gray-400 truncate mt-0.5">{source.title}</p>
                    </div>
                    <button
                        onClick={onClose}
                        className="p-1.5 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg transition-colors"
                        aria-label="关闭"
                    >
                        <X className="w-4 h-4" />
                    </button>
                </div>

                {/* Overall progress */}
                <div className="px-5 py-4 border-b border-gray-50">
                    <div className="flex items-center justify-between mb-2">
                        <span className="text-xs font-medium text-gray-500">整体进度</span>
                        <div className="flex items-center gap-1.5">
                            <span className={`text-[10px] font-medium px-2 py-0.5 rounded-full ${statusInfo.badge}`}>
                                {statusInfo.label}
                            </span>
                            <span className="text-xs tabular-nums text-gray-400">{source.progress}%</span>
                        </div>
                    </div>
                    <ProgressBar
                        value={source.progress}
                        color={
                            source.analysis_status === 'completed' ? 'bg-green-500' :
                            source.analysis_status === 'failed'    ? 'bg-red-400'   :
                            'bg-blue-500'
                        }
                    />
                </div>

                {/* Task list */}
                <div className="flex-1 overflow-y-auto px-5 min-h-0">
                    {!wfId ? (
                        <div className="py-8 text-center text-sm text-gray-400">暂无工作流信息</div>
                    ) : loading ? (
                        <div className="py-8 flex items-center justify-center gap-2 text-sm text-gray-400">
                            <Loader className="w-4 h-4 animate-spin" />
                            加载任务信息…
                        </div>
                    ) : error ? (
                        <div className="py-8 text-center text-sm text-red-400">{error}</div>
                    ) : tasks.length === 0 ? (
                        <div className="py-8 text-center text-sm text-gray-400">暂无任务数据</div>
                    ) : (
                        <div className="divide-y divide-gray-50">
                            {tasks.map(task => <TaskRow key={task.id} task={task} />)}
                        </div>
                    )}
                </div>

            </div>
        </div>
    );
}
