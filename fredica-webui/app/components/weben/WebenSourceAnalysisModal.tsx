import { useEffect } from "react";
import { X, Activity } from "lucide-react";
import type { WebenSource } from "~/util/weben";
import { getAnalysisStatusInfo } from "~/util/weben";
import { ProgressBar } from "~/components/ui/WorkflowInfoPanel";
import { WorkflowInfoPanel } from "~/components/ui/WorkflowInfoPanel";

interface Props {
    source: WebenSource;
    onClose: () => void;
}

export function WebenSourceAnalysisModal({ source, onClose }: Props) {
    const wfId       = source.workflow_run_id;
    const statusInfo = getAnalysisStatusInfo(source.analysis_status);
    const active     = source.analysis_status === 'analyzing' || source.analysis_status === 'pending';

    useEffect(() => {
        const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
        document.addEventListener('keydown', handler);
        return () => document.removeEventListener('keydown', handler);
    }, [onClose]);

    return (
        <div className="fixed inset-0 z-60 flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm">
            <div className="bg-white rounded-2xl shadow-xl w-full max-w-md flex flex-col max-h-[80vh]">

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

                <div className="flex-1 overflow-y-auto px-5 min-h-0">
                    <WorkflowInfoPanel workflowRunId={wfId} active={active} />
                </div>

            </div>
        </div>
    );
}
