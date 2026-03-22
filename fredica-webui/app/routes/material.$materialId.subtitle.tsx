import { useState, useEffect } from "react";
import { Link } from "react-router";
import {
    Subtitles, Mic, ScanText, FileVideo2,
    CheckCircle, ChevronRight, ChevronDown, Clock,
    ExternalLink, Loader, RefreshCw,
} from "lucide-react";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { useAppFetch } from "~/util/app_fetch";

// ─── Types ────────────────────────────────────────────────────────────────────

/** API response item from MaterialSubtitleListRoute */
interface SubtitleApiItem {
    lan: string;
    lan_doc: string;
    source: string;       // "bilibili_platform" etc.
    queried_at: number;   // Unix seconds
    subtitle_url: string;
    type: number;         // 0=human, 1=AI
}

const SOURCE_LABEL: Record<string, { text: string; cls: string }> = {
    bilibili_platform: { text: '平台字幕', cls: 'bg-blue-50 text-blue-600' },
    asr:               { text: 'Whisper ASR', cls: 'bg-violet-50 text-violet-600' },
    ocr:               { text: 'OCR 识别', cls: 'bg-amber-50 text-amber-600' },
    embedded:          { text: '内嵌字幕轨', cls: 'bg-green-50 text-green-700' },
};

// ─── SubtitleChip ─────────────────────────────────────────────────────────────

function SubtitleChip({ sub }: { sub: SubtitleApiItem }) {
    const badge = SOURCE_LABEL[sub.source] ?? { text: sub.source, cls: 'bg-gray-100 text-gray-500' };
    const dateStr = new Date(sub.queried_at * 1000).toLocaleDateString('zh-CN');
    return (
        <div className="inline-flex items-center gap-1.5 px-2.5 py-1.5 bg-white border border-gray-200 rounded-lg text-xs">
            <CheckCircle className="w-3 h-3 text-green-500 flex-shrink-0" />
            <span className="font-medium text-gray-800">{sub.lan_doc || sub.lan}</span>
            {sub.type === 1 && <span className="text-gray-400">AI</span>}
            <span className={`px-1 py-0.5 rounded font-medium ${badge.cls}`}>{badge.text}</span>
            <span className="text-gray-400">{dateStr}</span>
        </div>
    );
}

// ─── Scheme panel ─────────────────────────────────────────────────────────────

type Scheme = 'platform' | 'embedded' | 'asr' | 'ocr';

interface SchemeConfig {
    id: Scheme;
    label: string;
    desc: string;
    icon: React.ReactNode;
    /** 尚未实现的方案，禁用点击和选中 */
    disabled?: boolean;
}

const SCHEMES: SchemeConfig[] = [
    { id: 'platform', label: '平台字幕',           desc: '下载 Bilibili / YouTube 等平台提供的 CC / AI 字幕',  icon: <Subtitles className="w-4 h-4" /> },
    { id: 'embedded', label: '内嵌字幕轨',          desc: '从 MKV / MP4 中提取封装的 SRT / ASS 字幕轨道',      icon: <FileVideo2 className="w-4 h-4" />, disabled: true },
    { id: 'asr',      label: '语音识别（Whisper）', desc: '使用本地 Faster-Whisper 模型将音频转换为文字',        icon: <Mic className="w-4 h-4" />,       disabled: true },
    { id: 'ocr',      label: 'OCR 硬字幕识别',      desc: '从视频帧中识别烧录在画面上的字幕（适合老片）',        icon: <ScanText className="w-4 h-4" />,  disabled: true },
];

function SchemeCard({ scheme, selected, onSelect }: { scheme: SchemeConfig; selected: boolean; onSelect: () => void }) {
    return (
        <button
            onClick={onSelect}
            disabled={scheme.disabled}
            className={`w-full flex items-start gap-3 p-3 rounded-lg border text-left transition-colors ${
                scheme.disabled
                    ? 'border-gray-100 bg-gray-50 opacity-50 cursor-not-allowed'
                    : selected
                        ? 'border-violet-400 bg-violet-50'
                        : 'border-gray-200 bg-white hover:border-gray-300 hover:bg-gray-50'
            }`}
        >
            <div className={`p-1.5 rounded-md flex-shrink-0 ${
                scheme.disabled
                    ? 'bg-gray-100 text-gray-400'
                    : selected ? 'bg-violet-100 text-violet-600' : 'bg-gray-100 text-gray-500'
            }`}>
                {scheme.icon}
            </div>
            <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                    <p className={`text-sm font-medium ${
                        scheme.disabled ? 'text-gray-400' : selected ? 'text-violet-800' : 'text-gray-700'
                    }`}>{scheme.label}</p>
                    {scheme.disabled && (
                        <span className="text-[9px] px-1 py-0.5 bg-gray-100 text-gray-400 rounded font-medium">即将推出</span>
                    )}
                </div>
                <p className="text-xs text-gray-400 mt-0.5 leading-relaxed">{scheme.desc}</p>
            </div>
            {selected && !scheme.disabled && <CheckCircle className="w-4 h-4 text-violet-500 flex-shrink-0 mt-0.5" />}
        </button>
    );
}

// ─── Platform scheme detail ───────────────────────────────────────────────────

function PlatformSchemeDetail({ isBilibili }: { isBilibili: boolean }) {
    if (isBilibili) {
        return (
            <div className="space-y-3 pt-1">
                <div className="p-3 bg-blue-50 rounded-lg border border-blue-200">
                    <p className="text-sm text-blue-700">
                        B站素材支持直接查询平台提供的 CC / AI 字幕，无需本地转录。
                    </p>
                </div>
                <Link
                    to="../subtitle-bilibili"
                    relative="path"
                    className="flex items-center justify-center gap-2 w-full px-4 py-2.5 bg-violet-600 hover:bg-violet-700 text-white text-sm font-medium rounded-lg transition-colors"
                >
                    <ExternalLink className="w-4 h-4" />
                    查询 B站平台字幕
                    <ChevronRight className="w-4 h-4" />
                </Link>
            </div>
        );
    }
    return (
        <div className="pt-1 space-y-3">
            <div className="p-3 bg-amber-50 rounded-lg border border-amber-200">
                <p className="text-sm text-amber-700">该素材来源不支持平台字幕查询，请选择其他方案。</p>
            </div>
        </div>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function SubtitlePage() {
    const { material } = useWorkspaceContext();
    const isBilibili = material.source_type === 'bilibili';
    const [selectedScheme, setSelectedScheme] = useState<Scheme>('platform');
    const [schemeExpanded, setSchemeExpanded] = useState(true);
    const [subtitles, setSubtitles] = useState<SubtitleApiItem[]>([]);
    const [subtitlesLoading, setSubtitlesLoading] = useState(true);
    const [subtitlesRefresh, setSubtitlesRefresh] = useState(0);
    const { apiFetch } = useAppFetch();

    useEffect(() => {
        let cancelled = false;
        setSubtitlesLoading(true);
        setSubtitles([]);
        apiFetch(
            `/api/v1/MaterialSubtitleListRoute?material_id=${encodeURIComponent(material.id)}`,
            { method: 'GET' },
            { silent: true },
        )
            .then(({ data }) => { if (!cancelled && Array.isArray(data)) setSubtitles(data as SubtitleApiItem[]); })
            .catch(() => { /* ignore */ })
            .finally(() => { if (!cancelled) setSubtitlesLoading(false); });
        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [material.id, subtitlesRefresh]);

    return (
        <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-5">

            {/* Existing subtitles */}
            <section>
                <div className="flex items-center justify-between mb-3">
                    <h2 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">已有字幕</h2>
                    <button
                        onClick={() => setSubtitlesRefresh(n => n + 1)}
                        disabled={subtitlesLoading}
                        className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors disabled:opacity-40"
                        title="刷新"
                    >
                        <RefreshCw className={`w-3.5 h-3.5 text-gray-500 ${subtitlesLoading ? 'animate-spin' : ''}`} />
                    </button>
                </div>
                {subtitlesLoading ? (
                    <div className="flex items-center gap-2 py-2 text-gray-400">
                        <Loader className="w-4 h-4 animate-spin" />
                        <span className="text-sm">查询缓存字幕…</span>
                    </div>
                ) : subtitles.length === 0 ? (
                    <p className="text-sm text-gray-400">暂无缓存字幕，可通过下方方式获取。</p>
                ) : (
                    <div className="flex flex-wrap gap-2">
                        {subtitles.map(s => (
                            <SubtitleChip key={`${s.lan}-${s.source}`} sub={s} />
                        ))}
                    </div>
                )}
            </section>

            {/* Scheme selector */}
            <section>
                <button
                    onClick={() => setSchemeExpanded(v => !v)}
                    className="flex items-center gap-2 text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3 hover:text-gray-600 transition-colors"
                >
                    <ChevronDown className={`w-3.5 h-3.5 transition-transform ${schemeExpanded ? '' : '-rotate-90'}`} />
                    新增提取
                </button>
                {schemeExpanded && (
                    <div className="space-y-4">
                        {/* Scheme cards */}
                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                            {SCHEMES.map(s => (
                                <SchemeCard key={s.id} scheme={s} selected={selectedScheme === s.id} onSelect={() => setSelectedScheme(s.id)} />
                            ))}
                        </div>
                        {/* Scheme detail — only platform is currently active */}
                        <div className="bg-white rounded-xl border border-gray-200 p-4">
                            {selectedScheme === 'platform' && <PlatformSchemeDetail isBilibili={isBilibili} />}
                        </div>
                    </div>
                )}
            </section>

            {/* Format info */}
            <section className="bg-gray-50 rounded-xl border border-gray-100 px-4 py-3">
                <div className="flex items-center gap-2 mb-2">
                    <Clock className="w-3.5 h-3.5 text-gray-400" />
                    <p className="text-xs font-medium text-gray-500">字幕格式说明</p>
                </div>
                <p className="text-xs text-gray-400 leading-relaxed">
                    提取结果默认保存为 SRT 格式。支持后续通过「转码」标签进行 SRT ↔ ASS ↔ VTT 互转或时轴校正。
                </p>
            </section>

        </div>
    );
}
