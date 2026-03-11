import { Loader, X, Trash2, Download, Clapperboard, Pause, Play } from "lucide-react";
import { Link } from "react-router";
import { type MaterialVideo, type WorkerTask, WORKER_TASK_STATUS, TASK_TYPE_LABELS } from "./materialTypes";
import { InfoTab } from "./InfoTab";
import { WebenTab } from "./WebenTab";
import { type WebenSource } from "~/util/weben";

type ActionTab = 'workflow' | 'info' | 'weben';

export interface WorkflowCtx {
    tasksLoading: boolean;
    tasks: WorkerTask[];
    runningTaskType: string | null;
    pausingTaskId: string | null;
    cancellingPipelineId: string | null;
    deletingVideoIds: Set<string>;
    onStartWorkflow: (type: string) => void;
    onRunTask: (type: string) => void;
    onPauseTask: (id: string) => void;
    onResumeTask: (id: string) => void;
    onCancelDownload: (id: string) => void;
    onDeleteVideo: (id: string) => void;
}

export interface WebenCtx {
    total: number;
    sources: WebenSource[];
    page: number;
    loading: boolean;
    starting: boolean;
    onPageChange: (page: number) => void;
    onStartAnalysis: () => void;
}

export function MaterialActionModal({
    actionTarget,
    actionTab,
    onTabChange,
    onClose,
    workflow,
    onOpenAiConclusion,
    onOpenSubtitle,
    weben,
}: {
    actionTarget: MaterialVideo;
    actionTab: ActionTab;
    onTabChange: (tab: ActionTab) => void;
    onClose: () => void;
    workflow: WorkflowCtx;
    onOpenAiConclusion: (bvid: string) => void;
    onOpenSubtitle: (bvid: string) => void;
    weben: WebenCtx;
}) {
    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
            <div className="absolute inset-0 bg-black/40" onClick={onClose} />
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
                        onClick={onClose}
                        className="flex-shrink-0 p-1 rounded-lg hover:bg-gray-100 transition-colors"
                    >
                        <X className="w-4 h-4 text-gray-500" />
                    </button>
                </div>

                {/* Tabs */}
                <div className="flex border-b border-gray-200 px-5">
                    {(['workflow', 'info', 'weben'] as const).map(tab => (
                        <button
                            key={tab}
                            onClick={() => onTabChange(tab)}
                            className={`px-3 py-2.5 text-xs font-medium border-b-2 transition-colors ${actionTab === tab
                                ? 'border-purple-500 text-purple-700'
                                : 'border-transparent text-gray-500 hover:text-gray-700'
                            }`}
                        >
                            {tab === 'workflow' ? '一键流程' : tab === 'info' ? '信息拉取' : '知识提取'}
                        </button>
                    ))}
                </div>

                {/* Body */}
                <div className="px-5 py-4">
                    {actionTab === 'workflow' && (workflow.tasksLoading ? (
                        <div className="flex justify-center py-6">
                            <Loader className="w-5 h-5 animate-spin text-gray-400" />
                        </div>
                    ) : (
                        <WorkflowTabBody actionTarget={actionTarget} ctx={workflow} onClose={onClose} />
                    ))}

                    {actionTab === 'info' && (
                        <InfoTab
                            actionTarget={actionTarget}
                            onOpenModal={onOpenAiConclusion}
                            onOpenSubtitleModal={onOpenSubtitle}
                        />
                    )}

                    {actionTab === 'weben' && (
                        <WebenTab
                            total={weben.total}
                            sources={weben.sources}
                            sourcePage={weben.page}
                            sourceLoading={weben.loading}
                            onPageChange={weben.onPageChange}
                            starting={weben.starting}
                            onStartAnalysis={weben.onStartAnalysis}
                        />
                    )}
                </div>
            </div>
        </div>
    );
}

function WorkflowTabBody({ actionTarget, ctx, onClose }: {
    actionTarget: MaterialVideo;
    ctx: WorkflowCtx;
    onClose: () => void;
}) {
    const { tasks: modalWorkerTasks, runningTaskType, pausingTaskId, cancellingPipelineId, deletingVideoIds,
        onStartWorkflow, onRunTask, onPauseTask, onResumeTask, onCancelDownload, onDeleteVideo } = ctx;
    const isBilibili = actionTarget.source_type === 'bilibili';

    const hasActiveTranscode = isBilibili && modalWorkerTasks.some(
        t => ['DOWNLOAD_BILIBILI_VIDEO', 'TRANSCODE_MP4'].includes(t.type) &&
            ['pending', 'claimed', 'running'].includes(t.status)
    );
    const activeDownload = isBilibili ? modalWorkerTasks.find(
        t => t.type === 'DOWNLOAD_BILIBILI_VIDEO' && ['pending', 'claimed', 'running'].includes(t.status)
    ) : undefined;
    const runningTask = isBilibili ? modalWorkerTasks.find(
        t => ['DOWNLOAD_BILIBILI_VIDEO', 'TRANSCODE_MP4'].includes(t.type) && t.status === 'running'
    ) : undefined;
    const anyActiveTask = isBilibili ? modalWorkerTasks.find(
        t => ['DOWNLOAD_BILIBILI_VIDEO', 'TRANSCODE_MP4'].includes(t.type) &&
            ['pending', 'claimed', 'running'].includes(t.status)
    ) : undefined;

    return (
        <div className="space-y-2">
            {/* 下载并转码 */}
            {isBilibili && (
                <div className="flex items-center justify-between gap-3 py-1.5">
                    <div className="min-w-0">
                        <span className="text-sm text-gray-700">视频转码</span>
                        <p className="text-xs text-gray-400 mt-0.5">下载完成后自动转码，已下载则跳过</p>
                    </div>
                    <button
                        onClick={() => onStartWorkflow('bilibili_download_transcode')}
                        disabled={hasActiveTranscode || !!runningTaskType}
                        className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {(hasActiveTranscode || !!runningTaskType) ? <Loader className="w-3 h-3 animate-spin" /> : <Clapperboard className="w-3 h-3" />}
                        {hasActiveTranscode ? '进行中…' : '下载并转码'}
                    </button>
                </div>
            )}

            {/* 仅下载 */}
            {isBilibili && (
                <div className="flex items-center justify-between gap-3 py-1.5">
                    <span className="text-sm text-gray-700">视频下载</span>
                    <button
                        onClick={() => onRunTask('DOWNLOAD_BILIBILI_VIDEO')}
                        disabled={!!activeDownload || !!runningTaskType}
                        className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {(!!activeDownload || !!runningTaskType) ? <Loader className="w-3 h-3 animate-spin" /> : <Download className="w-3 h-3" />}
                        {activeDownload ? '下载中…' : '仅下载'}
                    </button>
                </div>
            )}

            {/* 暂停/恢复/取消 */}
            {anyActiveTask && (
                <div className="flex items-center justify-center gap-2 py-1 px-2 bg-gray-50 rounded-lg">
                    {runningTask && (
                        runningTask.is_paused ? (
                            <button
                                onClick={() => onResumeTask(runningTask.id)}
                                disabled={!!pausingTaskId}
                                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-green-700 bg-green-50 rounded-lg hover:bg-green-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                {pausingTaskId ? <Loader className="w-3 h-3 animate-spin" /> : <Play className="w-3 h-3" />}
                                恢复
                            </button>
                        ) : (
                            <button
                                onClick={() => onPauseTask(runningTask.id)}
                                disabled={!!pausingTaskId || !runningTask.is_pausable}
                                title={!runningTask.is_pausable ? '此任务不支持暂停' : undefined}
                                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-amber-700 bg-amber-50 rounded-lg hover:bg-amber-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                {pausingTaskId ? <Loader className="w-3 h-3 animate-spin" /> : <Pause className="w-3 h-3" />}
                                暂停
                            </button>
                        )
                    )}
                    <button
                        onClick={() => onCancelDownload(anyActiveTask.id)}
                        disabled={!!cancellingPipelineId}
                        className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-gray-600 bg-white border border-gray-200 rounded-lg hover:bg-gray-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {cancellingPipelineId ? <Loader className="w-3 h-3 animate-spin" /> : <X className="w-3 h-3" />}
                        取消全部
                    </button>
                </div>
            )}

            {/* Task progress list */}
            {modalWorkerTasks.some(t => t.type in TASK_TYPE_LABELS) && (
                <div className="space-y-1.5 pt-1">
                    {modalWorkerTasks.filter(t => t.type in TASK_TYPE_LABELS).map(task => {
                        const isSkipped = task.status === 'completed' &&
                            (() => { try { return (JSON.parse(task.result ?? '{}') as Record<string, unknown>).skipped === true; } catch { return false; } })();
                        const statusInfo = isSkipped
                            ? { label: '已跳过（已完成）', className: 'bg-green-100 text-green-700' }
                            : (WORKER_TASK_STATUS[task.status] ?? { label: task.status, className: 'bg-gray-100 text-gray-600' });
                        const typeLabel = TASK_TYPE_LABELS[task.type] ?? task.type;
                        const showBar = !isSkipped && (task.status === 'running' || task.status === 'claimed' || task.status === 'completed');
                        const barPct = task.status === 'completed' ? 100 : task.progress;
                        return (
                            <div key={task.id} className="space-y-0.5">
                                <div className="flex items-center justify-between gap-2">
                                    <span className="text-xs text-gray-600">{typeLabel}</span>
                                    <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded whitespace-nowrap ${statusInfo.className}`}>
                                        {statusInfo.label}{!isSkipped && task.status === 'running' && task.progress > 0 ? ` ${task.progress}%` : ''}
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
                    onClick={onClose}
                >
                    前往任务中心查看详情 →
                </Link>
            </div>

            {/* Danger zone */}
            <div className="pt-3 border-t border-red-100">
                <button
                    onClick={async () => {
                        onClose();
                        onDeleteVideo(actionTarget.id);
                    }}
                    disabled={deletingVideoIds.has(actionTarget.id)}
                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    {deletingVideoIds.has(actionTarget.id) ? <Loader className="w-3 h-3 animate-spin" /> : <Trash2 className="w-3 h-3" />}
                    移除素材库（但不删除数据）
                </button>
            </div>
        </div>
    );
}
