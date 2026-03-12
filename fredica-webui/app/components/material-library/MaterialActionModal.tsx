import { useState } from "react";
import { Loader, X, Trash2, Download, Clapperboard } from "lucide-react";
import { Link } from "react-router";
import { type MaterialVideo } from "./materialTypes";
import { InfoTab } from "./InfoTab";
import { WebenTab } from "./WebenTab";
import { type WebenSource } from "~/util/weben";
import { TaskList, type ActiveTaskState } from "~/components/ui/WorkflowInfoPanel";

type ActionTab = 'workflow' | 'info' | 'weben';

export interface WorkflowCtx {
    materialId: string;
    runningTaskType: string | null;
    deletingVideoIds: Set<string>;
    onStartWorkflow: (type: string) => void;
    onRunTask: (type: string) => void;
    onDeleteVideo: (id: string) => void;
}

export interface WebenCtx {
    total: number;
    sources: WebenSource[];
    page: number;
    loading: boolean;
    onPageChange: (page: number) => void;
    analyzeUrl: string;
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
            <div className="relative bg-white rounded-xl shadow-xl w-full max-w-md mx-4 max-h-[90vh] flex flex-col">

                {/* Header */}
                <div className="flex items-start justify-between gap-2 px-5 pt-5 pb-3 flex-shrink-0">
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
                <div className="flex border-b border-gray-200 px-5 flex-shrink-0">
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
                <div className="px-5 py-4 overflow-y-auto flex-1">
                    {actionTab === 'workflow' && (
                        <WorkflowTabBody actionTarget={actionTarget} ctx={workflow} onClose={onClose} />
                    )}
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
                            analyzeUrl={weben.analyzeUrl}
                        />
                    )}
                </div>
            </div>
        </div>
    );
}

const DEFAULT_ACTIVE_STATE: ActiveTaskState = {
    hasActiveTranscode: false,
    activeDownloadId: null,
    runningPausableTaskId: null,
    runningPausableTaskIsPaused: false,
    anyActiveTaskId: null,
};

function WorkflowTabBody({ actionTarget, ctx, onClose }: {
    actionTarget: MaterialVideo;
    ctx: WorkflowCtx;
    onClose: () => void;
}) {
    const { materialId, runningTaskType, deletingVideoIds, onStartWorkflow, onRunTask, onDeleteVideo } = ctx;
    const [activeState, setActiveState] = useState<ActiveTaskState>(DEFAULT_ACTIVE_STATE);
    const { hasActiveTranscode, activeDownloadId, anyActiveTaskId } = activeState;
    const isBilibili = actionTarget.source_type === 'bilibili';

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
                        disabled={!!activeDownloadId || !!runningTaskType}
                        className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {(!!activeDownloadId || !!runningTaskType) ? <Loader className="w-3 h-3 animate-spin" /> : <Download className="w-3 h-3" />}
                        {activeDownloadId ? '下载中…' : '仅下载'}
                    </button>
                </div>
            )}

            {/* TaskList 自管理轮询 + 暂停/恢复/取消按钮 */}
            <div className="border-t border-gray-100 pt-2">
                <TaskList
                    materialId={materialId}
                    active={!!anyActiveTaskId || !!runningTaskType}
                    onActiveState={setActiveState}
                />
            </div>

            {/* 任务中心链接 */}
            <div className="pt-2 border-t border-gray-100">
                <Link
                    to={`/tasks?material_id=${encodeURIComponent(actionTarget.id)}`}
                    className="text-xs text-blue-500 hover:text-blue-700 transition-colors"
                    onClick={onClose}
                >
                    前往任务中心查看详情 →
                </Link>
            </div>

            {/* Danger zone */}
            <div className="pt-3 border-t border-red-100">
                <button
                    onClick={() => { onClose(); onDeleteVideo(actionTarget.id); }}
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
