import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import { BookMarked, BookmarkPlus, BrainCircuit, CheckCircle2, ExternalLink, Loader2, RotateCcw, Save, Sparkles, Trash2, AlertTriangle, X } from "lucide-react";
import { toast } from "react-toastify";
import { PromptBuilder } from "~/components/prompt-builder/PromptBuilder";
import { PromptPaneShell } from "~/components/prompt-builder/PromptPaneShell";
import { PromptStreamPane } from "~/components/prompt-builder/PromptStreamPane";
import { PromptTemplatePickerModal } from "~/components/prompt-builder/PromptTemplatePickerModal";
import { SaveTemplateModal } from "~/components/prompt-builder/SaveTemplateModal";
import { ConceptSaveEditor, computeConceptDiff } from "~/components/weben/ConceptSaveEditor";
import type { ConceptDiff } from "~/components/weben/ConceptSaveEditor";
import { useAppConfig } from "~/context/appConfig";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { buildAuthHeaders, DEFAULT_SERVER_PORT, useAppFetch } from "~/util/app_fetch";
import { print_error, reportHttpError } from "~/util/error_handler";
import {
    getSafeApiFetch,
    getSafeAppConfig,
    normalizeWebenResult,
    getSafeWorkspaceMaterial,
    validateWebenResult,
    type WebenValidationIssue,
} from "~/util/materialWebenGuards";
import type { BuildPromptResult } from "~/util/prompt-builder/types";
import type { PromptTemplateListItem } from "~/util/prompt-builder/promptTemplateApi";
import { json_parse } from "~/util/json";
import { streamLlmRouterText } from "~/util/streamLlmRouterText";
import {
    fetchConceptsByMaterial,
    fetchMaterialSubtitles,
    previewPromptScript,
    saveExtractionRun,
    type MaterialSubtitleItem,
    type MaterialWebenLlmResult,
} from "~/util/materialWebenApi";
import type { WebenConcept } from "~/util/weben";

type Stage = "editing" | "previewed" | "generating" | "parsed" | "parse_error";

// 剥除部分 LLM 会在输出外包裹的 ```json ... ``` 代码块，
// 再交给 normalizeWebenResult 对缺失/格式错误的字段补默认值。
// JSON 解析失败时抛出异常，由调用方捕获并展示可读的报错信息。
function parseWebenResult(raw: string): MaterialWebenLlmResult {
    const trimmed = raw.trim();
    const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
    const jsonText = fenced?.[1] ?? trimmed;
    return normalizeWebenResult(json_parse<any>(jsonText));
}

function filterConceptsForMaterial(existing: WebenConcept[], materialId: string): WebenConcept[] {
    const normalizedMaterialId = materialId.trim();
    if (!normalizedMaterialId) return [];
    return existing.filter(concept => concept.material_id === normalizedMaterialId);
}

/**
 * 计算打开"审阅概念变更" modal 时所用的 diff 基线。
 *
 * 优先级：
 *   1. savedConceptBaseline — 本次会话保存成功后在本地写入的快照。
 *      使用本地快照可避免重新拉取 API 时拿到刚写入的数据，
 *      导致"下次点保存全部变成新增"的伪变更问题。
 *   2. 过滤后的 fetchedConcepts — 从 API 拉取并按 materialId 过滤。
 *      空字符串 material_id 的历史遗留行会被排除，避免跨素材概念污染。
 *
 * 作为具名导出，方便在测试中单独验证。
 */
export function resolveConceptDiffBaseline(params: {
    fetchedConcepts: WebenConcept[];
    savedConceptBaseline: WebenConcept[] | null;
    materialId: string;
}): WebenConcept[] {
    if (params.savedConceptBaseline) {
        console.debug("[SummaryWebenPage] resolveConceptDiffBaseline: use saved baseline", {
            materialId: params.materialId,
            savedBaselineCount: params.savedConceptBaseline.length,
        });
        return params.savedConceptBaseline;
    }
    const filtered = filterConceptsForMaterial(params.fetchedConcepts, params.materialId);
    const emptyMaterialCount = params.fetchedConcepts.filter(c => !c.material_id?.trim()).length;
    const otherMaterialCounts = params.fetchedConcepts.reduce<Record<string, number>>((acc, concept) => {
        const materialId = concept.material_id?.trim();
        if (!materialId || materialId === params.materialId) return acc;
        acc[materialId] = (acc[materialId] ?? 0) + 1;
        return acc;
    }, {});
    console.debug("[SummaryWebenPage] resolveConceptDiffBaseline: use fetched concepts", {
        materialId: params.materialId,
        fetchedCount: params.fetchedConcepts.length,
        filteredCount: filtered.length,
        emptyMaterialCount,
        otherMaterialCounts,
        sampleFetched: params.fetchedConcepts.slice(0, 10).map(c => ({
            name: c.canonical_name,
            material_id: c.material_id,
        })),
    });
    return filtered;
}

function ValidationSummary({
    blockingErrors,
    warnings,
}: {
    blockingErrors: WebenValidationIssue[];
    warnings: WebenValidationIssue[];
}) {
    if (blockingErrors.length === 0 && warnings.length === 0) return null;

    return (
        <div className="space-y-3">
            {blockingErrors.length > 0 ? (
                <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-3">
                    <div className="flex items-center gap-2 text-sm font-medium text-red-800">
                        <AlertTriangle className="w-4 h-4" />
                        保存前需要先处理以下问题
                    </div>
                    <ul className="mt-2 space-y-1 text-sm text-red-700 list-disc pl-5">
                        {blockingErrors.map((issue, index) => (
                            <li key={`error-${index}`}>{issue.message}</li>
                        ))}
                    </ul>
                </div>
            ) : null}

            {warnings.length > 0 ? (
                <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-3">
                    <div className="text-sm font-medium text-amber-800">注意事项</div>
                    <ul className="mt-2 space-y-1 text-sm text-amber-700 list-disc pl-5">
                        {warnings.map((issue, index) => (
                            <li key={`warning-${index}`}>{issue.message}</li>
                        ))}
                    </ul>
                </div>
            ) : null}
        </div>
    );
}

function DeleteRowButton({ onClick, label }: { onClick: () => void; label: string }) {
    return (
        <button
            type="button"
            onClick={onClick}
            className="inline-flex items-center gap-1 rounded-md border border-gray-200 px-2 py-1 text-xs text-gray-600 hover:bg-gray-50"
            aria-label={label}
        >
            <Trash2 className="w-3.5 h-3.5" />
            删除
        </button>
    );
}

function RenderResultPane({
    result,
    parseError,
    blockingErrors,
    warnings,
    onRemoveConcept,
}: {
    result: MaterialWebenLlmResult | null;
    parseError: string | null;
    blockingErrors: WebenValidationIssue[];
    warnings: WebenValidationIssue[];
    onRemoveConcept?: (index: number) => void;
}) {
    return (
        <PromptPaneShell title="组件渲染 / 保存前审阅">
            <div className="h-full min-h-[360px] overflow-auto p-4 space-y-4">
                {parseError ? (
                    <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{parseError}</div>
                ) : null}

                {!result && !parseError ? (
                    <div className="text-sm text-gray-400">生成完成后，在这里查看、校验并审阅 concepts。</div>
                ) : null}

                {result ? (
                    <>
                        <ValidationSummary blockingErrors={blockingErrors} warnings={warnings} />

                        <section className="space-y-2">
                            <h4 className="text-sm font-semibold text-gray-800">概念 ({result.concepts.length})</h4>
                            <div className="space-y-2">
                                {result.concepts.map((concept, index) => (
                                    <div key={`${concept.name}-${index}`} className="rounded-lg border border-gray-200 bg-white p-3">
                                        <div className="flex items-start justify-between gap-3">
                                            <div className="min-w-0 flex-1">
                                                <div className="flex items-center gap-2 flex-wrap">
                                                    <span className="text-sm font-semibold text-gray-900">{concept.name}</span>
                                                    {concept.types.map(t => (
                                                        <span key={t} className="text-xs px-2 py-0.5 rounded-full bg-violet-50 text-violet-700">{t}</span>
                                                    ))}
                                                </div>
                                                <p className="text-sm text-gray-600 mt-2 leading-relaxed">{concept.description}</p>
                                                {concept.aliases?.length ? (
                                                    <div className="mt-2 text-xs text-gray-500">别名：{concept.aliases.join("、")}</div>
                                                ) : null}
                                            </div>
                                            <DeleteRowButton onClick={() => onRemoveConcept?.(index)} label={`删除概念 ${concept.name}`} />
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </section>
                    </>
                ) : null}
            </div>
        </PromptPaneShell>
    );
}

function ConceptSaveModal({ diff, onConfirm, onCancel }: {
    diff: ConceptDiff;
    onConfirm: (concepts: MaterialWebenLlmResult["concepts"]) => void;
    onCancel: () => void;
}) {
    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
            <div className="bg-white rounded-2xl shadow-xl w-full max-w-lg mx-4 p-5 space-y-4">
                <div className="flex items-center justify-between">
                    <h3 className="text-base font-semibold text-gray-900">审阅概念变更</h3>
                    <button onClick={onCancel} className="p-1 text-gray-400 hover:text-gray-600">
                        <X className="w-4 h-4" />
                    </button>
                </div>
                <ConceptSaveEditor diff={diff} onConfirm={onConfirm} onCancel={onCancel} />
            </div>
        </div>
    );
}

export default function SummaryWebenPage() {
    const { materialId: routeMaterialId = "" } = useParams<{ materialId: string }>();
    const workspaceContext = useWorkspaceContext();
    const material = useMemo(() => getSafeWorkspaceMaterial(workspaceContext?.material), [workspaceContext]);
    const appFetchContext = useAppFetch();
    const apiFetch = useMemo(() => getSafeApiFetch(appFetchContext?.apiFetch), [appFetchContext]);
    const appConfigContext = useAppConfig();
    const appConfig = useMemo(() => getSafeAppConfig(appConfigContext?.appConfig), [appConfigContext]);
    const [template, setTemplate] = useState("");
    const [previewResult, setPreviewResult] = useState<BuildPromptResult | null>(null);
    const [previewLoading, setPreviewLoading] = useState(false);
    const [resolvedModelId, setResolvedModelId] = useState<string | null>(null);
    const [disableCache, setDisableCache] = useState(false);
    const [subtitles, setSubtitles] = useState<MaterialSubtitleItem[]>([]);
    const [streamText, setStreamText] = useState("");
    const [streamError, setStreamError] = useState<string | null>(null);
    // parsedResult：LLM 输出的原始解析结果，只写一次，不可变。
    // reviewedResult：用户在渲染面板中手动删除条目后的可变副本，
    //   保存时使用此值；重新生成时两者同时清空。
    const [parsedResult, setParsedResult] = useState<MaterialWebenLlmResult | null>(null);
    const [reviewedResult, setReviewedResult] = useState<MaterialWebenLlmResult | null>(null);
    const [parseError, setParseError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);
    const [lastSavedSourceId, setLastSavedSourceId] = useState<string | null>(null);
    const [stage, setStage] = useState<Stage>("editing");
    const [saveEditorDiff, setSaveEditorDiff] = useState<ConceptDiff | "loading" | null>(null);
    const [savedConceptBaseline, setSavedConceptBaseline] = useState<WebenConcept[] | null>(null);
    const saveButtonRef = useRef<HTMLButtonElement>(null);

    // Captured extraction context for WebenExtractionRunSaveRoute
    const capturedPromptText = useRef<string | null>(null);
    const capturedLlmInputJson = useRef<string | null>(null);
    const capturedLlmOutputRaw = useRef<string | null>(null);

    // 模板管理状态
    const [pickerOpen, setPickerOpen] = useState(false);
    const [saveModalOpen, setSaveModalOpen] = useState(false);
    /** 上次从模板选择器加载的模板元数据，用于"覆盖"判断和底部信息栏展示。 */
    const [loadedTemplate, setLoadedTemplate] = useState<PromptTemplateListItem | null>(null);
    /** 上次加载/保存时的脚本内容快照，用于判断是否有未保存修改。 */
    const [loadedTemplateCode, setLoadedTemplateCode] = useState<string | null>(null);

    const selectedSubtitle = subtitles[0] ?? null;
    const effectiveMaterialId = material.id.trim() || routeMaterialId.trim();

    // 头部代码：由编辑器自动注入，不属于模板内容，不可编辑。
    // 使用路由参数 routeMaterialId（直接来自 URL），确保始终非空。
    // 提交时前置于模板代码之前，后端从 GraalJS bindings 读取 __materialId。
    const scriptHeader = useMemo(() => [
        "// 执行上下文 - 由编辑器自动注入",
        `var __materialId = ${JSON.stringify(routeMaterialId)};`,
    ].join("\n"), [routeMaterialId]);

    const reviewValidation = useMemo(() => {
        return reviewedResult
            ? validateWebenResult(reviewedResult)
            : { sanitizedResult: null, blockingErrors: [], warnings: [] };
    }, [reviewedResult]);

    const hasUsableModel = Boolean(resolvedModelId?.trim());

    // 有加载的模板且当前脚本内容与加载快照不同，则视为有未保存修改
    const hasUnsavedChanges =
        loadedTemplate !== null &&
        loadedTemplateCode !== null &&
        template !== loadedTemplateCode;

    useEffect(() => {
        let cancelled = false;
        fetchMaterialSubtitles(apiFetch, effectiveMaterialId)
            .then(items => {
                if (!cancelled) setSubtitles(items);
            })
            .catch(error => {
                if (!cancelled) print_error({ reason: "加载字幕列表失败", err: error });
            });

        return () => { cancelled = true; };
    }, [apiFetch, effectiveMaterialId]);

    // LLM 输出解析完成后，将"保存到 Weben"按钮滚动进视口
    useEffect(() => {
        if (stage === "parsed") {
            saveButtonRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
        }
    }, [stage]);

    const handlePreview = async () => {
        console.debug("[SummaryWebenPage] handlePreview: routeMaterialId=", routeMaterialId, "template.length=", template.length, "scriptHeader.length=", scriptHeader.length);
        if (!routeMaterialId.trim()) {
            const msg = "素材 ID 无效，无法执行脚本";
            print_error({ reason: msg });
            setPreviewResult({ text: msg, charCount: 0, blocked: true, warnings: [] });
            return;
        }
        setPreviewLoading(true);
        try {
            // 调用后端 GraalJS 沙箱执行脚本，返回解析后的 Prompt 文本
            const { promptText, error } = await previewPromptScript({
                scriptCode: `${scriptHeader}\n\n${template}`,
                connection: {
                    domain: appConfig.webserver_domain,
                    port: appConfig.webserver_port,
                    schema: appConfig.webserver_schema,
                    appAuthToken: appConfig.webserver_auth_token,
                    sessionToken: appConfig.session_token,
                },
            });
            console.debug("[SummaryWebenPage] handlePreview: result error=", error, "promptText.length=", promptText?.length ?? 0);
            if (error) {
                print_error({ reason: `脚本执行失败: ${error}` });
                setPreviewResult({ text: `脚本执行失败：${error}`, charCount: 0, blocked: true, warnings: [] });
            } else {
                const text = promptText ?? "";
                setPreviewResult({ text, charCount: text.length, blocked: false, warnings: [] });
            }
            setStage("previewed");
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            console.debug("[SummaryWebenPage] handlePreview: caught exception:", error);
            print_error({ reason: "构建 Prompt 预览失败", err: error });
            setPreviewResult({ text: `预览失败：${message}`, charCount: 0, blocked: true, warnings: [] });
        } finally {
            setPreviewLoading(false);
        }
    };

    const handleGenerate = async (signal: AbortSignal) => {
        console.debug("[SummaryWebenPage] handleGenerate: routeMaterialId=", routeMaterialId, "template.length=", template.length);
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
            // Step 1: 调用后端 GraalJS 沙箱执行脚本，获取解析后的 Prompt 文本
            const { promptText, error: scriptError } = await previewPromptScript({
                scriptCode: `${scriptHeader}\n\n${template}`,
                connection: {
                    domain: appConfig.webserver_domain,
                    port: appConfig.webserver_port,
                    schema: appConfig.webserver_schema,
                    appAuthToken: appConfig.webserver_auth_token,
                    sessionToken: appConfig.session_token,
                },
            });

            if (signal.aborted) return;

            if (scriptError || !promptText?.trim()) {
                const message = scriptError ?? "脚本未返回 Prompt 文本";
                print_error({ reason: `脚本执行失败: ${message}` });
                setStreamError(message);
                setStage("parse_error");
                return;
            }

            const resolvedText = promptText;
            capturedPromptText.current = resolvedText;
            const messagesJson = JSON.stringify([{ role: "user", content: resolvedText }]);
            capturedLlmInputJson.current = messagesJson;
            setPreviewResult({ text: resolvedText, charCount: resolvedText.length, blocked: false, warnings: [] });
            setStage("generating");
            setStreamText("");
            setStreamError(null);
            setParsedResult(null);
            setReviewedResult(null);
            setParseError(null);
            capturedLlmOutputRaw.current = null;

            // Step 2: 将 Prompt 文本发送给 LLM，流式接收输出
            const fullText = await streamLlmRouterText({
                appModelId: resolvedModelId,
                messagesJson,
                connection: {
                    domain: appConfig.webserver_domain,
                    port: appConfig.webserver_port,
                    schema: appConfig.webserver_schema,
                    appAuthToken: appConfig.webserver_auth_token,
                    sessionToken: appConfig.session_token,
                },
                onChunk: chunk => {
                    setStreamText(prev => prev + chunk);
                },
                signal,
                disableCache,
            });

            if (signal.aborted) return;

            capturedLlmOutputRaw.current = fullText;

            try {
                const parsed = parseWebenResult(fullText);
                setParsedResult(parsed);
                setReviewedResult(parsed);
                setStage("parsed");
            } catch (error) {
                const message = error instanceof Error ? error.message : String(error);
                setParseError(`解析失败：${message}`);
                setStage("parse_error");
            }
        } catch (error) {
            if (signal.aborted) return;
            const message = error instanceof Error ? error.message : String(error);
            setStreamError(message);
            print_error({ reason: "生成失败", err: error });
            setStage("parse_error");
        } finally {
            setPreviewLoading(false);
        }
    };

    const handleRemoveConcept = useCallback((index: number) => {
        setReviewedResult(prev => prev ? {
            ...prev,
            concepts: prev.concepts.filter((_, itemIndex) => itemIndex !== index),
        } : prev);
    }, []);


    // ── 模板管理回调 ──────────────────────────────────────────────────────────

    const handleLoadTemplate = useCallback((scriptCode: string, listItem: PromptTemplateListItem) => {
        setTemplate(scriptCode);
        setLoadedTemplate(listItem);
        setLoadedTemplateCode(scriptCode); // 快照，用于未保存修改检测
        // 加载新模板后重置生成结果，避免旧结果和新脚本混用
        setStage("editing");
        setPreviewResult(null);
        setStreamText("");
        setStreamError(null);
        setParsedResult(null);
        setReviewedResult(null);
        setParseError(null);
        setSavedConceptBaseline(null);
    }, []);

    const handleSaveClick = async () => {
        if (!reviewValidation.sanitizedResult || reviewValidation.blockingErrors.length > 0) return;
        setSaveEditorDiff("loading");
        try {
            const fetchedConcepts = await fetchConceptsByMaterial(apiFetch, effectiveMaterialId);
            const existing = resolveConceptDiffBaseline({
                fetchedConcepts,
                savedConceptBaseline,
                materialId: effectiveMaterialId,
            });
            const diff = computeConceptDiff(existing, reviewValidation.sanitizedResult.concepts);
            console.debug("[SummaryWebenPage] handleSaveClick: computed diff", {
                materialId: effectiveMaterialId,
                incomingCount: reviewValidation.sanitizedResult.concepts.length,
                existingCount: existing.length,
                addedCount: diff.added.length,
                changedCount: diff.changed.length,
                removedCount: diff.removed.length,
                unchangedCount: diff.unchanged.length,
                addedNames: diff.added.map(c => c.name),
                changedNames: diff.changed.map(item => item.incoming.name),
                removedNames: diff.removed.map(c => c.canonical_name),
                unchangedNames: diff.unchanged.map(c => c.name),
            });
            setSaveEditorDiff(diff);
        } catch (e) {
            print_error({ reason: "加载已有概念失败", err: e });
            setSaveEditorDiff(null);
        }
    };

    const handleEditorConfirm = async (finalConcepts: MaterialWebenLlmResult["concepts"]) => {
        setSaveEditorDiff(null);
        setSaving(true);
        try {
            const { resp, data } = await saveExtractionRun(apiFetch, {
                material_id: effectiveMaterialId,
                source_title: material.title,
                prompt_script: template || undefined,
                prompt_text: capturedPromptText.current ?? undefined,
                llm_model_id: resolvedModelId ?? undefined,
                llm_input_json: capturedLlmInputJson.current ?? undefined,
                llm_output_raw: capturedLlmOutputRaw.current ?? undefined,
                concepts: finalConcepts,
            });
            if (!resp.ok) { reportHttpError("保存到 Weben 失败", resp); return; }
            if (data?.error) { print_error({ reason: `保存到 Weben 失败: ${data.error}` }); return; }
            const nextBaseline = finalConcepts.map((concept, index) => ({
                id: `saved-${index}-${concept.name}`,
                material_id: effectiveMaterialId,
                canonical_name: concept.name,
                concept_type: concept.types.join(","),
                brief_definition: concept.description,
                metadata_json: "{}",
                confidence: 1.0,
                first_seen_at: 0,
                last_seen_at: 0,
                created_at: 0,
                updated_at: 0,
            }));
            console.debug("[SummaryWebenPage] handleEditorConfirm: save success", {
                materialId: effectiveMaterialId,
                savedCount: nextBaseline.length,
                savedNames: nextBaseline.map(c => c.canonical_name),
                sourceId: data?.source_id ?? null,
                runId: data?.run_id ?? null,
            });
            setSavedConceptBaseline(nextBaseline);
            setLastSavedSourceId(data?.source_id ?? null);
            toast.success(`已保存到 Weben：概念 ${data?.concept_total ?? 0} 条`);
        } catch (e) {
            print_error({ reason: "保存到 Weben 异常", err: e });
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className="p-4 sm:p-6 space-y-4">
            <div className="bg-white rounded-xl border border-gray-200 p-4 sm:p-5 space-y-4">
                <div className="flex items-start gap-3">
                    <div className="p-2.5 rounded-xl bg-violet-50 text-violet-600">
                        <BrainCircuit className="w-5 h-5" />
                    </div>
                    <div className="min-w-0 flex-1">
                        <h3 className="text-base font-semibold text-gray-900">Weben 知识提取工作台</h3>
                        <p className="text-sm text-gray-500 mt-1">先用最小闭环打通模板编辑、变量预览、流式输出与 JSON 结果渲染。</p>
                    </div>
                </div>

                <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
                    <div className="rounded-lg border border-gray-200 bg-gray-50 px-3 py-3">
                        <div className="text-xs font-medium text-gray-500">信息来源</div>
                        <div className="mt-2 text-sm text-gray-800">字幕文本</div>
                        <div className="mt-1 text-xs text-gray-500">
                            {selectedSubtitle
                                ? `${selectedSubtitle.lan_doc || selectedSubtitle.lan} · ${selectedSubtitle.source}`
                                : "暂无可用字幕，请先去“字幕提取”标签获取字幕"}
                        </div>
                    </div>

                    <div className="rounded-lg border border-gray-200 bg-gray-50 px-3 py-3">
                        <div className="text-xs font-medium text-gray-500">当前阶段</div>
                        <div className="mt-2 flex items-center gap-2 text-sm text-gray-800">
                            {stage === "generating" ? <Loader2 className="w-4 h-4 animate-spin text-blue-500" /> : <CheckCircle2 className="w-4 h-4 text-green-500" />}
                            <span>{stage}</span>
                        </div>
                        <div className="mt-1 text-xs text-gray-500">preview 与 submit 共享同一套变量解析链路。</div>
                        {lastSavedSourceId ? (
                            <Link
                                to={`/weben/sources/${lastSavedSourceId}`}
                                className="mt-2 inline-flex items-center gap-1 text-xs text-green-700 hover:text-green-900 hover:underline"
                            >
                                <ExternalLink className="w-3 h-3" />
                                已保存 · 查看来源详情
                            </Link>
                        ) : null}
                    </div>
                </div>
            </div>

            <div className="flex items-center justify-end gap-2">
                {(stage === "parsed" || stage === "parse_error") && (
                    <button
                        type="button"
                        onClick={() => {
                            setStage("editing");
                            setStreamText("");
                            setStreamError(null);
                            setParsedResult(null);
                            setReviewedResult(null);
                            setParseError(null);
                        }}
                        className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium rounded-lg border border-gray-200 bg-white text-gray-600 hover:bg-gray-50"
                    >
                        <RotateCcw className="w-4 h-4" />
                        重新生成
                    </button>
                )}
                <button
                    type="button"
                    ref={saveButtonRef}
                    onClick={() => void handleSaveClick()}
                    disabled={!reviewValidation.sanitizedResult || reviewValidation.blockingErrors.length > 0 || saving || saveEditorDiff === "loading"}
                    className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium rounded-lg bg-violet-600 text-white hover:bg-violet-700 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    {saving || saveEditorDiff === "loading" ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                    保存到 Weben
                </button>
            </div>

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
                generateDisabled={!hasUsableModel}
                renderDisabled={!reviewedResult && !parseError}
                currentTemplateName={loadedTemplate?.name}
                settingsStorageKey={`material.summary.weben:${effectiveMaterialId}`}
                onResolvedModelChange={setResolvedModelId}
                defaultTemplateId="sys_weben_extract_v1"
                apiFetch={apiFetch}
                onDefaultTemplateLoaded={handleLoadTemplate}
                readonlyHeader={scriptHeader}
                disableCache={disableCache}
                onDisableCacheChange={setDisableCache}
                editorHeaderExtra={(
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
                )}
                streamPane={<PromptStreamPane text={streamText} error={streamError} actions={null} />}
                renderPane={(
                    <RenderResultPane
                        result={reviewedResult}
                        parseError={parseError}
                        blockingErrors={reviewValidation.blockingErrors}
                        warnings={reviewValidation.warnings}
                        onRemoveConcept={handleRemoveConcept}
                    />
                )}
            />

            {pickerOpen ? (
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
            ) : null}

            {saveModalOpen ? (
                <SaveTemplateModal
                    scriptCode={template}
                    loadedFromTemplate={loadedTemplate}
                    apiFetch={apiFetch}
                    onSaved={savedId => {
                        toast.success("模板已保存");
                        // 更新 loadedTemplate，并刷新快照（保存后视为"已同步"）
                        setLoadedTemplate(prev => prev ? { ...prev, id: savedId } : null);
                        setLoadedTemplateCode(template);
                    }}
                    onClose={() => setSaveModalOpen(false)}
                />
            ) : null}

            <div className="rounded-xl border border-violet-100 bg-violet-50 px-4 py-3 text-sm text-violet-800 flex items-start gap-2">
                <Sparkles className="w-4 h-4 mt-0.5 flex-shrink-0" />
                <div>
                    这一版已接入真实 LlmModelListRoute、通用字幕内容接口，以及最小可用的保存到 Weben DB 链路；后续可继续扩展更多信息源与更细的导入控制。
                </div>
            </div>

            {saveEditorDiff !== null && saveEditorDiff !== "loading" && (
                <ConceptSaveModal
                    diff={saveEditorDiff}
                    onConfirm={handleEditorConfirm}
                    onCancel={() => setSaveEditorDiff(null)}
                />
            )}
        </div>
    );
}
