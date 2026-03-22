import { useState } from "react";
import { Users, Play, Pause, Settings2, ChevronDown, Mic, BarChart2 } from "lucide-react";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { ProgressBar } from "~/components/ui/WorkflowInfoPanel";

// ─── Mock data ────────────────────────────────────────────────────────────────

interface Segment {
    start: number;
    end: number;
    text: string;
}

interface Speaker {
    id: string;
    label: string;
    duration: number;
    color: string;
    segments: Segment[];
}

const TOTAL_DURATION = 600; // 10 min mock

const MOCK_SPEAKERS: Speaker[] = [
    {
        id: 'A',
        label: '说话人 A',
        duration: 340,
        color: 'bg-violet-400',
        segments: [
            { start: 0,   end: 90,  text: '大家好，今天我们来聊聊 Transformer 架构……' },
            { start: 150, end: 220, text: '所以这里的关键是 Query、Key、Value 三个矩阵……' },
            { start: 280, end: 360, text: '多头注意力的优势在于可以同时关注不同位置……' },
            { start: 420, end: 510, text: '我们来看一个具体的计算示例……' },
        ],
    },
    {
        id: 'B',
        label: '说话人 B',
        duration: 180,
        color: 'bg-blue-400',
        segments: [
            { start: 90,  end: 150, text: '对，这个和 RNN 的区别非常明显……' },
            { start: 220, end: 280, text: '那 Positional Encoding 又是怎么工作的？' },
            { start: 360, end: 420, text: '我理解了，所以本质上是维度归约然后重组？' },
            { start: 510, end: 570, text: '好的，感谢分享，这部分确实很关键。' },
        ],
    },
];

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatTime(sec: number): string {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}:${String(s).padStart(2, '0')}`;
}

// ─── Timeline ────────────────────────────────────────────────────────────────

function SpeakerTimeline({ speakers }: { speakers: Speaker[] }) {
    return (
        <div className="space-y-2">
            {speakers.map(sp => (
                <div key={sp.id} className="flex items-center gap-3">
                    <span className="text-xs font-medium text-gray-500 w-16 flex-shrink-0 text-right">{sp.label}</span>
                    <div className="flex-1 h-6 bg-gray-100 rounded relative overflow-hidden">
                        {sp.segments.map((seg, i) => (
                            <div
                                key={i}
                                className={`absolute top-0 h-full rounded ${sp.color} opacity-80`}
                                style={{
                                    left:  `${(seg.start / TOTAL_DURATION) * 100}%`,
                                    width: `${((seg.end - seg.start) / TOTAL_DURATION) * 100}%`,
                                }}
                            />
                        ))}
                    </div>
                    <span className="text-xs text-gray-400 font-mono w-12 flex-shrink-0">{formatTime(sp.duration)}</span>
                </div>
            ))}
            {/* Time axis */}
            <div className="flex items-center gap-3">
                <span className="w-16" />
                <div className="flex-1 flex justify-between">
                    {[0, 2, 4, 6, 8, 10].map(m => (
                        <span key={m} className="text-[10px] text-gray-300 font-mono">{m}:00</span>
                    ))}
                </div>
                <span className="w-12" />
            </div>
        </div>
    );
}

// ─── Speaker stats ────────────────────────────────────────────────────────────

function SpeakerStats({ speakers }: { speakers: Speaker[] }) {
    return (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {speakers.map(sp => {
                const pct = Math.round((sp.duration / TOTAL_DURATION) * 100);
                return (
                    <div key={sp.id} className="bg-white rounded-xl border border-gray-200 p-4">
                        <div className="flex items-center justify-between mb-2">
                            <div className="flex items-center gap-2">
                                <div className={`w-2.5 h-2.5 rounded-full ${sp.color}`} />
                                <span className="text-sm font-semibold text-gray-700">{sp.label}</span>
                            </div>
                            <span className="text-sm font-bold text-gray-900 tabular-nums">{pct}%</span>
                        </div>
                        <ProgressBar
                            value={pct}
                            color={sp.color.replace('bg-', 'bg-')}
                            height="h-1.5"
                        />
                        <p className="text-xs text-gray-400 mt-2">总时长 {formatTime(sp.duration)}</p>
                        <div className="mt-3 space-y-1.5">
                            {sp.segments.slice(0, 2).map((seg, i) => (
                                <p key={i} className="text-xs text-gray-500 truncate">
                                    <span className="font-mono text-gray-300 mr-1.5">[{formatTime(seg.start)}]</span>
                                    {seg.text}
                                </p>
                            ))}
                            {sp.segments.length > 2 && (
                                <p className="text-xs text-gray-400">+{sp.segments.length - 2} 条片段…</p>
                            )}
                        </div>
                    </div>
                );
            })}
        </div>
    );
}

// ─── Config panel ─────────────────────────────────────────────────────────────

function DiarizeConfig({ onClose }: { onClose: () => void }) {
    return (
        <div className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">最大说话人数</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>自动检测</option>
                        <option>2</option><option>3</option><option>4</option><option>5+</option>
                    </select>
                </div>
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">前处理</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>DeepFilterNet（推荐）</option>
                        <option>跳过降噪</option>
                    </select>
                </div>
            </div>
            <div className="flex gap-2">
                <button onClick={onClose} className="px-4 py-2 text-sm text-gray-500 bg-white border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors">取消</button>
                <button className="flex-1 flex items-center justify-center gap-2 px-4 py-2 bg-violet-600 hover:bg-violet-700 text-white text-sm font-medium rounded-lg transition-colors">
                    <Users className="w-4 h-4" />
                    开始声纹分类
                </button>
            </div>
        </div>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

const MOCK_DIARIZE_STATUS: 'idle' | 'completed' = 'completed';

export default function DiarizePage() {
    useWorkspaceContext();
    const [configOpen, setConfigOpen] = useState(false);
    const [segmentsExpanded, setSegmentsExpanded] = useState(false);

    const allSegments = MOCK_SPEAKERS
        .flatMap(sp => sp.segments.map(seg => ({ ...seg, speaker: sp })))
        .sort((a, b) => a.start - b.start);

    return (
        <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-5">

            {MOCK_DIARIZE_STATUS === 'idle' ? (
                /* Idle state */
                <div className="bg-white rounded-xl border border-gray-200 p-6">
                    {!configOpen ? (
                        <div className="flex flex-col items-center gap-4 text-center">
                            <div className="p-3 bg-blue-50 rounded-full">
                                <Mic className="w-7 h-7 text-blue-500" />
                            </div>
                            <div>
                                <p className="text-sm font-medium text-gray-700">尚未运行声纹分类</p>
                                <p className="text-xs text-gray-400 mt-1">将音频按说话人切分，输出带时间戳的分段</p>
                            </div>
                            <button
                                onClick={() => setConfigOpen(true)}
                                className="flex items-center gap-2 px-5 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-lg transition-colors"
                            >
                                <Users className="w-4 h-4" />
                                配置并开始
                            </button>
                        </div>
                    ) : (
                        <DiarizeConfig onClose={() => setConfigOpen(false)} />
                    )}
                </div>
            ) : (
                <>
                    {/* Timeline */}
                    <div className="bg-white rounded-xl border border-gray-200 p-4">
                        <div className="flex items-center justify-between mb-3">
                            <h2 className="text-sm font-semibold text-gray-700 flex items-center gap-2">
                                <BarChart2 className="w-4 h-4 text-blue-500" />
                                说话人时间轴
                            </h2>
                            <button
                                onClick={() => setConfigOpen(v => !v)}
                                className="p-1.5 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded transition-colors"
                                title="重新配置"
                            >
                                <Settings2 className="w-3.5 h-3.5" />
                            </button>
                        </div>
                        {configOpen && (
                            <div className="mb-4 pb-4 border-b border-gray-100">
                                <DiarizeConfig onClose={() => setConfigOpen(false)} />
                            </div>
                        )}
                        <SpeakerTimeline speakers={MOCK_SPEAKERS} />
                    </div>

                    {/* Stats */}
                    <SpeakerStats speakers={MOCK_SPEAKERS} />

                    {/* All segments */}
                    <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                        <button
                            onClick={() => setSegmentsExpanded(v => !v)}
                            className="w-full flex items-center gap-2 px-4 py-3 hover:bg-gray-50 transition-colors"
                        >
                            <Play className="w-3.5 h-3.5 text-gray-400" />
                            <span className="text-sm font-semibold text-gray-700 flex-1 text-left">
                                全部片段（{allSegments.length}）
                            </span>
                            <ChevronDown className={`w-3.5 h-3.5 text-gray-400 transition-transform ${segmentsExpanded ? '' : '-rotate-90'}`} />
                        </button>
                        {segmentsExpanded && (
                            <div className="divide-y divide-gray-50 max-h-72 overflow-y-auto">
                                {allSegments.map((seg, i) => (
                                    <div key={i} className="flex items-start gap-3 px-4 py-2.5">
                                        <span className="text-xs font-mono text-gray-300 flex-shrink-0 pt-0.5">{formatTime(seg.start)}</span>
                                        <div className={`w-1.5 h-1.5 rounded-full mt-1.5 flex-shrink-0 ${seg.speaker.color}`} />
                                        <div className="flex-1 min-w-0">
                                            <span className="text-[10px] text-gray-400">{seg.speaker.label}</span>
                                            <p className="text-xs text-gray-700 leading-relaxed mt-0.5">{seg.text}</p>
                                        </div>
                                        <button className="p-1 text-gray-300 hover:text-gray-500 flex-shrink-0">
                                            <Pause className="w-3 h-3" />
                                        </button>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                </>
            )}
        </div>
    );
}
