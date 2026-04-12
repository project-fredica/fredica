import { useState, useEffect, useRef } from "react";
import {
    Subtitles, Mic, ScanText, FileVideo2,
    CheckCircle, ChevronRight, ChevronDown, Clock,
    ExternalLink, Loader, RefreshCw, AlertCircle, X,
} from "lucide-react";
import { Link } from "react-router";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { useAppFetch } from "~/util/app_fetch";
import { WorkflowInfoPanel } from "~/components/ui/WorkflowInfoPanel";
import { startMaterialWorkflow } from "~/util/materialWorkflowApi";
import { reportHttpError, print_error } from "~/util/error_handler";
import {
    ALL_WHISPER_MODELS, WHISPER_MODEL_VRAM_HINT, WHISPER_LANGUAGES,
    pickDefaultModel, filterDisallowedModels,
} from "~/util/asrConfig";
import { callBridgeOrNull } from "~/util/bridge";
import { json_parse } from "~/util/json";

// ─── Types ────────────────────────────────────────────────────────────────────

/** API response item from MaterialSubtitleListRoute */
interface SubtitleApiItem {
    lan: string;
    lan_doc: string;
    source: string;       // "bilibili_platform" etc.
    queried_at: number;   // Unix seconds
    subtitle_url: string;
    type: number;         // 0=human, 1=AI
    model_size?: string;  // ASR model name (e.g. "large-v3")
    partial?: boolean;    // true if transcription is incomplete
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
    const aiLabel = sub.type === 1
        ? (sub.model_size ? `AI(${sub.model_size})` : 'AI')
        : null;

    const inner = (
        <div className={`inline-flex items-center gap-1.5 px-2.5 py-1.5 bg-white border border-gray-200 rounded-lg text-xs ${
            sub.source === "asr" ? "hover:border-violet-300 cursor-pointer" : ""
        }`}>
            {sub.partial
                ? <Loader className="w-3 h-3 text-amber-500 animate-spin flex-shrink-0" />
                : <CheckCircle className="w-3 h-3 text-green-500 flex-shrink-0" />
            }
            <span className="font-medium text-gray-800">{sub.lan_doc || sub.lan}</span>
            {aiLabel && <span className="text-gray-400">{aiLabel}</span>}
            <span className={`px-1 py-0.5 rounded font-medium ${badge.cls}`}>{badge.text}</span>
            <span className="text-gray-400">{dateStr}</span>
            {sub.source === "asr" && <ChevronRight className="w-3 h-3 text-gray-400" />}
        </div>
    );

    if (sub.source === "asr") {
        return <Link to={`../subtitle-asr?model_size=${encodeURIComponent(sub.model_size || 'large-v3')}`} relative="path">{inner}</Link>;
    }
    return inner;
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
    { id: 'asr',      label: '语音识别（Whisper）', desc: '使用本地 Faster-Whisper 模型将音频转换为文字',        icon: <Mic className="w-4 h-4" /> },
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

// ─── ASR scheme detail ────────────────────────────────────────────────────────

// ─── ASR model picker modal ───────────────────────────────────────────────────

function AsrModelPickerModal({
    selectedModel,
    selectedLanguage,
    selectedAllowDownload,
    langGuessState,
    availableModels,
    adminAllowDownload,
    onSelect,
    onClose,
}: {
    selectedModel: string;
    selectedLanguage: string;
    selectedAllowDownload: boolean;
    langGuessState: "idle" | "loading" | { lang: string; label: string } | "failed";
    availableModels: string[];
    adminAllowDownload: boolean;
    onSelect: (model: string, language: string, allowDownload: boolean) => void;
    onClose: () => void;
}) {
    const [model, setModel] = useState(selectedModel);
    const [language, setLanguage] = useState(selectedLanguage);
    const [allowDownload, setAllowDownload] = useState(selectedAllowDownload);
    const [langHighlight, setLangHighlight] = useState(false);
    const overlayRef = useRef<HTMLDivElement>(null);
    const modelListRef = useRef<HTMLDivElement>(null);

    // 模型切换时（含代码触发）自动滚动到选中项
    useEffect(() => {
        if (!model || !modelListRef.current) return;
        const btn = modelListRef.current.querySelector(`[data-model="${model}"]`);
        btn?.scrollIntoView({ block: "nearest", behavior: "smooth" });
    }, [model]);

    // 语言变化时触发边框高亮动画
    const isFirstLangRender = useRef(true);
    useEffect(() => {
        if (isFirstLangRender.current) { isFirstLangRender.current = false; return; }
        setLangHighlight(true);
        const t = setTimeout(() => setLangHighlight(false), 1500);
        return () => clearTimeout(t);
    }, [language]);

    // LLM 猜测完成后自动设置语言，并联动更新模型
    useEffect(() => {
        if (typeof langGuessState !== "object") return;
        const detectedLang = langGuessState.lang;
        setLanguage(detectedLang);
        setModel(pickDefaultModel(detectedLang));
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [langGuessState]);

    return (
        <div
            ref={overlayRef}
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
            onClick={e => { if (e.target === overlayRef.current) onClose(); }}
        >
            <div className="bg-white rounded-xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
                {/* Header */}
                <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
                    <h3 className="text-sm font-semibold text-gray-800">语音识别配置</h3>
                    <div className="flex items-center gap-1">
                        <a
                            href="https://github.com/SYSTRAN/faster-whisper#benchmark"
                            target="_blank"
                            rel="noopener noreferrer"
                            className="p-1 rounded hover:bg-gray-100 transition-colors"
                            title="查看模型显存参考数据"
                        >
                            <ExternalLink className="w-3.5 h-3.5 text-gray-400" />
                        </a>
                        <button onClick={onClose} className="p-1 rounded hover:bg-gray-100 transition-colors">
                            <X className="w-3.5 h-3.5 text-gray-400" />
                        </button>
                    </div>
                </div>

                <div className="px-4 py-3 space-y-3">
                    {/* Model list */}
                    <div>
                        <p className="text-[11px] font-medium text-gray-400 uppercase tracking-wide mb-1.5">模型</p>
                        <div ref={modelListRef} className="max-h-48 overflow-y-auto space-y-1 pr-1 border border-gray-100 rounded-lg p-1">
                            {availableModels.map(m => {
                                const isEnOnly = m.endsWith(".en") || m.startsWith("distil-");
                                const vram = WHISPER_MODEL_VRAM_HINT[m];
                                const isSelected = model === m;
                                return (
                                    <button
                                        key={m}
                                        data-model={m}
                                        onClick={() => setModel(m)}
                                        className={`w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg border text-left transition-colors ${
                                            isSelected
                                                ? "border-violet-400 bg-violet-50"
                                                : "border-gray-200 hover:border-gray-300 hover:bg-gray-50"
                                        }`}
                                    >
                                        <div className={`w-3 h-3 rounded-full border-2 flex-shrink-0 ${
                                            isSelected ? "border-violet-500 bg-violet-500" : "border-gray-300"
                                        }`} />
                                        <span className={`text-xs font-medium flex-1 ${isSelected ? "text-violet-800" : "text-gray-700"}`}>{m}</span>
                                        <div className="flex items-center gap-1">
                                            {isEnOnly && <span className="text-[10px] px-1 py-0.5 bg-blue-50 text-blue-500 rounded">仅英语</span>}
                                            {vram != null && vram > 0 && <span className="text-[10px] text-gray-400">{vram}GB</span>}
                                        </div>
                                    </button>
                                );
                            })}
                        </div>
                    </div>

                    {/* Language */}
                    <div>
                        <p className="text-[11px] font-medium text-gray-400 uppercase tracking-wide mb-1.5">语言</p>
                        <select
                            id="asr-language-select"
                            value={language}
                            onChange={e => setLanguage(e.target.value)}
                            className={`w-full text-xs border rounded-lg px-2.5 py-1.5 bg-white focus:outline-none transition-all duration-300 ${
                                langHighlight
                                    ? "border-violet-400 ring-2 ring-violet-200"
                                    : "border-gray-200 focus:border-violet-400"
                            }`}
                        >
                            {WHISPER_LANGUAGES.map(l => (
                                <option key={l.value} value={l.value}>{l.label}</option>
                            ))}
                        </select>
                        {typeof langGuessState === "object" && (
                            <p className="text-[11px] text-gray-400 mt-1">
                                LLM 猜测语言为「{langGuessState.label}」，如与实际不符可在此修改。
                            </p>
                        )}
                    </div>

                    {/* Allow download — 管理员禁用时隐藏 */}
                    {adminAllowDownload ? (
                        <label className="flex items-start gap-2 cursor-pointer select-none">
                            <input
                                type="checkbox"
                                checked={allowDownload}
                                onChange={e => setAllowDownload(e.target.checked)}
                                className="mt-0.5 accent-violet-600"
                            />
                            <span className="text-[11px] text-gray-600 leading-relaxed">
                                允许在线下载模型
                                <span className="block text-gray-400">若本地无缓存，将从 HuggingFace 下载所选模型。未勾选时仅使用本地已有模型。</span>
                            </span>
                        </label>
                    ) : (
                        <div className="flex items-start gap-2 p-2 bg-amber-50 rounded-lg border border-amber-200">
                            <AlertCircle className="w-3.5 h-3.5 text-amber-500 flex-shrink-0 mt-0.5" />
                            <span className="text-[11px] text-amber-700 leading-relaxed">
                                管理员已禁用模型在线下载，仅可使用本地已有模型。
                            </span>
                        </div>
                    )}
                </div>

                {/* Footer */}
                <div className="px-4 py-3 border-t border-gray-100 flex justify-end gap-2">
                    <button onClick={onClose} className="px-3 py-1.5 text-xs text-gray-500 hover:text-gray-700 transition-colors">
                        取消
                    </button>
                    <button
                        onClick={() => { onSelect(model, language, adminAllowDownload ? allowDownload : false); onClose(); }}
                        disabled={!model}
                        title={!model ? "请先选择模型" : undefined}
                        className="flex items-center gap-1.5 px-3 py-1.5 bg-violet-600 hover:bg-violet-700 disabled:opacity-50 disabled:cursor-not-allowed text-white text-xs font-medium rounded-lg transition-colors"
                    >
                        <Mic className="w-3 h-3" />
                        开始识别
                    </button>
                </div>
            </div>
        </div>
    );
}

// ─── ASR scheme detail ────────────────────────────────────────────────────────

/** ASR 配置（从 bridge 或 HTTP API 读取） */
interface AsrAdminConfig {
    allow_download: boolean;
    disallowed_models: string;
}

function AsrSchemeDetail({ materialId }: { materialId: string }) {
    const { apiFetch } = useAppFetch();
    const [model, setModel] = useState("");
    const [language, setLanguage] = useState("auto");
    const [allowDownload, setAllowDownload] = useState(true);
    const [showModal, setShowModal] = useState(false);
    const [starting, setStarting] = useState(false);
    const [workflowRunId, setWorkflowRunId] = useState<string | null>(null);
    const [startError, setStartError] = useState<string | null>(null);
    const [recovering, setRecovering] = useState(true);
    const [langGuessState, setLangGuessState] = useState<
        "idle" | "loading" | { lang: string; label: string } | "failed"
    >("idle");
    const [asrAdminConfig, setAsrAdminConfig] = useState<AsrAdminConfig>({
        allow_download: true,
        disallowed_models: "",
    });

    // On mount: fetch ASR admin config (bridge → HTTP fallback)
    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                // 优先 bridge（桌面端）
                const raw = await callBridgeOrNull("get_asr_config");
                if (raw) {
                    const cfg = json_parse<AsrAdminConfig & { error?: string }>(raw);
                    if (!cancelled && cfg && !cfg.error) {
                        setAsrAdminConfig({ allow_download: cfg.allow_download, disallowed_models: cfg.disallowed_models });
                        return;
                    }
                }
                // 回退 HTTP API（浏览器开发模式）
                const { data } = await apiFetch<AsrAdminConfig>(
                    "/api/v1/AsrConfigGetRoute",
                    { method: "GET" },
                    { silent: true },
                );
                if (!cancelled && data) {
                    setAsrAdminConfig({ allow_download: data.allow_download, disallowed_models: data.disallowed_models });
                }
            } catch (e) {
                print_error({ reason: "读取 ASR 配置失败", err: e });
            }
        })();
        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // 根据黑名单过滤可用模型
    const availableModels = filterDisallowedModels(ALL_WHISPER_MODELS, asrAdminConfig.disallowed_models);

    // On mount: LLM language guess
    useEffect(() => {
        let cancelled = false;
        setLangGuessState("loading");
        apiFetch<{ language: string | null }>(
            `/api/v1/MaterialLanguageGuessRoute?material_id=${encodeURIComponent(materialId)}`,
            { method: "GET" },
            { silent: true },
        ).then(({ data }) => {
            if (cancelled) return;
            if (!data?.language) { setLangGuessState("failed"); return; }
            const detectedLang = data.language;
            const label = WHISPER_LANGUAGES.find(l => l.value === detectedLang)?.label ?? detectedLang;
            setLangGuessState({ lang: detectedLang, label });
            // 不自动覆盖语言下拉框，猜测结果仅在模态框内作为提示展示
        }).catch(e => { if (!cancelled) { print_error({ reason: "语言猜测失败", err: e }); setLangGuessState("failed"); } });
        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [materialId]);

    // On mount: recover active ASR workflow run if any
    useEffect(() => {
        let cancelled = false;
        apiFetch<{ workflow_runs: Array<{ workflow_run: { id: string; status: string; template: string }; tasks: Array<{ status: string }> }> }>(
            `/api/v1/MaterialWorkflowStatusRoute?material_id=${encodeURIComponent(materialId)}`,
            { method: "GET" },
            { silent: true },
        ).then(({ data }) => {
            if (cancelled || !data) return;
            const active = data.workflow_runs.find(
                e => e.workflow_run.template === "whisper_transcribe" &&
                     !["completed", "failed", "cancelled"].includes(e.workflow_run.status)
            );
            if (active) setWorkflowRunId(active.workflow_run.id);
        }).catch(e => { print_error({ reason: "查询 ASR 工作流状态失败", err: e }); }).finally(() => { if (!cancelled) setRecovering(false); });
        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [materialId]);

    const openModal = () => {
        setShowModal(true);
    };

    // 每次渲染时计算模态框的初始模型（避免 setModel 异步更新导致模态框拿到旧值）
    const modalInitialModel = model || pickDefaultModel(
        typeof langGuessState === "object" ? langGuessState.lang : language,
    );

    const handleStart = async (chosenModel: string, chosenLanguage: string, chosenAllowDownload: boolean) => {
        setModel(chosenModel);
        setLanguage(chosenLanguage);
        setAllowDownload(chosenAllowDownload);
        setStarting(true);
        setStartError(null);
        try {
            const { resp, data } = await startMaterialWorkflow(apiFetch, materialId, "whisper_transcribe", {
                model: chosenModel,
                language: chosenLanguage,
                allow_download: chosenAllowDownload,
            }).then(r => r as unknown as { resp: Response; data: unknown });
            if (!resp.ok) { reportHttpError("启动 ASR 工作流失败", resp); return; }
            const result = data as { workflow_run_id?: string; error?: string } | null;
            if (result?.error === "TASK_ALREADY_ACTIVE") {
                setStartError("已有 ASR 任务正在运行，请等待完成后再试。");
                return;
            }
            if (result?.workflow_run_id) setWorkflowRunId(result.workflow_run_id);
        } catch {
            setStartError("启动失败，请检查服务器连接。");
        } finally {
            setStarting(false);
        }
    };

    if (recovering) {
        return (
            <div className="flex items-center gap-2 py-3 text-gray-400">
                <Loader className="w-4 h-4 animate-spin" />
                <span className="text-sm">检查任务状态…</span>
            </div>
        );
    }

    return (
        <div className="space-y-3 pt-1">
            <div className="p-3 bg-violet-50 rounded-lg border border-violet-200">
                <p className="text-sm text-violet-700">
                    使用本地 Faster-Whisper 模型对视频音频进行语音识别，生成 SRT 字幕文件。
                </p>
            </div>
            {langGuessState === "loading" && (
                <div className="flex items-center gap-1.5 text-xs text-gray-400">
                    <Loader className="w-3 h-3 animate-spin" />
                    <span>LLM 正在猜测语言…</span>
                </div>
            )}
            {startError && (
                <div className="flex items-start gap-2 p-3 bg-red-50 rounded-lg border border-red-200">
                    <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                    <p className="text-sm text-red-700">{startError}</p>
                </div>
            )}
            {workflowRunId ? (
                <WorkflowInfoPanel workflowRunId={workflowRunId} active defaultExpanded />
            ) : (
                <button
                    onClick={openModal}
                    disabled={starting}
                    className="flex items-center justify-center gap-2 w-full px-4 py-2.5 bg-violet-600 hover:bg-violet-700 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors"
                >
                    {starting ? <Loader className="w-4 h-4 animate-spin" /> : <Mic className="w-4 h-4" />}
                    {starting ? "启动中…" : "配置并开始语音识别"}
                </button>
            )}
            <p className="text-xs text-gray-400">识别完成后可在「已有字幕」中查看结果</p>
            {showModal && (
                <AsrModelPickerModal
                    selectedModel={modalInitialModel}
                    selectedLanguage={language}
                    selectedAllowDownload={allowDownload}
                    langGuessState={langGuessState}
                    availableModels={availableModels}
                    adminAllowDownload={asrAdminConfig.allow_download}
                    onSelect={handleStart}
                    onClose={() => setShowModal(false)}
                />
            )}
        </div>
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
        apiFetch<SubtitleApiItem[]>(
            `/api/v1/MaterialSubtitleListRoute?material_id=${encodeURIComponent(material.id)}`,
            { method: 'GET' },
            { silent: true },
        )
            .then(({ data }) => { if (!cancelled && Array.isArray(data)) setSubtitles(data); })
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
                        {subtitles.map((s, i) => (
                            <SubtitleChip key={`${s.lan}-${s.source}-${s.model_size ?? ''}-${i}`} sub={s} />
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
                        {/* Scheme detail */}
                        <div className="bg-white rounded-xl border border-gray-200 p-4">
                            {selectedScheme === 'platform' && <PlatformSchemeDetail isBilibili={isBilibili} />}
                            {selectedScheme === 'asr' && <AsrSchemeDetail materialId={material.id} />}
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
