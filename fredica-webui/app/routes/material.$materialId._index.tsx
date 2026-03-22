import { Link } from "react-router";
import {
    Subtitles, BrainCircuit, Users, Film, Zap, ListChecks,
    CheckCircle, Loader2, Clock, AlertCircle, ArrowRight,
} from "lucide-react";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { WorkflowInfoPanel } from "~/components/ui/WorkflowInfoPanel";
import { MaterialVideoPlayer } from "~/components/material/MaterialVideoPlayer";

// ─── Mock data ────────────────────────────────────────────────────────────────

type CapStatus = 'idle' | 'in_progress' | 'completed' | 'failed';

interface CapabilityItem {
    id: string;
    label: string;
    desc: string;
    icon: React.ReactNode;
    status: CapStatus;
    result?: string;
    progress?: number;
    to: string;
}

const MOCK_CAPABILITIES: CapabilityItem[] = [
    { id: 'subtitle',  label: '字幕提取', desc: '提取或生成视频字幕',     icon: <Subtitles className="w-4 h-4" />,    status: 'completed', result: '中文字幕（平台 AI）',  to: 'subtitle'  },
    { id: 'summary',   label: '内容总结', desc: 'AI 摘要 · 关键词 · 知识图谱', icon: <BrainCircuit className="w-4 h-4" />, status: 'in_progress', progress: 62,              to: 'summary'   },
    { id: 'diarize',   label: '声纹分类', desc: '说话人分离与声纹标注',    icon: <Users className="w-4 h-4" />,        status: 'idle',                                    to: 'diarize'   },
    { id: 'frames',    label: '帧分析',   desc: '场景检测 · OCR · 目标检测', icon: <Film className="w-4 h-4" />,         status: 'idle',                                    to: 'frames'    },
    { id: 'transcode', label: '转码与增强', desc: '格式转换 · 降噪 · 超分', icon: <Zap className="w-4 h-4" />,          status: 'completed', result: 'MP4 H.264 已转码', to: 'transcode' },
    { id: 'tasks',     label: '任务历史', desc: '查看所有处理任务',         icon: <ListChecks className="w-4 h-4" />,   status: 'idle',                                    to: 'tasks'     },
];

// ─── Components ───────────────────────────────────────────────────────────────

function statusIcon(status: CapStatus) {
    switch (status) {
        case 'completed':   return <CheckCircle className="w-3.5 h-3.5 text-green-500" />;
        case 'in_progress': return <Loader2 className="w-3.5 h-3.5 text-blue-500 animate-spin" />;
        case 'failed':      return <AlertCircle className="w-3.5 h-3.5 text-red-500" />;
        default:            return <Clock className="w-3.5 h-3.5 text-gray-300" />;
    }
}

function statusLabel(status: CapStatus) {
    switch (status) {
        case 'completed':   return { text: '已完成', cls: 'bg-green-50 text-green-700' };
        case 'in_progress': return { text: '进行中', cls: 'bg-blue-50 text-blue-700' };
        case 'failed':      return { text: '失败',   cls: 'bg-red-50 text-red-600' };
        default:            return { text: '未开始', cls: 'bg-gray-50 text-gray-400' };
    }
}

function CapCard({ item }: { item: CapabilityItem }) {
    const badge = statusLabel(item.status);
    return (
        <Link
            to={item.to}
            className="group flex flex-col gap-3 p-4 bg-white rounded-xl border border-gray-200 hover:border-violet-300 hover:shadow-sm transition-all"
        >
            <div className="flex items-start justify-between">
                <div className="p-2 bg-gray-50 group-hover:bg-violet-50 rounded-lg transition-colors">
                    {item.icon}
                </div>
                <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${badge.cls}`}>{badge.text}</span>
            </div>
            <div>
                <p className="text-sm font-semibold text-gray-800">{item.label}</p>
                <p className="text-xs text-gray-400 mt-0.5">{item.desc}</p>
            </div>
            {item.status === 'in_progress' && item.progress != null && (
                <div className="h-1 bg-gray-100 rounded-full overflow-hidden">
                    <div className="h-full bg-blue-500 rounded-full transition-all duration-500" style={{ width: `${item.progress}%` }} />
                </div>
            )}
            {item.status === 'completed' && item.result && (
                <p className="text-xs text-green-600 truncate">{item.result}</p>
            )}
            <div className="flex items-center justify-end mt-auto">
                <ArrowRight className="w-3.5 h-3.5 text-gray-300 group-hover:text-violet-400 transition-colors" />
            </div>
        </Link>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function MaterialOverviewPage() {
    const { material } = useWorkspaceContext();

    return (
        <div className="max-w-3xl mx-auto p-4 sm:p-6 space-y-6">

            {/* Video player */}
            <section>
                <MaterialVideoPlayer materialId={material.id} mode="inline" sourceType={material.source_type} />
            </section>

            {/* Capability grid */}
            <section>
                <h2 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3">处理能力</h2>
                <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                    {MOCK_CAPABILITIES.map(item => <CapCard key={item.id} item={item} />)}
                </div>
            </section>

            {/* Recent tasks */}
            <section>
                <h2 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3">最近任务</h2>
                <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                    <WorkflowInfoPanel materialId={material.id} active defaultExpanded />
                </div>
            </section>

        </div>
    );
}
