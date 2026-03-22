import { useState } from "react";
import { Zap, FileVideo, Volume2, ChevronDown, ArrowRightLeft, MessageSquareText, CheckCircle, Wand2 } from "lucide-react";
import { useWorkspaceContext } from "~/routes/material.$materialId";

// ─── Helpers ──────────────────────────────────────────────────────────────────

function SectionCard({ title, icon, badge, children, defaultOpen = true }: {
    title: string;
    icon: React.ReactNode;
    badge?: React.ReactNode;
    children: React.ReactNode;
    defaultOpen?: boolean;
}) {
    const [open, setOpen] = useState(defaultOpen);
    return (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <button
                onClick={() => setOpen(v => !v)}
                className="w-full flex items-center gap-2.5 px-4 py-3 border-b border-gray-100 hover:bg-gray-50 transition-colors"
            >
                <span className="text-gray-500">{icon}</span>
                <span className="text-sm font-semibold text-gray-700 flex-1 text-left">{title}</span>
                {badge}
                <ChevronDown className={`w-3.5 h-3.5 text-gray-400 transition-transform ${open ? '' : '-rotate-90'}`} />
            </button>
            {open && <div className="p-4">{children}</div>}
        </div>
    );
}

// ─── Transcode ────────────────────────────────────────────────────────────────

function TranscodeSection() {
    const MOCK_DONE = true;
    return (
        <div className="space-y-4">
            {MOCK_DONE && (
                <div className="flex items-center gap-2 p-3 bg-green-50 rounded-lg border border-green-200">
                    <CheckCircle className="w-4 h-4 text-green-500 flex-shrink-0" />
                    <p className="text-sm text-green-700">已转码：MP4 H.264 · 1080p · 42.3 MB</p>
                </div>
            )}
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">目标容器</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>MP4</option><option>MKV</option><option>WebM</option><option>MOV</option>
                    </select>
                </div>
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">视频编码</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>H.264 (libx264)</option><option>H.265 (HEVC)</option><option>AV1</option>
                    </select>
                </div>
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">分辨率</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>保持原始</option><option>1080p</option><option>720p</option><option>480p</option>
                    </select>
                </div>
            </div>
            <button className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-gray-800 hover:bg-gray-900 text-white text-sm font-medium rounded-lg transition-colors">
                <FileVideo className="w-4 h-4" />
                {MOCK_DONE ? '重新转码' : '开始转码'}
            </button>
        </div>
    );
}

// ─── Audio enhance ────────────────────────────────────────────────────────────

function AudioEnhanceSection() {
    return (
        <div className="space-y-3">
            <p className="text-xs text-gray-500 leading-relaxed">使用深度学习模型对音频进行降噪和语音增强，提升后续 ASR / 声纹分类的准确率。</p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">降噪模型</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>DeepFilterNet（推荐）</option>
                        <option>MetricGAN+</option>
                        <option>RNNoise</option>
                    </select>
                </div>
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">输出格式</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>WAV（无损）</option>
                        <option>FLAC</option>
                    </select>
                </div>
            </div>
            <button className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-gray-800 hover:bg-gray-900 text-white text-sm font-medium rounded-lg transition-colors">
                <Volume2 className="w-4 h-4" />
                开始音频增强
            </button>
        </div>
    );
}

// ─── Subtitle ops ─────────────────────────────────────────────────────────────

function SubtitleOpsSection() {
    return (
        <div className="space-y-3">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
                {[
                    { label: '字幕烧录', icon: <MessageSquareText className="w-4 h-4" />, desc: '将字幕硬编码进视频' },
                    { label: '时轴对齐', icon: <ArrowRightLeft className="w-4 h-4" />, desc: '自动校正字幕时间轴' },
                    { label: '格式转换', icon: <Wand2 className="w-4 h-4" />, desc: 'SRT ↔ ASS ↔ VTT' },
                ].map(op => (
                    <button
                        key={op.label}
                        className="flex flex-col items-start gap-2 p-3 rounded-lg border border-gray-200 hover:border-violet-300 hover:bg-violet-50 transition-colors text-left"
                    >
                        <span className="text-gray-500">{op.icon}</span>
                        <div>
                            <p className="text-sm font-medium text-gray-700">{op.label}</p>
                            <p className="text-xs text-gray-400 mt-0.5">{op.desc}</p>
                        </div>
                    </button>
                ))}
            </div>
        </div>
    );
}

// ─── Upscale ──────────────────────────────────────────────────────────────────

function UpscaleSection() {
    return (
        <div className="space-y-3">
            <p className="text-xs text-gray-500">使用 Real-ESRGAN / EDSR 对视频进行超分辨率放大（2× / 4×）。注意：耗时较长，需 GPU。</p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">放大倍数</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>2×</option><option>4×</option>
                    </select>
                </div>
                <div>
                    <label className="text-xs text-gray-500 mb-1 block">模型</label>
                    <select className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                        <option>Real-ESRGAN（推荐）</option>
                        <option>EDSR</option>
                    </select>
                </div>
            </div>
            <button className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-gray-800 hover:bg-gray-900 text-white text-sm font-medium rounded-lg transition-colors">
                <Zap className="w-4 h-4" />
                开始超分
            </button>
        </div>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function TranscodePage() {
    useWorkspaceContext();
    return (
        <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-4">

            <SectionCard
                title="视频转码"
                icon={<FileVideo className="w-4 h-4" />}
                badge={<span className="text-[10px] bg-green-50 text-green-600 px-1.5 py-0.5 rounded font-medium">已完成</span>}
            >
                <TranscodeSection />
            </SectionCard>

            <SectionCard title="音频增强 / 降噪" icon={<Volume2 className="w-4 h-4" />} defaultOpen={false}>
                <AudioEnhanceSection />
            </SectionCard>

            <SectionCard title="字幕操作" icon={<MessageSquareText className="w-4 h-4" />} defaultOpen={false}>
                <SubtitleOpsSection />
            </SectionCard>

            <SectionCard title="视频超分辨率" icon={<Zap className="w-4 h-4" />} defaultOpen={false}>
                <UpscaleSection />
            </SectionCard>

        </div>
    );
}
