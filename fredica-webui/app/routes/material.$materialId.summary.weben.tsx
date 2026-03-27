import { Component, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router";
import { BookMarked, BookmarkPlus, BrainCircuit, CheckCircle2, Loader2, Save, Sparkles, Trash2, AlertTriangle } from "lucide-react";
import { toast } from "react-toastify";
import { PromptBuilder } from "~/components/prompt-builder/PromptBuilder";
import { PromptPaneShell } from "~/components/prompt-builder/PromptPaneShell";
import { PromptStreamPane } from "~/components/prompt-builder/PromptStreamPane";
import { PromptTemplatePickerModal } from "~/components/prompt-builder/PromptTemplatePickerModal";
import { SaveTemplateModal } from "~/components/prompt-builder/SaveTemplateModal";
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
import { createVariableResolver } from "~/util/prompt-builder/createVariableResolver";
import { VariableResolverCache } from "~/util/prompt-builder/VariableResolverCache";
import type { BuildPromptResult } from "~/util/prompt-builder/types";
import type { PromptTemplateListItem } from "~/util/prompt-builder/promptTemplateApi";
import { CONCEPT_TYPES, PREDICATES } from "~/util/weben";
import { json_parse } from "~/util/json";
import {
    fetchMaterialSubtitles,
    fetchSubtitleContent,
    getWebenPromptVariables,
    importWebenResult,
    previewPromptScript,
    type MaterialSubtitleItem,
    type MaterialWebenLlmResult,
} from "~/util/materialWebenApi";

type Stage = "editing" | "previewed" | "generating" | "parsed" | "parse_error";

function buildWebenSchemaHint() {
    const conceptTypes = CONCEPT_TYPES.map(item => item.key).join("、");
    const predicates = PREDICATES.join("、");
    return [
        "请严格输出如下 JSON 结构：",
        "{",
        '  "concepts": [{ "name": string, "type": string, "description": string, "aliases"?: string[] }],',
        '  "relations": [{ "subject": string, "predicate": string, "object": string, "excerpt"?: string }],',
        '  "flashcards": [{ "question": string, "answer": string, "concept": string }]',
        "}",
        `concept.type 可选值：${conceptTypes}`,
        `relation.predicate 可选值：${predicates}`,
    ].join("\n");
}

function parseWebenResult(raw: string): MaterialWebenLlmResult {
    const trimmed = raw.trim();
    const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
    const jsonText = fenced?.[1] ?? trimmed;
    return normalizeWebenResult(json_parse<any>(jsonText));
}

async function streamLlmRouterText(params: {
    appModelId: string;
    message: string;
    connection: {
        domain?: string | null;
        port?: string | null;
        schema?: string | null;
        appAuthToken?: string | null;
    };
    onChunk: (chunk: string) => void;
    signal?: AbortSignal;
}): Promise<string> {
    const schema = params.connection.schema ?? "http";
    const domain = params.connection.domain ?? "localhost";
    const port = params.connection.port ?? DEFAULT_SERVER_PORT;
    const resp = await fetch(`${schema}://${domain}:${port}/api/v1/LlmProxyChatRoute`, {
        method: "POST",
        headers: {
            ...buildAuthHeaders(params.connection.appAuthToken),
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            app_model_id: params.appModelId,
            message: params.message,
        }),
        signal: params.signal,
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);

    const reader = resp.body?.getReader();
    if (!reader) throw new Error("无法读取响应流");

    const decoder = new TextDecoder();
    let fullText = "";
    let buffer = "";
    try {
        while (true) {
            if (params.signal?.aborted) break;
            const { done, value } = await reader.read();
            if (value) buffer += decoder.decode(value, { stream: !done });
            const lines = buffer.split("\n");
            buffer = done ? "" : (lines.pop() ?? "");
            for (const line of lines) {
                const trimmed = line.replace(/\r$/, "").trim();
                if (!trimmed.startsWith("data:")) continue;
                const data = trimmed.slice(5).trim();
                if (data === "[DONE]") return fullText;
                try {
                    const chunk = (json_parse<any>(data))?.choices?.[0]?.delta?.content;
                    if (!chunk) continue;
                    fullText += chunk;
                    params.onChunk(chunk);
                } catch (error) {
                    console.debug("[SummaryWebenPage] ignore malformed LLM SSE chunk", error, { data });
                }
            }
            if (done) break;
        }
        return fullText;
    } finally {
        reader.releaseLock();
    }
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
    onRemoveRelation,
    onRemoveFlashcard,
}: {
    result: MaterialWebenLlmResult | null;
    parseError: string | null;
    blockingErrors: WebenValidationIssue[];
    warnings: WebenValidationIssue[];
    onRemoveConcept?: (index: number) => void;
    onRemoveRelation?: (index: number) => void;
    onRemoveFlashcard?: (index: number) => void;
}) {
    return (
        <PromptPaneShell title="组件渲染 / 保存前审阅">
            <div className="h-full min-h-[360px] overflow-auto p-4 space-y-4">
                {parseError ? (
                    <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{parseError}</div>
                ) : null}

                {!result && !parseError ? (
                    <div className="text-sm text-gray-400">生成完成后，在这里查看、校验并审阅 concepts / relations / flashcards。</div>
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
                                                    <span className="text-xs px-2 py-0.5 rounded-full bg-violet-50 text-violet-700">{concept.type}</span>
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

                        <section className="space-y-2">
                            <h4 className="text-sm font-semibold text-gray-800">关系 ({result.relations.length})</h4>
                            <div className="space-y-2">
                                {result.relations.map((relation, index) => (
                                    <div key={`${relation.subject}-${relation.predicate}-${relation.object}-${index}`} className="rounded-lg border border-gray-200 bg-white p-3 text-sm text-gray-700">
                                        <div className="flex items-start justify-between gap-3">
                                            <div className="min-w-0 flex-1">
                                                <div>
                                                    <span className="font-medium">{relation.subject}</span>
                                                    <span className="mx-2 text-violet-600">{relation.predicate}</span>
                                                    <span className="font-medium">{relation.object}</span>
                                                </div>
                                                {relation.excerpt ? <p className="text-xs text-gray-500 mt-2">摘录：{relation.excerpt}</p> : null}
                                            </div>
                                            <DeleteRowButton onClick={() => onRemoveRelation?.(index)} label={`删除关系 ${relation.subject} ${relation.predicate} ${relation.object}`} />
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </section>

                        <section className="space-y-2">
                            <h4 className="text-sm font-semibold text-gray-800">闪卡 ({result.flashcards.length})</h4>
                            <div className="space-y-2">
                                {result.flashcards.map((card, index) => (
                                    <div key={`${card.question}-${index}`} className="rounded-lg border border-gray-200 bg-white p-3">
                                        <div className="flex items-start justify-between gap-3">
                                            <div className="min-w-0 flex-1">
                                                <div className="text-sm font-medium text-gray-900">Q: {card.question}</div>
                                                <div className="text-sm text-gray-700 mt-2">A: {card.answer}</div>
                                                <div className="text-xs text-gray-500 mt-2">关联概念：{card.concept}</div>
                                            </div>
                                            <DeleteRowButton onClick={() => onRemoveFlashcard?.(index)} label={`删除闪卡 ${card.question}`} />
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
    const [subtitles, setSubtitles] = useState<MaterialSubtitleItem[]>([]);
    const [streamText, setStreamText] = useState("");
    const [streamError, setStreamError] = useState<string | null>(null);
    const [parsedResult, setParsedResult] = useState<MaterialWebenLlmResult | null>(null);
    const [reviewedResult, setReviewedResult] = useState<MaterialWebenLlmResult | null>(null);
    const [parseError, setParseError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);
    const [lastSavedSourceId, setLastSavedSourceId] = useState<string | null>(null);
    const [stage, setStage] = useState<Stage>("editing");

    // 模板管理状态
    const [pickerOpen, setPickerOpen] = useState(false);
    const [saveModalOpen, setSaveModalOpen] = useState(false);
    /** 上次从模板选择器加载的模板元数据，用于"覆盖"判断和底部信息栏展示。 */
    const [loadedTemplate, setLoadedTemplate] = useState<PromptTemplateListItem | null>(null);
    /** 上次加载/保存时的脚本内容快照，用于判断是否有未保存修改。 */
    const [loadedTemplateCode, setLoadedTemplateCode] = useState<string | null>(null);

    const schemaHint = useMemo(() => buildWebenSchemaHint(), []);
    const selectedSubtitle = subtitles[0] ?? null;
    const variables = useMemo(() => getWebenPromptVariables(), []);

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

    const cacheRef = useRef<VariableResolverCache | null>(null);
    const resolver = useMemo(() => createVariableResolver({
        variables,
        resolve: async key => {
            if (key === "material.title") {
                return { kind: "text", status: "ok", value: material.title || material.source_id };
            }
            if (key === "material.duration") {
                const minutes = material.duration > 0 ? `${Math.max(1, Math.round(material.duration / 60))} 分钟` : "未知";
                return { kind: "text", status: "ok", value: minutes };
            }
            if (key === "weben_schema_hint") {
                return { kind: "text", status: "ok", value: schemaHint };
            }
            if (key === "subtitle") {
                if (!selectedSubtitle?.subtitle_url) {
                    return { kind: "text", status: "unavailable", unavailableReason: "暂无可用字幕，请先完成字幕提取" };
                }
                const subtitleContent = await fetchSubtitleContent(apiFetch, selectedSubtitle);
                const text = subtitleContent.text;
                if (!text.trim()) {
                    return { kind: "text", status: "unavailable", unavailableReason: "字幕内容为空" };
                }
                return {
                    kind: "text",
                    status: "ok",
                    value: text,
                    preview: text.length > 1200 ? `${text.slice(0, 1200)}\n\n[...已截断预览，提交时仍使用完整字幕...]` : text,
                    charCount: subtitleContent.word_count,
                };
            }
            return { kind: "text", status: "unimplemented", unavailableReason: `变量 ${key} 尚未实现` };
        },
    }), [apiFetch, material.duration, material.source_id, material.title, schemaHint, selectedSubtitle?.subtitle_url, variables]);

    if (!cacheRef.current) {
        cacheRef.current = new VariableResolverCache(key => resolver(key), { ttlMs: 60_000 });
    }

    useEffect(() => {
        cacheRef.current = new VariableResolverCache(key => resolver(key), { ttlMs: 60_000 });
    }, [resolver]);

    useEffect(() => {
        let cancelled = false;
        fetchMaterialSubtitles(apiFetch, material.id)
            .then(items => {
                if (!cancelled) setSubtitles(items);
            })
            .catch(error => {
                if (!cancelled) print_error({ reason: "加载字幕列表失败", err: error });
            });

        return () => { cancelled = true; };
    }, [apiFetch, material.id]);

    const variableResolver = useMemo(() => {
        return (key: string) => cacheRef.current!.resolve(key);
    }, []);

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
            setPreviewResult({ text: resolvedText, charCount: resolvedText.length, blocked: false, warnings: [] });
            setStage("generating");
            setStreamText("");
            setStreamError(null);
            setParsedResult(null);
            setReviewedResult(null);
            setParseError(null);

            // Step 2: 将 Prompt 文本发送给 LLM，流式接收输出
            const fullText = await streamLlmRouterText({
                appModelId: resolvedModelId,
                message: resolvedText,
                connection: {
                    domain: appConfig.webserver_domain,
                    port: appConfig.webserver_port,
                    schema: appConfig.webserver_schema,
                    appAuthToken: appConfig.webserver_auth_token,
                },
                onChunk: chunk => {
                    setStreamText(prev => prev + chunk);
                },
                signal,
            });

            if (signal.aborted) return;

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
        // 审阅态与原始解析结果分离，避免用户删改后丢失“原始输出”的定位上下文。
        setReviewedResult(prev => prev ? {
            ...prev,
            concepts: prev.concepts.filter((_, itemIndex) => itemIndex !== index),
        } : prev);
    }, []);

    const handleRemoveRelation = useCallback((index: number) => {
        setReviewedResult(prev => prev ? {
            ...prev,
            relations: prev.relations.filter((_, itemIndex) => itemIndex !== index),
        } : prev);
    }, []);

    const handleRemoveFlashcard = useCallback((index: number) => {
        setReviewedResult(prev => prev ? {
            ...prev,
            flashcards: prev.flashcards.filter((_, itemIndex) => itemIndex !== index),
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
    }, []);

    const handleSave = async () => {        if (!reviewValidation.sanitizedResult || reviewValidation.blockingErrors.length > 0) return;
        setSaving(true);
        try {
            const { resp, data } = await importWebenResult(apiFetch, {
                material_id: material.id,
                source_title: material.title,
                concepts: reviewValidation.sanitizedResult.concepts,
                relations: reviewValidation.sanitizedResult.relations,
                flashcards: reviewValidation.sanitizedResult.flashcards,
            });
            if (!resp.ok) {
                reportHttpError("保存到 Weben 失败", resp);
                return;
            }
            if (data?.error) {
                print_error({ reason: `保存到 Weben 失败: ${data.error}` });
                return;
            }
            setLastSavedSourceId(data?.source_id ?? null);
            toast.success(`已保存到 Weben：概念 ${data?.concept_total ?? 0} 条，关系 ${data?.relation_imported ?? 0} 条，闪卡 ${data?.flashcard_imported ?? 0} 条`);
        } catch (error) {
            print_error({ reason: "保存到 Weben 异常", err: error });
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
                            <div className="mt-2 text-xs text-green-700">最近一次保存 source_id：{lastSavedSourceId}</div>
                        ) : null}
                    </div>
                </div>
            </div>

            <div className="flex items-center justify-end">
                <button
                    type="button"
                    onClick={() => void handleSave()}
                    disabled={!reviewValidation.sanitizedResult || reviewValidation.blockingErrors.length > 0 || saving}
                    className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium rounded-lg border border-gray-200 bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                    保存到 Weben
                </button>
            </div>

            <PromptBuilder
                value={template}
                onChange={next => {
                    setTemplate(next);
                    setStage("editing");
                }}
                variableResolver={variableResolver}
                variables={variables}
                previewResult={previewResult}
                previewLoading={previewLoading}
                onPreview={handlePreview}
                onGenerate={handleGenerate}
                generateDisabled={!hasUsableModel}
                renderDisabled={!reviewedResult && !parseError}
                currentTemplateName={loadedTemplate?.name}
                settingsStorageKey={`material.summary.weben:${material.id}`}
                onResolvedModelChange={setResolvedModelId}
                defaultTemplateId="sys_weben_extract_v1"
                apiFetch={apiFetch}
                onDefaultTemplateLoaded={handleLoadTemplate}
                readonlyHeader={scriptHeader}
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
                        onRemoveRelation={handleRemoveRelation}
                        onRemoveFlashcard={handleRemoveFlashcard}
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
        </div>
    );
}
