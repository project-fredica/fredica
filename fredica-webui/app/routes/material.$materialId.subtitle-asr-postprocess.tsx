import { useState, useEffect, useCallback, useMemo } from "react";
import { ArrowLeft, Download, Save, Loader, AlertTriangle, BookMarked, BookmarkPlus } from "lucide-react";
import { Link, useParams, useSearchParams } from "react-router";
import { toast } from "react-toastify";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { useAppFetch } from "~/util/app_fetch";
import { useAppConfig } from "~/context/appConfig";
import { useFloatingPlayerCtx } from "~/context/floatingPlayer";
import { CommonSubtitlePanel, type CommonSubtitleItem } from "~/components/subtitle/CommonSubtitlePanel";
import { PromptBuilder } from "~/components/prompt-builder/PromptBuilder";
import { PromptStreamPane, type SegmentProgress } from "~/components/prompt-builder/PromptStreamPane";
import { PromptPaneShell } from "~/components/prompt-builder/PromptPaneShell";
import { PromptTemplatePickerModal } from "~/components/prompt-builder/PromptTemplatePickerModal";
import { SaveTemplateModal } from "~/components/prompt-builder/SaveTemplateModal";
import { convertToSrt, downloadSrt } from "~/util/subtitleExport";
import type { SrtSegment } from "~/util/subtitleExport";
import { parseSrt } from "~/util/srtParser";
import { streamPromptScriptGenerate, previewPromptScript } from "~/util/materialWebenApi";
import { print_error } from "~/util/error_handler";
import type { BuildPromptResult } from "~/util/prompt-builder/types";
import type { PromptTemplateListItem } from "~/util/prompt-builder/promptTemplateApi";

// ─── Types ───────────────────────────────────────────────────────────────────

interface AsrSubtitleResponse {
    segments: Array<{ from: number; to: number; content: string }>;
    language?: string | null;
    model_size?: string | null;
    segment_count: number;
    total_chunks: number;
    done_chunks: number;
    partial: boolean;
}

type Stage = "editing" | "previewed" | "generating" | "parsed" | "parse_error";

// ─── Post-process result pane ───────────────────────────────────────────────

function PostProcessRenderPane({
    segments,
    parseError,
    originalCount,
    materialId,
    onSeek,
    onExport,
    onSave,
    saving,
}: {
    segments: SrtSegment[] | null;
    parseError: string | null;
    originalCount: number;
    materialId: string;
    onSeek: (seconds: number) => void;
    onExport: () => void;
    onSave: () => void;
    saving: boolean;
}) {
    if (parseError) {
        return (
            <PromptPaneShell title="解析结果">
                <div className="p-4 space-y-2">
                    <div className="flex items-start gap-2 p-3 bg-red-50 rounded-lg border border-red-200">
                        <AlertTriangle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                        <div className="text-sm text-red-700">
                            <p className="font-medium">SRT 解析失败</p>
                            <p className="mt-1">{parseError}</p>
                            <p className="mt-2 text-xs text-red-500">请切换到"LLM 输出"标签查看原始输出</p>
                        </div>
                    </div>
                </div>
            </PromptPaneShell>
        );
    }

    if (!segments || segments.length === 0) {
        return (
            <PromptPaneShell title="解析结果">
                <div className="flex-1 flex items-center justify-center p-8 text-sm text-gray-400">
                    生成完成后将在此显示解析结果
                </div>
            </PromptPaneShell>
        );
    }

    const countMismatch = segments.length !== originalCount;

    return (
        <PromptPaneShell
            title={`解析结果 · ${segments.length} 段`}
            actions={
                <div className="flex items-center gap-2">
                    <button
                        onClick={onSave}
                        disabled={saving}
                        className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-emerald-600 bg-emerald-50 hover:bg-emerald-100 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {saving ? <Loader className="w-3.5 h-3.5 animate-spin" /> : <Save className="w-3.5 h-3.5" />}
                        {saving ? "保存中…" : "保存到已有字幕"}
                    </button>
                    <button
                        onClick={onExport}
                        className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-violet-600 bg-violet-50 hover:bg-violet-100 rounded-lg transition-colors"
                    >
                        <Download className="w-3.5 h-3.5" />
                        导出处理后 SRT
                    </button>
                </div>
            }
        >
            {countMismatch && (
                <div className="flex items-start gap-2 px-4 py-2 bg-amber-50 border-b border-amber-200 flex-shrink-0">
                    <AlertTriangle className="w-3.5 h-3.5 text-amber-500 flex-shrink-0 mt-0.5" />
                    <p className="text-xs text-amber-700">
                        段数变化：原始 {originalCount} 段 → 处理后 {segments.length} 段
                    </p>
                </div>
            )}
            <div className="flex-1 min-h-0 p-4 flex flex-col">
                <CommonSubtitlePanel items={segments} materialId={materialId} onSeek={onSeek} />
            </div>
        </PromptPaneShell>
    );
}

// ─── Page ────────────────────────────────────────────────────────────────────

export default function SubtitleAsrPostprocessPage() {
    const { materialId: routeMaterialId = "" } = useParams<{ materialId: string }>();
    const { material } = useWorkspaceContext();
    const { apiFetch } = useAppFetch();
    const { appConfig } = useAppConfig();
    const { openFloatingPlayer } = useFloatingPlayerCtx();
    const [searchParams] = useSearchParams();
    const modelSize = searchParams.get("model_size") || "large-v3";

    // ── ASR data ──
    const [data, setData] = useState<AsrSubtitleResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // ── Stage state machine ──
    const [stage, setStage] = useState<Stage>("editing");

    // ── Prompt editor state ──
    const [template, setTemplate] = useState("");
    const [previewResult, setPreviewResult] = useState<BuildPromptResult | null>(null);
    const [previewLoading, setPreviewLoading] = useState(false);
    const [resolvedModelId, setResolvedModelId] = useState<string | null>(null);
    const [disableCache, setDisableCache] = useState(false);

    // ── Stream / parse state ──
    const [streamText, setStreamText] = useState("");
    const [streamError, setStreamError] = useState<string | null>(null);
    const [segmentProgress, setSegmentProgress] = useState<SegmentProgress | null>(null);
    const [processedSegments, setProcessedSegments] = useState<SrtSegment[] | null>(null);
    const [parseError, setParseError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);

    // ── Template management ──
    const [pickerOpen, setPickerOpen] = useState(false);
    const [saveModalOpen, setSaveModalOpen] = useState(false);
    const [loadedTemplate, setLoadedTemplate] = useState<PromptTemplateListItem | null>(null);
    const [loadedTemplateCode, setLoadedTemplateCode] = useState<string | null>(null);

    const hasUsableModel = Boolean(resolvedModelId?.trim());

    const hasUnsavedChanges =
        loadedTemplate !== null &&
        loadedTemplateCode !== null &&
        template !== loadedTemplateCode;

    // 头部代码：由编辑器自动注入，不属于模板内容，不可编辑。
    // 提交时前置于模板代码之前，后端从 GraalJS bindings 读取变量。
    const scriptHeader = useMemo(() => [
        "// 执行上下文 - 由编辑器自动注入",
        `var __materialId = ${JSON.stringify(routeMaterialId)};`,
        `var __subtitleId = ${JSON.stringify(`asr.${modelSize}`)};`,
    ].join("\n"), [routeMaterialId, modelSize]);

    // ── Fetch ASR data ──
    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        setError(null);
        apiFetch<AsrSubtitleResponse>(
            `/api/v1/MaterialAsrSubtitleRoute?material_id=${encodeURIComponent(material.id)}&model_size=${encodeURIComponent(modelSize)}`,
            { method: "GET" },
            { silent: true },
        )
            .then(({ data: d }) => {
                if (cancelled) return;
                if (d) setData(d);
            })
            .catch(e => {
                if (!cancelled) {
                    print_error({ reason: "加载 ASR 字幕失败", err: e });
                    setError("加载失败，请检查服务器连接。");
                }
            })
            .finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [material.id, modelSize]);

    // ── Template management callbacks ──

    const handleLoadTemplate = useCallback((scriptCode: string, listItem: PromptTemplateListItem) => {
        setTemplate(scriptCode);
        setLoadedTemplate(listItem);
        setLoadedTemplateCode(scriptCode);
        setStage("editing");
        setPreviewResult(null);
        setStreamText("");
        setStreamError(null);
        setProcessedSegments(null);
        setParseError(null);
    }, []);

    // ── Callbacks ──

    const handleSeek = (seconds: number) => {
        openFloatingPlayer(material.id, seconds);
    };

    const handleExportProcessed = useCallback(() => {
        if (!processedSegments || processedSegments.length === 0) return;
        const srt = convertToSrt(processedSegments);
        downloadSrt(srt, `subtitle___${material.id}___asr-postprocess.${modelSize}.srt`);
    }, [processedSegments, data, material]);

    const handleSaveToSubtitles = useCallback(async () => {
        if (!processedSegments || processedSegments.length === 0) return;
        setSaving(true);
        try {
            // 前端 SRT 格式校验
            const srt = convertToSrt(processedSegments);
            const validated = parseSrt(srt);
            if (validated.length === 0) {
                toast.error("SRT 格式校验失败：无有效字幕段");
                return;
            }
            const { data: result } = await apiFetch<{ filename: string; saved_at: number; error?: string }>(
                "/api/v1/PostprocessSubtitleSaveRoute",
                {
                    method: "POST",
                    body: JSON.stringify({
                        material_id: material.id,
                        model_id: resolvedModelId || "unknown",
                        srt_content: srt,
                    }),
                },
            );
            if (result?.error) {
                toast.error(`保存失败：${result.error}`);
            } else if (result?.filename) {
                toast.success(`已保存到已有字幕 (${result.filename})`);
            } else {
                toast.error("保存失败：未知错误");
            }
        } catch (e) {
            print_error({ reason: "保存后处理字幕失败", err: e });
            toast.error("保存失败");
        } finally {
            setSaving(false);
        }
    }, [processedSegments, apiFetch, material.id, resolvedModelId]);

    const handlePreview = useCallback(async () => {
        if (!routeMaterialId.trim()) {
            const msg = "素材 ID 无效，无法执行脚本";
            print_error({ reason: msg });
            setPreviewResult({ text: msg, charCount: 0, blocked: true, warnings: [] });
            return;
        }
        setPreviewLoading(true);
        try {
            const { promptText, promptTexts, error: scriptError } = await previewPromptScript({
                scriptCode: `${scriptHeader}\n\n${template}`,
                connection: {
                    domain: appConfig.webserver_domain,
                    port: appConfig.webserver_port,
                    schema: appConfig.webserver_schema,
                    appAuthToken: appConfig.webserver_auth_token,
                    sessionToken: appConfig.session_token,
                },
            });
            if (scriptError) {
                print_error({ reason: `脚本执行失败: ${scriptError}` });
                setPreviewResult({ text: `脚本执行失败：${scriptError}`, charCount: 0, blocked: true, warnings: [] });
            } else if (promptTexts && promptTexts.length > 0) {
                const totalChars = promptTexts.reduce((s, t) => s + t.length, 0);
                setPreviewResult({ text: "", texts: promptTexts, charCount: totalChars, blocked: false, warnings: [] });
            } else {
                const text = promptText ?? "";
                setPreviewResult({ text, charCount: text.length, blocked: false, warnings: [] });
            }
            setStage("previewed");
        } catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            print_error({ reason: "构建 Prompt 预览失败", err: e });
            setPreviewResult({ text: `预览失败：${message}`, charCount: 0, blocked: true, warnings: [] });
        } finally {
            setPreviewLoading(false);
        }
    }, [routeMaterialId, scriptHeader, template, appConfig]);

    const handleGenerate = useCallback(async (signal: AbortSignal) => {
        if (!routeMaterialId.trim()) {
            const msg = "素材 ID 无效，无法执行脚本";
            print_error({ reason: msg });
            setStreamError(msg);
            return;
        }
        if (!resolvedModelId?.trim()) {
            const msg = "当前没有可用模型，请先在编辑器设置中处理模型配置";
            print_error({ reason: msg });
            setStreamError(msg);
            return;
        }
        setPreviewLoading(true);
        try {
            setStage("generating");
            setStreamText("");
            setStreamError(null);
            setSegmentProgress(null);
            setProcessedSegments(null);
            setParseError(null);

            // 一步调用：后端执行脚本 → 逐段调用 LLM → SSE 流式返回
            const { fullText, error: genError } = await streamPromptScriptGenerate({
                scriptCode: `${scriptHeader}\n\n${template}`,
                appModelId: resolvedModelId,
                connection: {
                    domain: appConfig.webserver_domain,
                    port: appConfig.webserver_port,
                    schema: appConfig.webserver_schema,
                    appAuthToken: appConfig.webserver_auth_token,
                    sessionToken: appConfig.session_token,
                },
                onSegmentStart: (index, total) => {
                    setSegmentProgress({ index, total });
                    if (index > 0) setStreamText(prev => prev.trimEnd() + "\n\n");
                },
                onChunk: chunk => setStreamText(prev => prev + chunk),
                signal,
                disableCache,
            });

            if (signal.aborted) return;

            if (genError) {
                setStreamError(genError);
                setStage("parse_error");
                return;
            }

            // 解析 LLM 输出为 SRT 字幕
            try {
                const segments = parseSrt(fullText);
                if (segments.length === 0) {
                    setParseError("LLM 输出中未找到有效的 SRT 字幕段");
                    setStage("parse_error");
                } else {
                    setProcessedSegments(segments);
                    setStage("parsed");
                }
            } catch (e) {
                const message = e instanceof Error ? e.message : String(e);
                setParseError(`SRT 解析失败：${message}`);
                setStage("parse_error");
            }
        } catch (e) {
            if (signal.aborted) return;
            const message = e instanceof Error ? e.message : String(e);
            setStreamError(message);
            print_error({ reason: "字幕后处理生成失败", err: e });
            setStage("parse_error");
        } finally {
            setPreviewLoading(false);
        }
    }, [resolvedModelId, routeMaterialId, scriptHeader, template, appConfig, disableCache]);

    const items: CommonSubtitleItem[] = data?.segments ?? [];

    // ── Editor header extra: template management only (no presets) ──
    const editorHeaderExtra = (
        <>
            <button
                type="button"
                onClick={() => setPickerOpen(true)}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-lg border border-gray-200 bg-white text-gray-700 hover:bg-gray-50"
            >
                <BookMarked className="w-4 h-4" />
                模板库
            </button>
            <button
                type="button"
                onClick={() => setSaveModalOpen(true)}
                className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-lg border transition-colors ${
                    hasUnsavedChanges
                        ? "border-amber-300 bg-amber-50 text-amber-800 hover:bg-amber-100"
                        : "border-gray-200 bg-white text-gray-700 hover:bg-gray-50"
                }`}
            >
                <BookmarkPlus className="w-4 h-4" />
                另存模板
                {hasUnsavedChanges ? <span className="w-1.5 h-1.5 rounded-full bg-amber-500 flex-shrink-0" /> : null}
            </button>
        </>
    );

    return (
        <div className="max-w-4xl mx-auto p-4 sm:p-6 flex flex-col gap-4 h-full">

            {/* Breadcrumb */}
            <div className="flex items-center gap-2">
                <Link
                    to="../subtitle"
                    relative="path"
                    className="flex items-center gap-1.5 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                >
                    <ArrowLeft className="w-3.5 h-3.5" />
                    字幕提取
                </Link>
                <span className="text-xs text-gray-300">/</span>
                <Link
                    to={`../subtitle-preview/asr.${modelSize}`}
                    relative="path"
                    className="text-xs text-gray-400 hover:text-gray-600 transition-colors"
                >
                    ASR 识别字幕
                </Link>
                <span className="text-xs text-gray-300">/</span>
                <span className="text-xs text-gray-500 font-medium">后处理</span>
            </div>

            {/* Loading */}
            {loading && (
                <div className="flex-1 flex items-center justify-center gap-2 text-gray-400">
                    <Loader className="w-4 h-4 animate-spin" />
                    <span className="text-sm">加载 ASR 字幕…</span>
                </div>
            )}

            {/* Error */}
            {error && !loading && (
                <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center py-16">
                    <p className="text-sm font-medium text-red-600">{error}</p>
                    <Link
                        to={`../subtitle-preview/asr.${modelSize}`}
                        relative="path"
                        className="mt-2 text-xs text-violet-600 hover:text-violet-800 transition-colors"
                    >
                        ← 返回 ASR 字幕
                    </Link>
                </div>
            )}

            {/* Empty */}
            {!loading && !error && items.length === 0 && (
                <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center py-16">
                    <p className="text-sm font-medium text-gray-600">暂无 ASR 转录结果</p>
                    <p className="text-xs text-gray-400">请先在字幕提取页面启动语音识别</p>
                    <Link
                        to="../subtitle"
                        relative="path"
                        className="mt-2 text-xs text-violet-600 hover:text-violet-800 transition-colors"
                    >
                        ← 返回字幕提取
                    </Link>
                </div>
            )}

            {/* Content */}
            {!loading && !error && data && items.length > 0 && (
                <>
                    {/* Original subtitle summary */}
                    <div className="flex items-center gap-3 text-xs text-gray-500">
                        {data.model_size && (
                            <span className="px-1.5 py-0.5 bg-violet-50 text-violet-600 rounded font-medium">
                                {data.model_size}
                            </span>
                        )}
                        {data.language && (
                            <span>语言: {data.language}</span>
                        )}
                        <span>{data.segment_count} 段</span>
                        <span className="text-gray-300">|</span>
                        <span>阶段: {stage}</span>
                    </div>

                    {/* Post-process workbench */}
                    <div className="flex-1 min-h-0">
                        <PromptBuilder
                            value={template}
                            onChange={next => {
                                setTemplate(next);
                                setStage("editing");
                            }}
                            previewResult={previewResult}
                            previewLoading={previewLoading}
                            onPreview={handlePreview}
                            onGenerate={handleGenerate}
                            generateDisabled={!hasUsableModel || !data}
                            renderDisabled={!processedSegments && !parseError}
                            currentTemplateName={loadedTemplate?.name}
                            settingsStorageKey={`subtitle-asr-postprocess:${routeMaterialId}`}
                            onResolvedModelChange={setResolvedModelId}
                            defaultTemplateId="sys_subtitle_postprocess_v1"
                            apiFetch={apiFetch}
                            onDefaultTemplateLoaded={handleLoadTemplate}
                            readonlyHeader={scriptHeader}
                            disableCache={disableCache}
                            onDisableCacheChange={setDisableCache}
                            editorHeaderExtra={editorHeaderExtra}
                            streamPane={
                                <PromptStreamPane
                                    text={streamText}
                                    error={streamError}
                                    segmentProgress={segmentProgress}
                                />
                            }
                            renderPane={
                                <PostProcessRenderPane
                                    segments={processedSegments}
                                    parseError={parseError}
                                    originalCount={data.segment_count}
                                    materialId={material.id}
                                    onSeek={handleSeek}
                                    onExport={handleExportProcessed}
                                    onSave={handleSaveToSubtitles}
                                    saving={saving}
                                />
                            }
                        />
                    </div>
                </>
            )}

            {/* Template picker modal */}
            {pickerOpen && (
                <PromptTemplatePickerModal
                    apiFetch={apiFetch}
                    onSelect={handleLoadTemplate}
                    onClose={() => setPickerOpen(false)}
                    hasUnsavedChanges={hasUnsavedChanges}
                    currentScriptCode={template}
                    onTemplateDeleted={deletedId => {
                        if (loadedTemplate?.id === deletedId) {
                            setLoadedTemplate(null);
                            setLoadedTemplateCode(null);
                        }
                    }}
                />
            )}

            {/* Save template modal */}
            {saveModalOpen && (
                <SaveTemplateModal
                    scriptCode={template}
                    loadedFromTemplate={loadedTemplate}
                    apiFetch={apiFetch}
                    onSaved={savedId => {
                        toast.success("模板已保存");
                        setLoadedTemplate(prev => prev ? { ...prev, id: savedId } : null);
                        setLoadedTemplateCode(template);
                    }}
                    onClose={() => setSaveModalOpen(false)}
                />
            )}
        </div>
    );
}
