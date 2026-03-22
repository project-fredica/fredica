import { useState } from "react";
import {
    Film, Scissors, Image, ScanSearch, Target,
    ChevronDown, CheckCircle, Clock, Play,
} from "lucide-react";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { ProgressBar } from "~/components/ui/WorkflowInfoPanel";

// ─── Mock data ────────────────────────────────────────────────────────────────

const MOCK_SCENES = [
    { id: '1', start: 0,   end: 92,  label: '开场介绍' },
    { id: '2', start: 92,  end: 213, label: 'QKV 推导' },
    { id: '3', start: 213, end: 305, label: '多头注意力' },
    { id: '4', start: 305, end: 418, label: '代码演示' },
    { id: '5', start: 418, end: 490, label: '性能对比' },
    { id: '6', start: 490, end: 543, label: '总结' },
];

const MOCK_THUMBNAILS = [
    { id: '1', time: 0,   label: '00:00' },
    { id: '2', time: 60,  label: '01:00' },
    { id: '3', time: 120, label: '02:00' },
    { id: '4', time: 180, label: '03:00' },
    { id: '5', time: 240, label: '04:00' },
    { id: '6', time: 300, label: '05:00' },
];

function formatTime(sec: number): string {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}:${String(s).padStart(2, '0')}`;
}

// ─── Section wrapper ──────────────────────────────────────────────────────────

function SectionCard({ title, icon, badge, status, children, defaultOpen = true }: {
    title: string;
    icon: React.ReactNode;
    badge?: React.ReactNode;
    status?: 'idle' | 'in_progress' | 'completed';
    children: React.ReactNode;
    defaultOpen?: boolean;
}) {
    const [open, setOpen] = useState(defaultOpen);
    const statusDot = status === 'completed' ? 'bg-green-400' : status === 'in_progress' ? 'bg-blue-400 animate-pulse' : 'bg-gray-200';
    return (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <button
                onClick={() => setOpen(v => !v)}
                className="w-full flex items-center gap-2.5 px-4 py-3 border-b border-gray-100 hover:bg-gray-50 transition-colors"
            >
                <span className="text-gray-500">{icon}</span>
                <span className="text-sm font-semibold text-gray-700 flex-1 text-left">{title}</span>
                {badge}
                <div className={`w-2 h-2 rounded-full flex-shrink-0 ${statusDot}`} />
                <ChevronDown className={`w-3.5 h-3.5 text-gray-400 transition-transform ${open ? '' : '-rotate-90'}`} />
            </button>
            {open && <div className="p-4">{children}</div>}
        </div>
    );
}

// ─── Scene list ───────────────────────────────────────────────────────────────

function SceneList() {
    return (
        <div className="space-y-1.5">
            {MOCK_SCENES.map((scene, i) => (
                <div key={scene.id} className="flex items-center gap-3 p-2.5 rounded-lg hover:bg-gray-50 transition-colors group">
                    <span className="text-[10px] font-mono text-gray-300 w-5 text-right">{i + 1}</span>
                    <div className="flex-1 h-1 bg-gray-100 rounded-full relative overflow-hidden">
                        <div className="absolute h-full bg-violet-300 rounded-full" style={{ width: '100%' }} />
                    </div>
                    <span className="text-xs font-mono text-gray-400 flex-shrink-0">
                        {formatTime(scene.start)} → {formatTime(scene.end)}
                    </span>
                    <span className="text-xs text-gray-500 flex-shrink-0 hidden sm:block">{scene.label}</span>
                    <button className="p-1 text-gray-300 group-hover:text-gray-500 transition-colors flex-shrink-0">
                        <Play className="w-3 h-3" />
                    </button>
                </div>
            ))}
            <p className="text-xs text-gray-400 text-center pt-1">共检测到 {MOCK_SCENES.length} 个镜头切换点</p>
        </div>
    );
}

// ─── Thumbnail grid ───────────────────────────────────────────────────────────

function ThumbnailGrid() {
    return (
        <div className="space-y-3">
            <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-6 gap-2">
                {MOCK_THUMBNAILS.map(t => (
                    <div key={t.id} className="group relative aspect-video bg-gray-100 rounded-lg overflow-hidden border border-gray-200 hover:border-violet-300 transition-colors cursor-pointer">
                        {/* Placeholder thumbnail */}
                        <div className="absolute inset-0 flex items-center justify-center">
                            <Film className="w-5 h-5 text-gray-300" />
                        </div>
                        <div className="absolute bottom-0 left-0 right-0 bg-black/50 py-0.5 text-center">
                            <span className="text-[9px] text-white font-mono">{t.label}</span>
                        </div>
                        <CheckCircle className="absolute top-1 right-1 w-3 h-3 text-violet-400 opacity-0 group-hover:opacity-100 transition-opacity" />
                    </div>
                ))}
            </div>
            <button className="w-full flex items-center justify-center gap-2 py-2 text-sm text-gray-500 bg-gray-50 hover:bg-gray-100 border border-dashed border-gray-200 rounded-lg transition-colors">
                <Image className="w-3.5 h-3.5" />
                生成更多缩略图
            </button>
        </div>
    );
}

// ─── Object detect config ─────────────────────────────────────────────────────

function ObjectDetectConfig() {
    return (
        <div className="space-y-3">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">检测模型</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>YOLO v8（推荐）</option>
                        <option>RT-DETR</option>
                    </select>
                </div>
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">采样间隔</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>每 5 秒</option>
                        <option>每 2 秒</option>
                        <option>每帧</option>
                    </select>
                </div>
            </div>
            <button className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-gray-800 hover:bg-gray-900 text-white text-sm font-medium rounded-lg transition-colors">
                <Target className="w-4 h-4" />
                开始目标检测
            </button>
        </div>
    );
}

// ─── Extract frames config ────────────────────────────────────────────────────

function ExtractFramesConfig() {
    return (
        <div className="space-y-3">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">采样间隔</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>每 5 秒</option>
                        <option>每 1 秒</option>
                        <option>每 30 秒</option>
                    </select>
                </div>
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">输出格式</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>PNG</option>
                        <option>JPEG</option>
                    </select>
                </div>
            </div>
            <button className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-gray-800 hover:bg-gray-900 text-white text-sm font-medium rounded-lg transition-colors">
                <Scissors className="w-4 h-4" />
                开始抽帧
            </button>
        </div>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function FramesPage() {
    useWorkspaceContext();
    return (
        <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-4">

            <SectionCard
                title="场景检测"
                icon={<Scissors className="w-4 h-4" />}
                status="completed"
                badge={<span className="text-[10px] bg-green-50 text-green-600 px-1.5 py-0.5 rounded font-medium">{MOCK_SCENES.length} 个镜头</span>}
            >
                <SceneList />
            </SectionCard>

            <SectionCard
                title="封面与缩略图"
                icon={<Image className="w-4 h-4" />}
                status="completed"
                badge={<span className="text-[10px] bg-green-50 text-green-600 px-1.5 py-0.5 rounded font-medium">{MOCK_THUMBNAILS.length} 张</span>}
            >
                <ThumbnailGrid />
            </SectionCard>

            <SectionCard
                title="视频帧提取"
                icon={<Film className="w-4 h-4" />}
                status="idle"
                defaultOpen={false}
            >
                <ExtractFramesConfig />
            </SectionCard>

            <SectionCard
                title="目标检测"
                icon={<Target className="w-4 h-4" />}
                status="idle"
                defaultOpen={false}
            >
                <div className="mb-3 p-3 bg-gray-50 rounded-lg border border-gray-100">
                    <p className="text-xs text-gray-500">对视频帧中的物体进行分类与定位（需要先完成帧提取）。</p>
                </div>
                <ObjectDetectConfig />
            </SectionCard>

            <SectionCard
                title="硬字幕检测"
                icon={<ScanSearch className="w-4 h-4" />}
                status="idle"
                defaultOpen={false}
            >
                <div className="space-y-3">
                    <p className="text-xs text-gray-500">采样视频帧，通过 Vision LLM / OCR 检测是否存在硬字幕及其区域坐标。</p>
                    <div className="flex items-center gap-3">
                        <Clock className="w-3.5 h-3.5 text-gray-400" />
                        <span className="text-xs text-gray-400">预计耗时 &lt;1 min</span>
                    </div>
                    <button className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-gray-800 hover:bg-gray-900 text-white text-sm font-medium rounded-lg transition-colors">
                        <ScanSearch className="w-4 h-4" />
                        开始检测
                    </button>
                </div>
            </SectionCard>

            {/* Overall progress indicator (mock) */}
            <div className="bg-gray-50 rounded-xl border border-gray-100 px-4 py-3">
                <div className="flex items-center justify-between mb-1.5">
                    <p className="text-xs font-medium text-gray-500">帧分析整体进度</p>
                    <span className="text-xs font-mono text-gray-400">2 / 4 已完成</span>
                </div>
                <ProgressBar value={50} color="bg-violet-400" height="h-1.5" />
            </div>

        </div>
    );
}
