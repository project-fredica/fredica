import { useState, useEffect, useRef } from "react";
import { useParams, useNavigate } from "react-router";
import { ArrowLeft, CheckCircle, ChevronRight, Loader, Mic, Subtitles, AlertCircle, Info, Activity } from "lucide-react";
import { Link } from "react-router";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { type MaterialVideo, type BilibiliExtra } from "~/components/material-library/materialTypes";
import { type WebenSource, getAnalysisStatusInfo } from "~/util/weben";
import { ProgressBar, WorkflowInfoPanel } from "~/components/ui/WorkflowInfoPanel";

// ─── Types ─────────────────────────────────────────────────────────────────────

interface SubtitleMeta {
    id: string;
    lan: string;
    lan_doc: string;
    subtitle_url: string;
    type: number; // 1=AI字幕, 0=人工字幕
}

interface SubtitleCheckResult {
    code: number;
    subtitles: SubtitleMeta[] | null;
}

interface WhisperCompatEntry {
    model: string;
    label: string;
    description: string;
    recommended?: boolean;
    requires_gpu?: boolean;
    vram_gb?: number;
    speed?: string;
    accuracy?: string;
}

interface WhisperConfigInfo {
    model: string;
    compute_type: string;
    device: string;
    compat_json: string; // JSON string of WhisperCompatEntry[]
}

// ─── Step indicator ────────────────────────────────────────────────────────────

function StepDot({ n, active, done }: { n: number; active: boolean; done: boolean }) {
    return (
        <div className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-semibold flex-shrink-0
            ${done  ? 'bg-violet-600 text-white' :
              active ? 'bg-violet-100 text-violet-700 ring-2 ring-violet-400' :
                       'bg-gray-100 text-gray-400'}`}>
            {done ? <CheckCircle className="w-4 h-4" /> : n}
        </div>
    );
}

function StepRow({ n, label, active, done }: { n: number; label: string; active: boolean; done: boolean }) {
    return (
        <div className="flex items-center gap-2">
            <StepDot n={n} active={active} done={done} />
            <span className={`text-sm ${active ? 'text-gray-900 font-medium' : done ? 'text-violet-600' : 'text-gray-400'}`}>
                {label}
            </span>
        </div>
    );
}

// ─── Step 1: 预检字幕 ──────────────────────────────────────────────────────────

function Step1Checking() {
    return (
        <div className="flex flex-col items-center gap-3 py-10">
            <Loader className="w-8 h-8 animate-spin text-violet-500" />
            <p className="text-sm text-gray-500">正在检查平台字幕…</p>
        </div>
    );
}

// ─── Step 2: 字幕选择 ──────────────────────────────────────────────────────────

function Step2SubtitleChoice({
    subtitles,
    onUsePlatform,
    onUseWhisper,
}: {
    subtitles: SubtitleMeta[];
    onUsePlatform: (sub: SubtitleMeta) => void;
    onUseWhisper: () => void;
}) {
    const [selected, setSelected] = useState<string>(subtitles[0]?.id ?? '');
    const typeLabel = (type: number) => type === 1 ? 'AI 字幕' : '人工字幕';

    return (
        <div className="space-y-4">
            <div className="flex items-start gap-2 p-3 bg-green-50 rounded-lg border border-green-200">
                <CheckCircle className="w-4 h-4 text-green-600 mt-0.5 flex-shrink-0" />
                <p className="text-sm text-green-700">
                    检测到 {subtitles.length} 条平台字幕，可直接使用，无需本地转录。
                </p>
            </div>

            <div className="space-y-2">
                <p className="text-xs font-medium text-gray-500 uppercase tracking-wide">选择字幕轨道</p>
                {subtitles.map(sub => (
                    <label
                        key={sub.id}
                        className={`flex items-center gap-3 p-3 rounded-lg border cursor-pointer transition-colors
                            ${selected === sub.id
                                ? 'border-violet-400 bg-violet-50'
                                : 'border-gray-200 hover:border-gray-300 bg-white'}`}
                    >
                        <input
                            type="radio"
                            name="subtitle"
                            value={sub.id}
                            checked={selected === sub.id}
                            onChange={() => setSelected(sub.id)}
                            className="accent-violet-600"
                        />
                        <div className="flex-1 min-w-0">
                            <p className="text-sm font-medium text-gray-800">{sub.lan_doc}</p>
                            <p className="text-xs text-gray-400">{sub.lan} · {typeLabel(sub.type)}</p>
                        </div>
                        {selected === sub.id && <CheckCircle className="w-4 h-4 text-violet-500 flex-shrink-0" />}
                    </label>
                ))}
            </div>

            <div className="flex flex-col gap-2 pt-2">
                <button
                    onClick={() => { const sub = subtitles.find(s => s.id === selected); if (sub) onUsePlatform(sub); }}
                    className="flex items-center justify-center gap-2 w-full px-4 py-2.5 bg-violet-600 hover:bg-violet-700 text-white text-sm font-medium rounded-lg transition-colors"
                >
                    <Subtitles className="w-4 h-4" />
                    使用平台字幕
                    <ChevronRight className="w-4 h-4" />
                </button>
                <button
                    onClick={onUseWhisper}
                    className="flex items-center justify-center gap-2 w-full px-4 py-2.5 bg-white hover:bg-gray-50 text-gray-600 text-sm font-medium rounded-lg border border-gray-200 transition-colors"
                >
                    <Mic className="w-4 h-4" />
                    自行提取字幕（Whisper ASR）
                </button>
            </div>
        </div>
    );
}

// ─── Step 2b: 无字幕提示 ───────────────────────────────────────────────────────

function Step2NoSubtitle({ onContinue }: { onContinue: () => void }) {
    return (
        <div className="space-y-4">
            <div className="flex items-start gap-2 p-3 bg-amber-50 rounded-lg border border-amber-200">
                <AlertCircle className="w-4 h-4 text-amber-600 mt-0.5 flex-shrink-0" />
                <p className="text-sm text-amber-700">
                    未检测到平台提供的字幕，将使用本地 Whisper ASR 进行语音转录。
                </p>
            </div>
            <button
                onClick={onContinue}
                className="flex items-center justify-center gap-2 w-full px-4 py-2.5 bg-violet-600 hover:bg-violet-700 text-white text-sm font-medium rounded-lg transition-colors"
            >
                <Mic className="w-4 h-4" />
                继续，选择 ASR 模型
                <ChevronRight className="w-4 h-4" />
            </button>
        </div>
    );
}

// ─── Step 3: 模型选择 ──────────────────────────────────────────────────────────

function Step3ModelSelect({
    configInfo,
    onConfirm,
    submitting,
}: {
    configInfo: WhisperConfigInfo;
    onConfirm: (model: string) => void;
    submitting: boolean;
}) {
    let compatList: WhisperCompatEntry[] = [];
    try { compatList = JSON.parse(configInfo.compat_json) as WhisperCompatEntry[]; } catch { /* ignore */ }

    const defaultModel = configInfo.model || (compatList[0]?.model ?? '');
    const [selected, setSelected] = useState<string>(defaultModel);
    const selectedEntry = compatList.find(e => e.model === selected);

    return (
        <div className="space-y-4">
            <div className="flex items-start gap-2 p-3 bg-blue-50 rounded-lg border border-blue-200">
                <Info className="w-4 h-4 text-blue-600 mt-0.5 flex-shrink-0" />
                <div className="text-sm text-blue-700 space-y-0.5">
                    <p>当前配置：设备 <span className="font-medium">{configInfo.device}</span>，精度 <span className="font-medium">{configInfo.compute_type}</span></p>
                    <p className="text-xs text-blue-500">模型越大精度越高，但速度越慢、显存占用越多。</p>
                </div>
            </div>

            {compatList.length > 0 ? (
                <div className="space-y-2">
                    <p className="text-xs font-medium text-gray-500 uppercase tracking-wide">选择 Whisper 模型</p>
                    {compatList.map(entry => (
                        <label
                            key={entry.model}
                            className={`flex items-start gap-3 p-3 rounded-lg border cursor-pointer transition-colors
                                ${selected === entry.model
                                    ? 'border-violet-400 bg-violet-50'
                                    : 'border-gray-200 hover:border-gray-300 bg-white'}`}
                        >
                            <input
                                type="radio"
                                name="model"
                                value={entry.model}
                                checked={selected === entry.model}
                                onChange={() => setSelected(entry.model)}
                                className="accent-violet-600 mt-0.5"
                            />
                            <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-2 flex-wrap">
                                    <span className="text-sm font-medium text-gray-800">{entry.label || entry.model}</span>
                                    {entry.recommended && (
                                        <span className="text-[10px] px-1.5 py-0.5 bg-violet-100 text-violet-700 rounded-full font-medium">推荐</span>
                                    )}
                                    {entry.requires_gpu && (
                                        <span className="text-[10px] px-1.5 py-0.5 bg-orange-100 text-orange-700 rounded-full font-medium">需要 GPU</span>
                                    )}
                                </div>
                                {entry.description && <p className="text-xs text-gray-500 mt-0.5">{entry.description}</p>}
                                <div className="flex items-center gap-3 mt-1 flex-wrap">
                                    {entry.speed    && <span className="text-[10px] text-gray-400">速度：{entry.speed}</span>}
                                    {entry.accuracy && <span className="text-[10px] text-gray-400">精度：{entry.accuracy}</span>}
                                    {entry.vram_gb != null && <span className="text-[10px] text-gray-400">显存：{entry.vram_gb} GB</span>}
                                </div>
                            </div>
                            {selected === entry.model && <CheckCircle className="w-4 h-4 text-violet-500 flex-shrink-0 mt-0.5" />}
                        </label>
                    ))}
                </div>
            ) : (
                <div className="p-3 bg-gray-50 rounded-lg border border-gray-200">
                    <p className="text-sm text-gray-600">
                        将使用当前配置的默认模型：<span className="font-medium">{configInfo.model || '（未配置）'}</span>
                    </p>
                </div>
            )}

            {selectedEntry && (
                <div className="p-3 bg-gray-50 rounded-lg text-xs text-gray-500 space-y-1">
                    <p className="font-medium text-gray-700">已选：{selectedEntry.label || selectedEntry.model}</p>
                    {selectedEntry.description && <p>{selectedEntry.description}</p>}
                </div>
            )}

            <button
                onClick={() => onConfirm(selected)}
                disabled={submitting || !selected}
                className="flex items-center justify-center gap-2 w-full px-4 py-2.5 bg-violet-600 hover:bg-violet-700 disabled:opacity-50 disabled:cursor-not-allowed text-white text-sm font-medium rounded-lg transition-colors"
            >
                {submitting ? <Loader className="w-4 h-4 animate-spin" /> : <CheckCircle className="w-4 h-4" />}
                {submitting ? '正在启动…' : '确认，开始知识提取'}
            </button>
        </div>
    );
}

// ─── Step 4: 分析进度 ──────────────────────────────────────────────────────────

function AnalysisProgressPanel({ materialId, apiFetch }: { materialId: string; apiFetch: ReturnType<typeof useAppFetch>['apiFetch'] }) {
    const [source, setSource] = useState<WebenSource | null>(null);
    const cancelledRef = useRef(false);

    const fetchSource = async () => {
        try {
            const { data } = await apiFetch(
                `/api/v1/WebenSourceListRoute?material_id=${encodeURIComponent(materialId)}&limit=1&offset=0`,
                { method: 'GET' }, { silent: true },
            );
            const d = data as { items: WebenSource[]; total: number } | null;
            if (d?.items?.[0]) return d.items[0];
        } catch { /* ignore */ }
        return null;
    };

    useEffect(() => {
        cancelledRef.current = false;
        let timer: ReturnType<typeof setInterval>;

        const poll = async () => {
            if (cancelledRef.current) return;
            const src = await fetchSource();
            if (cancelledRef.current) return;
            if (src) {
                setSource(src);
                setLoading(false);
                if (src.analysis_status === 'completed' || src.analysis_status === 'failed') {
                    clearInterval(timer);
                }
            }
        };

        poll();
        timer = setInterval(poll, 2000);
        return () => { cancelledRef.current = true; clearInterval(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [materialId]);

    if (!source) return (
        <div className="flex items-center justify-center gap-2 py-8 text-sm text-gray-400">
            <Loader className="w-4 h-4 animate-spin" />
            加载进度…
        </div>
    );

    const statusInfo = getAnalysisStatusInfo(source.analysis_status);
    const isActive   = source.analysis_status === 'analyzing' || source.analysis_status === 'pending';
    const barColor   = source.analysis_status === 'completed' ? 'bg-green-500' : source.analysis_status === 'failed' ? 'bg-red-400' : 'bg-blue-500';

    return (
        <div className="space-y-4">
            <div className="flex items-center gap-2 mb-1">
                <Activity className="w-4 h-4 text-blue-500 flex-shrink-0" />
                <span className="text-sm font-medium text-gray-700 flex-1">分析进度</span>
                <span className={`text-[10px] font-medium px-2 py-0.5 rounded-full ${statusInfo.badge}`}>{statusInfo.label}</span>
                <span className="text-xs tabular-nums text-gray-400">{source.progress}%</span>
            </div>
            <ProgressBar value={source.progress} color={barColor} height="h-2" />

            <div className="border border-gray-100 rounded-lg overflow-hidden px-3">
                <WorkflowInfoPanel workflowRunId={source.workflow_run_id} active={isActive} />
            </div>

            {isActive && (
                <p className="text-xs text-gray-400 text-center">分析进行中，可关闭此页面，任务将在后台继续运行。</p>
            )}
        </div>
    );
}

// ─── Page ──────────────────────────────────────────────────────────────────────

type FlowStep = 'checking' | 'subtitle_choice' | 'no_subtitle' | 'model_select' | 'done';

export default function WebenAnalyzePage() {
    const { materialId } = useParams<{ materialId: string }>();
    const { apiFetch } = useAppFetch();
    const navigate = useNavigate();

    const [material, setMaterial]           = useState<MaterialVideo | null>(null);
    const [step, setStep]                   = useState<FlowStep>('checking');
    const [subtitles, setSubtitles]         = useState<SubtitleMeta[]>([]);
    const [whisperConfig, setWhisperConfig] = useState<WhisperConfigInfo | null>(null);
    const [checkError, setCheckError]       = useState<string | null>(null);
    const [submitting, setSubmitting]       = useState(false);

    const isBilibili = material?.source_type === 'bilibili';
    const bvid = material ? (() => { try { return (JSON.parse(material.extra) as BilibiliExtra).bvid ?? material.source_id; } catch { return material.source_id; } })() : '';
    const title = material?.title ?? '';
    const duration = material?.duration;

    async function fetchWhisperConfig(): Promise<WhisperConfigInfo | null> {
        try {
            const { resp, data } = await apiFetch('/api/v1/FasterWhisperConfigInfoRoute', { method: 'GET' }, { silent: true });
            if (resp.ok) return data as WhisperConfigInfo;
        } catch { /* ignore */ }
        return null;
    }

    useEffect(() => {
        if (!materialId) return;
        let cancelled = false;

        async function runCheck() {
            setStep('checking');
            setCheckError(null);

            // 先加载素材信息
            const { resp: mResp, data: mData } = await apiFetch(
                `/api/v1/MaterialGetRoute?id=${encodeURIComponent(materialId)}`,
                { method: 'GET' }, { silent: true },
            );
            if (cancelled) return;
            if (!mResp.ok || !mData) { setCheckError('素材加载失败'); setStep('no_subtitle'); return; }
            const mat = mData as MaterialVideo;
            setMaterial(mat);

            const matIsBilibili = mat.source_type === 'bilibili';
            const matBvid = (() => { try { return (JSON.parse(mat.extra) as BilibiliExtra).bvid ?? mat.source_id; } catch { return mat.source_id; } })();

            if (!matIsBilibili || !matBvid) {
                const cfg = await fetchWhisperConfig();
                if (cancelled) return;
                if (cfg) { setWhisperConfig(cfg); setStep('model_select'); }
                else setStep('no_subtitle');
                return;
            }

            try {
                const { resp, data } = await apiFetch('/api/v1/BilibiliVideoSubtitleRoute', {
                    method: 'POST',
                    body: JSON.stringify({ bvid: matBvid, page_index: 0, is_update: false }),
                }, { silent: true });
                if (cancelled) return;
                if (!resp.ok) throw new Error(`HTTP ${resp.status}`);

                const subs = (data as SubtitleCheckResult | null)?.subtitles ?? [];
                if (subs.length > 0) {
                    setSubtitles(subs);
                    setStep('subtitle_choice');
                } else {
                    const cfg = await fetchWhisperConfig();
                    if (cancelled) return;
                    if (cfg) { setWhisperConfig(cfg); setStep('model_select'); }
                    else setStep('no_subtitle');
                }
            } catch (err) {
                if (cancelled) return;
                setCheckError(err instanceof Error ? err.message : String(err));
                const cfg = await fetchWhisperConfig();
                if (cancelled) return;
                if (cfg) { setWhisperConfig(cfg); setStep('model_select'); }
                else setStep('no_subtitle');
            }
        }

        runCheck();
        return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [materialId]);

    async function handleUsePlatformSubtitle(sub: SubtitleMeta) {
        if (!materialId || !material || submitting) return;
        setSubmitting(true);
        try {
            const url = isBilibili && bvid ? `https://www.bilibili.com/video/${bvid}` : '';
            const { resp } = await apiFetch('/api/v1/WebenSourceAnalyzeRoute', {
                method: 'POST',
                body: JSON.stringify({
                    material_id: materialId,
                    url,
                    title: title || bvid || materialId,
                    source_type: isBilibili ? 'bilibili_video' : 'local_file',
                    ...(bvid ? { bvid } : {}),
                    ...(duration != null ? { duration_sec: duration } : {}),
                    quality_score: 0.8,
                    subtitle_url: sub.subtitle_url,
                    subtitle_lan: sub.lan,
                }),
            });
            if (resp.ok) setStep('done');
        } finally { setSubmitting(false); }
    }

    async function handleUseWhisper() {
        if (whisperConfig) { setStep('model_select'); return; }
        const cfg = await fetchWhisperConfig();
        setWhisperConfig(cfg);
        setStep('model_select');
    }

    async function handleConfirmModel(model: string) {
        if (!materialId || !material || submitting) return;
        setSubmitting(true);
        try {
            const url = isBilibili && bvid ? `https://www.bilibili.com/video/${bvid}` : '';
            const { resp } = await apiFetch('/api/v1/WebenSourceAnalyzeRoute', {
                method: 'POST',
                body: JSON.stringify({
                    material_id: materialId,
                    url,
                    title: title || bvid || materialId,
                    source_type: isBilibili ? 'bilibili_video' : 'local_file',
                    ...(bvid ? { bvid } : {}),
                    ...(duration != null ? { duration_sec: duration } : {}),
                    quality_score: 0.8,
                    whisper_model: model,
                }),
            });
            if (resp.ok) setStep('done');
        } finally { setSubmitting(false); }
    }

    const stepLabels = ['检查平台字幕', '选择字幕来源', '选择 ASR 模型'];
    const stepIndex: Record<FlowStep, number> = {
        checking: 0, subtitle_choice: 1, no_subtitle: 1, model_select: 2, done: 3,
    };
    const currentStepIdx = stepIndex[step];

    return (
        <SidebarLayout>
            <div className="max-w-lg mx-auto p-4 sm:p-6 space-y-5">

                <div className="flex items-center gap-3">
                    <button
                        onClick={() => navigate(-1)}
                        className="p-1.5 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors"
                    >
                        <ArrowLeft className="w-4 h-4" />
                    </button>
                    <div>
                        <h1 className="text-base font-semibold text-gray-900">知识提取配置</h1>
                        {title && <p className="text-xs text-gray-400 truncate max-w-xs mt-0.5">{title}</p>}
                    </div>
                </div>

                {isBilibili && bvid && (
                    <div className="flex items-center px-1">
                        {stepLabels.map((label, i) => (
                            <div key={i} className="flex items-center flex-1 min-w-0">
                                <StepRow n={i + 1} label={label} active={currentStepIdx === i} done={currentStepIdx > i} />
                                {i < stepLabels.length - 1 && <div className="flex-1 h-px bg-gray-200 mx-2" />}
                            </div>
                        ))}
                    </div>
                )}

                {checkError && (
                    <div className="flex items-start gap-2 p-3 bg-red-50 rounded-lg border border-red-200">
                        <AlertCircle className="w-4 h-4 text-red-500 mt-0.5 flex-shrink-0" />
                        <p className="text-xs text-red-600">字幕预检失败（{checkError}），已降级为 Whisper ASR。</p>
                    </div>
                )}

                <div className="bg-white rounded-xl border border-gray-200 p-5">
                    {step === 'checking'        && <Step1Checking />}
                    {step === 'subtitle_choice' && <Step2SubtitleChoice subtitles={subtitles} onUsePlatform={handleUsePlatformSubtitle} onUseWhisper={handleUseWhisper} />}
                    {step === 'no_subtitle'     && <Step2NoSubtitle onContinue={handleUseWhisper} />}
                    {step === 'model_select' && whisperConfig  && <Step3ModelSelect configInfo={whisperConfig} onConfirm={handleConfirmModel} submitting={submitting} />}
                    {step === 'model_select' && !whisperConfig && (
                        <div className="flex flex-col items-center gap-3 py-8">
                            <Loader className="w-6 h-6 animate-spin text-violet-500" />
                            <p className="text-sm text-gray-400">加载模型配置…</p>
                        </div>
                    )}
                    {step === 'done' && materialId && (
                        <AnalysisProgressPanel materialId={materialId} apiFetch={apiFetch} />
                    )}
                </div>

            </div>
        </SidebarLayout>
    );
}
