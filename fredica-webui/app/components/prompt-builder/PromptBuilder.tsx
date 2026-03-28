import { cloneElement, isValidElement, useEffect, useMemo, useRef, useState } from "react";
import { AlertTriangle, Eye, Play, Settings2, Sparkles, Cloud, CloudOff } from "lucide-react";
import { buildPrompt } from "~/util/prompt-builder/buildPrompt";
import { print_error } from "~/util/error_handler";
import {
    fetchLlmModelAvailability,
    fetchLlmModels,
    type LlmModelAvailability,
    type LlmModelMeta,
} from "~/util/materialWebenApi";
import { fetchPromptTemplateById } from "~/util/prompt-builder/promptTemplateApi";
import { PromptPaneShell } from "./PromptPaneShell";
import { PromptPreviewPane } from "./PromptPreviewPane";
import { PromptWorkbenchTabs } from "./PromptWorkbenchTabs";
import type { PromptBuilderProps, PromptWorkbenchTab } from "./promptBuilderTypes";

const EMPTY_MODEL_AVAILABILITY: LlmModelAvailability = {
    available_count: 0,
    has_any_available_model: false,
    selected_model_id: null,
    selected_model_available: false,
};

function PaneVisibility({
    active,
    tab,
    children,
}: {
    active: PromptWorkbenchTab;
    tab: PromptWorkbenchTab;
    children: React.ReactNode;
}) {
    return (
        <div
            role="tabpanel"
            id={`prompt-panel-${tab}`}
            aria-labelledby={`prompt-tab-${tab}`}
            hidden={active !== tab}
            className="h-full"
        >
            <div className="h-full">{children}</div>
        </div>
    );
}

function getModelStorageKey(scopeKey: string) {
    return `prompt_builder:${scopeKey}:selectedModelId`;
}

function readStoredModelId(scopeKey: string) {
    if (typeof window === "undefined") return "";
    return window.localStorage.getItem(getModelStorageKey(scopeKey)) ?? "";
}

function writeStoredModelId(scopeKey: string, modelId: string) {
    if (typeof window === "undefined") return;
    window.localStorage.setItem(getModelStorageKey(scopeKey), modelId);
}

function getSelectedModel(models: LlmModelMeta[], selectedModelId: string) {
    return models.find(model => model.app_model_id === selectedModelId) ?? null;
}

function getModelProblem(params: {
    loading: boolean;
    availability: LlmModelAvailability;
    selectedModelId: string;
}) {
    const { loading, availability, selectedModelId } = params;
    if (loading) return null;
    if (!availability.has_any_available_model) return "host_invalid" as const;
    if (!selectedModelId.trim() || !availability.selected_model_available) return "selected_invalid" as const;
    return null;
}

function getInfoBarText(params: {
    activeTab: PromptWorkbenchTab;
    loading: boolean;
    availability: LlmModelAvailability;
    selectedModel: LlmModelMeta | null;
    problem: ReturnType<typeof getModelProblem>;
}) {
    const { activeTab, loading, availability, selectedModel, problem } = params;
    if (loading) return "正在检查模型可用性…";
    if (problem === "host_invalid") {
        return activeTab === "settings"
            ? "服主配置无效。需要由服主在 APP 窗口中配置模型，租户侧无法自行修改。"
            : "服主配置无效";
    }
    if (problem === "selected_invalid") {
        return activeTab === "settings"
            ? "你配置的模型失效。请在这里重新选择可用模型。"
            : "你配置的模型失效";
    }
    if (activeTab === "editor") {
        return `当前使用的模型为 ${selectedModel?.label ?? "未配置"}`;
    }
    if (activeTab === "settings") {
        return selectedModel?.notes?.trim() || `当前使用的模型为 ${selectedModel?.label ?? "未配置"}`;
    }
    return `当前模型：${selectedModel?.label ?? "未配置"}`;
}

export function PromptBuilder(props: PromptBuilderProps) {
    const {
        value,
        onChange,
        previewResult,
        previewLoading = false,
        streamPane,
        renderPane,
        onPreview,
        onGenerate,
        generateDisabled = false,
        renderDisabled = false,
        className,
        editorHeaderExtra,
        currentTemplateName,
        settingsStorageKey,
        onResolvedModelChange,
        defaultTemplateId,
        apiFetch,
        onDefaultTemplateLoaded,
        readonlyHeader,
        disableCache = false,
        onDisableCacheChange,
    } = props;
    const [activeTab, setActiveTab] = useState<PromptWorkbenchTab>("editor");
    const [availableModels, setAvailableModels] = useState<LlmModelMeta[]>([]);
    const [selectedModelId, setSelectedModelId] = useState("");
    const [modelAvailability, setModelAvailability] = useState<LlmModelAvailability>(EMPTY_MODEL_AVAILABILITY);
    const [modelAvailabilityLoading, setModelAvailabilityLoading] = useState(true);
    const [generating, setGenerating] = useState(false);
    const generateAbortRef = useRef<AbortController | null>(null);
    const lastGenerateTimeRef = useRef<number>(0);
    const GENERATE_COOLDOWN_MS = 2000;

    const onChangeRef = useRef(onChange);
    const onDefaultTemplateLoadedRef = useRef(onDefaultTemplateLoaded);
    onChangeRef.current = onChange;
    onDefaultTemplateLoadedRef.current = onDefaultTemplateLoaded;

    useEffect(() => {
        if (!defaultTemplateId || !apiFetch) return;
        let cancelled = false;
        fetchPromptTemplateById(apiFetch, defaultTemplateId).then(tpl => {
            if (cancelled || !tpl) return;
            onChangeRef.current(tpl.script_code);
            onDefaultTemplateLoadedRef.current?.(tpl.script_code, tpl);
        });
        return () => { cancelled = true; };
    }, [defaultTemplateId, apiFetch]); // eslint-disable-line react-hooks/exhaustive-deps

    useEffect(() => {
        setSelectedModelId(readStoredModelId(settingsStorageKey));
    }, [settingsStorageKey]);

    useEffect(() => {
        if (!apiFetch) {
            setAvailableModels([]);
            setModelAvailability(EMPTY_MODEL_AVAILABILITY);
            setModelAvailabilityLoading(false);
            return;
        }
        let cancelled = false;
        fetchLlmModels(apiFetch)
            .then(items => {
                if (cancelled) return;
                setAvailableModels(items);
                if (!selectedModelId.trim() && items.length > 0) {
                    const nextModelId = items[0].app_model_id;
                    setSelectedModelId(nextModelId);
                    writeStoredModelId(settingsStorageKey, nextModelId);
                }
            })
            .catch(error => {
                if (cancelled) return;
                print_error({ reason: "加载模型列表失败", err: error });
                setAvailableModels([]);
            });
        return () => { cancelled = true; };
    }, [apiFetch, settingsStorageKey, selectedModelId]);

    useEffect(() => {
        if (!apiFetch) {
            setModelAvailability(EMPTY_MODEL_AVAILABILITY);
            setModelAvailabilityLoading(false);
            return;
        }
        let cancelled = false;
        setModelAvailabilityLoading(true);
        fetchLlmModelAvailability(apiFetch, selectedModelId)
            .then(result => {
                if (!cancelled) setModelAvailability(result);
            })
            .catch(error => {
                if (cancelled) return;
                print_error({ reason: "检查模型可用性失败", err: error });
                setModelAvailability(EMPTY_MODEL_AVAILABILITY);
            })
            .finally(() => {
                if (!cancelled) setModelAvailabilityLoading(false);
            });
        return () => { cancelled = true; };
    }, [apiFetch, selectedModelId]);

    const selectedModel = useMemo(
        () => getSelectedModel(availableModels, selectedModelId),
        [availableModels, selectedModelId],
    );
    const modelProblem = useMemo(
        () => getModelProblem({ loading: modelAvailabilityLoading, availability: modelAvailability, selectedModelId }),
        [modelAvailability, modelAvailabilityLoading, selectedModelId],
    );
    const internalGenerateDisabled = !generating && (generateDisabled || modelAvailabilityLoading || modelProblem !== null);

    const handleGenerateClick = () => {
        if (generating) {
            generateAbortRef.current?.abort();
            setGenerating(false);
            return;
        }
        const now = Date.now();
        if (now - lastGenerateTimeRef.current < GENERATE_COOLDOWN_MS) return;
        lastGenerateTimeRef.current = now;
        if (!onGenerate) return;
        const ctrl = new AbortController();
        generateAbortRef.current = ctrl;
        setGenerating(true);
        setActiveTab("stream");
        void Promise.resolve(onGenerate(ctrl.signal)).finally(() => {
            if (generateAbortRef.current === ctrl) setGenerating(false);
        });
    };
    const settingsTabLabel = modelProblem ? "⚠ 设置" : "设置";
    const infoBarText = useMemo(
        () => getInfoBarText({ activeTab, loading: modelAvailabilityLoading, availability: modelAvailability, selectedModel, problem: modelProblem }),
        [activeTab, modelAvailability, modelAvailabilityLoading, modelProblem, selectedModel],
    );

    useEffect(() => {
        onResolvedModelChange?.(modelProblem ? null : (selectedModel?.app_model_id ?? null));
    }, [modelProblem, onResolvedModelChange, selectedModel]);

    const tabs = [
        { id: "editor", label: "编辑器" },
        { id: "settings", label: settingsTabLabel },
        { id: "preview", label: "预览" },
        { id: "stream", label: "LLM 输出" },
        { id: "render", label: "组件渲染", disabled: renderDisabled },
    ] as const;

    function renderActions(includeEditorExtra: boolean) {
        return (
            <>
                {includeEditorExtra ? editorHeaderExtra : null}
                <button
                    type="button"
                    onClick={() => {
                        setActiveTab("preview");
                        void onPreview();
                    }}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-lg border border-gray-200 bg-white text-gray-700 hover:bg-gray-50"
                >
                    <Eye className="w-4 h-4" />
                    预览
                </button>
                {onGenerate ? (
                    <div className="inline-flex rounded-lg overflow-hidden border border-gray-200">
                        <button
                            type="button"
                            onClick={handleGenerateClick}
                            disabled={internalGenerateDisabled}
                            className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50 disabled:cursor-not-allowed ${
                                generating ? "bg-red-500 hover:bg-red-600" : "bg-violet-600 hover:bg-violet-700"
                            }`}
                        >
                            {generating ? <><Sparkles className="w-4 h-4 animate-spin" />中断</> : <><Play className="w-4 h-4" />生成</>}
                        </button>
                        {onDisableCacheChange ? (
                            <button
                                type="button"
                                onClick={() => onDisableCacheChange(!disableCache)}
                                disabled={generating}
                                title={disableCache ? "缓存已关闭（点击启用）" : "缓存已启用（点击关闭）"}
                                className="px-2 border-l bg-violet-600 hover:bg-violet-700 text-white border-violet-500 disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                {disableCache ? <CloudOff className="w-4 h-4" /> : <Cloud className="w-4 h-4" />}
                            </button>
                        ) : null}
                    </div>
                ) : null}
            </>
        );
    }

    return (
        <div className={className ?? "bg-white rounded-xl border border-gray-200 overflow-hidden"}>
            <PromptWorkbenchTabs tabs={[...tabs]} activeTab={activeTab} onChange={setActiveTab} />

            <div className="px-4 py-3 border-b border-gray-100 bg-gray-50 text-sm text-gray-600 flex items-start gap-2">
                {modelProblem ? <AlertTriangle className="w-4 h-4 mt-0.5 text-amber-500 flex-shrink-0" /> : <Settings2 className="w-4 h-4 mt-0.5 text-violet-500 flex-shrink-0" />}
                <span>{infoBarText}</span>
            </div>

            <div className="p-4 sm:p-5">
                <div className="h-[560px]">
                    <PaneVisibility active={activeTab} tab="editor">
                        <PromptPaneShell
                            title="Prompt 编辑器"
                            actions={renderActions(true)}
                        >
                            <div className="h-full flex flex-col min-h-[360px]">
                                {readonlyHeader ? (
                                    <div
                                        className="px-4 py-2 border-b border-gray-100 bg-gray-50 text-xs font-mono text-gray-400 whitespace-pre select-none cursor-default"
                                        aria-label="只读头部（自动注入）"
                                    >
                                        {readonlyHeader}
                                    </div>
                                ) : null}
                                <textarea
                                    value={value}
                                    onChange={event => onChange(event.target.value)}
                                    className="flex-1 w-full resize-none border-0 outline-none p-4 text-sm leading-6 text-gray-800 font-mono bg-white"
                                    placeholder="在这里编写 Prompt 模板，可使用 ${material.title}、${subtitle} 等变量。"
                                />
                                <div className="px-4 py-3 border-t border-gray-100 bg-gray-50 text-xs text-gray-500 flex items-center gap-2">
                                    <Sparkles className="w-3.5 h-3.5" />
                                    {currentTemplateName ? (
                                        <span>当前模板：<span className="font-medium text-gray-700">{currentTemplateName}</span></span>
                                    ) : (
                                        "预览或生成时才会解析变量，不在输入过程中自动刷新。"
                                    )}
                                </div>
                            </div>
                        </PromptPaneShell>
                    </PaneVisibility>

                    <PaneVisibility active={activeTab} tab="settings">
                        <PromptPaneShell title="模型设置">
                            <div className="p-4 space-y-4 text-sm text-gray-700">
                                <div>
                                    <label className="block text-xs font-medium text-gray-500">当前模型</label>
                                    <select
                                        value={selectedModelId}
                                        onChange={event => {
                                            const nextModelId = event.target.value;
                                            setSelectedModelId(nextModelId);
                                            writeStoredModelId(settingsStorageKey, nextModelId);
                                        }}
                                        disabled={modelAvailabilityLoading || availableModels.length === 0}
                                        className="mt-2 w-full text-sm border border-gray-200 rounded-lg px-3 py-2 bg-white outline-none focus:ring-1 focus:ring-violet-400 disabled:opacity-50"
                                    >
                                        {availableModels.length === 0 ? <option value="">暂无可用模型</option> : null}
                                        {availableModels.map(model => (
                                            <option key={model.app_model_id} value={model.app_model_id}>{model.label}</option>
                                        ))}
                                    </select>
                                </div>

                                {selectedModel?.notes ? (
                                    <div className="rounded-lg border border-gray-200 bg-gray-50 px-3 py-3 text-xs text-gray-600">
                                        {selectedModel.notes}
                                    </div>
                                ) : null}

                                {modelProblem === "host_invalid" ? (
                                    <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-3 text-sm text-amber-800">
                                        当前无可用模型。需要由服主在 APP 窗口中配置模型，租户侧无法自行修改。
                                    </div>
                                ) : null}

                                {modelProblem === "selected_invalid" ? (
                                    <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-3 text-sm text-amber-800">
                                        你配置的模型失效。请在这里重新选择可用模型。
                                    </div>
                                ) : null}
                            </div>
                        </PromptPaneShell>
                    </PaneVisibility>

                    <PaneVisibility active={activeTab} tab="preview">
                        <PromptPreviewPane result={previewResult} loading={previewLoading} actions={renderActions(false)} />
                    </PaneVisibility>

                    <PaneVisibility active={activeTab} tab="stream">
                        {streamPane
                            ? (isValidElement(streamPane)
                                ? cloneElement(streamPane as React.ReactElement<{ actions?: React.ReactNode }>, { actions: renderActions(false) })
                                : streamPane)
                            : <PromptPaneShell title="LLM 输出" actions={renderActions(false)}><div className="p-4 text-sm text-gray-400">尚未提供流式输出面板。</div></PromptPaneShell>}
                    </PaneVisibility>

                    <PaneVisibility active={activeTab} tab="render">
                        {renderPane ?? <PromptPaneShell title="组件渲染"><div className="p-4 text-sm text-gray-400">尚未提供结果渲染面板。</div></PromptPaneShell>}
                    </PaneVisibility>
                </div>
            </div>
        </div>
    );
}

export async function buildPromptPreviewText(value: string, variableResolver: PromptBuilderProps["variableResolver"]) {
    return buildPrompt(value, variableResolver, { mode: "preview" });
}
