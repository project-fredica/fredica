import { Brain, ArrowRight, ChevronLeft, ChevronRight, Loader } from "lucide-react";
import { Link } from "react-router";
import { type WebenSource, getAnalysisStatusInfo } from "~/util/weben";

const SOURCE_PAGE_SIZE = 5;

export function WebenTab({ total, sources, sourcePage, sourceLoading, onPageChange, analyzeUrl }: {
    total: number;
    sources: WebenSource[];
    sourcePage: number;
    sourceLoading: boolean;
    onPageChange: (page: number) => void;
    /** 跳转到知识提取配置页的 URL（含所有必要 query params） */
    analyzeUrl: string;
}) {
    const hasActive = sources.some(s => s.analysis_status === 'pending' || s.analysis_status === 'analyzing');
    const hasCompleted = sources.some(s => s.analysis_status === 'completed');
    const totalPages = Math.max(1, Math.ceil(total / SOURCE_PAGE_SIZE));

    return (
        <div className="space-y-3">
            {sourceLoading && sources.length === 0 ? (
                <div className="flex justify-center py-6">
                    <Loader className="w-5 h-5 animate-spin text-gray-400" />
                </div>
            ) : total === 0 ? (
                <div className="py-6 text-center space-y-1">
                    <Brain className="w-8 h-8 text-gray-200 mx-auto" />
                    <p className="text-sm text-gray-500">尚未进行知识提取</p>
                    <p className="text-xs text-gray-400">将从字幕/转录文本中提取概念、关系和闪卡</p>
                </div>
            ) : (
                <div className="border border-gray-100 rounded-lg overflow-hidden">
                    <div className="flex items-center justify-between px-3 py-2 bg-gray-50 border-b border-gray-100">
                        <span className="text-xs font-medium text-gray-600">来源 · 共 {total} 个</span>
                        {totalPages > 1 && (
                            <div className="flex items-center gap-1">
                                <button
                                    onClick={() => onPageChange(Math.max(1, sourcePage - 1))}
                                    disabled={sourcePage === 1}
                                    className="p-0.5 rounded text-gray-400 hover:text-gray-600 disabled:opacity-30"
                                >
                                    <ChevronLeft className="w-3.5 h-3.5" />
                                </button>
                                <span className="text-[10px] text-gray-400">{sourcePage}/{totalPages}</span>
                                <button
                                    onClick={() => onPageChange(Math.min(totalPages, sourcePage + 1))}
                                    disabled={sourcePage === totalPages}
                                    className="p-0.5 rounded text-gray-400 hover:text-gray-600 disabled:opacity-30"
                                >
                                    <ChevronRight className="w-3.5 h-3.5" />
                                </button>
                            </div>
                        )}
                    </div>
                    <div className="divide-y divide-gray-50">
                        {sources.map(s => {
                            const st = getAnalysisStatusInfo(s.analysis_status);
                            return (
                                <div key={s.id} className="flex items-center gap-3 px-3 py-2.5">
                                    <span className={`w-2 h-2 rounded-full flex-shrink-0 ${st.dot}`} />
                                    <div className="flex-1 min-w-0">
                                        <p className="text-xs font-medium text-gray-700 truncate">{s.title}</p>
                                        <p className="text-[10px] text-gray-400 mt-0.5">
                                            {s.analysis_status === 'analyzing' ? '正在提取…' :
                                             s.analysis_status === 'pending'   ? '排队等待中' :
                                             s.analysis_status === 'completed' ? '已完成提取' : '提取失败'}
                                        </p>
                                    </div>
                                    <span className={`text-[10px] px-1.5 py-0.5 rounded-full font-medium flex-shrink-0 ${st.badge}`}>
                                        {st.label}
                                    </span>
                                </div>
                            );
                        })}
                    </div>
                </div>
            )}

            {hasCompleted && (
                <div className="flex gap-2">
                    <Link
                        to="/weben/concepts"
                        className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-medium text-violet-700 bg-violet-50 rounded-lg hover:bg-violet-100 transition-colors"
                    >
                        <Brain className="w-3.5 h-3.5" />
                        查看提取概念
                    </Link>
                    <Link
                        to="/weben"
                        className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-medium text-gray-600 bg-gray-50 rounded-lg hover:bg-gray-100 transition-colors"
                    >
                        知识网络
                        <ArrowRight className="w-3 h-3" />
                    </Link>
                </div>
            )}

            <div className="flex items-center justify-between gap-3 pt-1 border-t border-gray-100">
                <div className="min-w-0">
                    <span className="text-sm text-gray-700">
                        {sources.length === 0 ? '开始知识提取' : '重新提取'}
                    </span>
                    {hasActive && (
                        <p className="text-xs text-blue-600 mt-0.5">分析任务进行中，可前往任务中心查看进度</p>
                    )}
                </div>
                <Link
                    to={analyzeUrl}
                    target="_blank"
                    className={`flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg transition-colors
                        ${hasActive
                            ? 'text-gray-400 bg-gray-50 pointer-events-none'
                            : 'text-violet-700 bg-violet-50 hover:bg-violet-100'}`}
                >
                    <Brain className="w-3 h-3" />
                    {hasActive ? '分析中…' : sources.length === 0 ? '开始提取' : '重新提取'}
                </Link>
            </div>

            {hasActive && (
                <Link
                    to="/tasks"
                    className="flex items-center justify-center gap-1.5 text-xs text-gray-400 hover:text-violet-600 transition-colors"
                >
                    前往任务中心查看进度 →
                </Link>
            )}
        </div>
    );
}
