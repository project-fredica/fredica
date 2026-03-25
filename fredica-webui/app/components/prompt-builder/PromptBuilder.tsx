import { useEffect, useRef, useState } from "react";
import { Eye, Play, Sparkles } from "lucide-react";
import { buildPrompt } from "~/util/prompt-builder/buildPrompt";
import { fetchPromptTemplateById } from "~/util/prompt-builder/promptTemplateApi";
import { PromptPaneShell } from "./PromptPaneShell";
import { PromptPreviewPane } from "./PromptPreviewPane";
import { PromptWorkbenchTabs } from "./PromptWorkbenchTabs";
import type { PromptBuilderProps, PromptWorkbenchTab } from "./promptBuilderTypes";

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
        defaultTemplateId,
        apiFetch,
        onDefaultTemplateLoaded,
    } = props;
    const [activeTab, setActiveTab] = useState<PromptWorkbenchTab>("editor");

    // 稳定化回调引用，避免 defaultTemplateId effect 依赖函数 identity 变化
    const onChangeRef = useRef(onChange);
    const onDefaultTemplateLoadedRef = useRef(onDefaultTemplateLoaded);
    onChangeRef.current = onChange;
    onDefaultTemplateLoadedRef.current = onDefaultTemplateLoaded;

    // 挂载时若提供了 defaultTemplateId，自动查询并填入编辑器
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

    const tabs = [
        { id: "editor", label: "编辑器" },
        { id: "preview", label: "预览" },
        { id: "stream", label: "LLM 输出" },
        { id: "render", label: "组件渲染", disabled: renderDisabled },
    ] as const;

    return (
        <div className={className ?? "bg-white rounded-xl border border-gray-200 overflow-hidden"}>
            <PromptWorkbenchTabs tabs={[...tabs]} activeTab={activeTab} onChange={setActiveTab} />

            <div className="p-4 sm:p-5">
                <div className="h-[560px]">
                    <PaneVisibility active={activeTab} tab="editor">
                        <PromptPaneShell
                            title="Prompt 编辑器"
                            actions={(
                                <>
                                    {editorHeaderExtra}
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
                                        <button
                                            type="button"
                                            onClick={() => {
                                                setActiveTab("stream");
                                                void onGenerate();
                                            }}
                                            disabled={generateDisabled}
                                            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-lg bg-violet-600 text-white hover:bg-violet-700 disabled:opacity-50 disabled:cursor-not-allowed"
                                        >
                                            <Play className="w-4 h-4" />
                                            生成
                                        </button>
                                    ) : null}
                                </>
                            )}
                        >
                            <div className="h-full flex flex-col min-h-[360px]">
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

                    <PaneVisibility active={activeTab} tab="preview">
                        <PromptPreviewPane result={previewResult} loading={previewLoading} />
                    </PaneVisibility>

                    <PaneVisibility active={activeTab} tab="stream">
                        {streamPane ?? <PromptPaneShell title="LLM 输出"><div className="p-4 text-sm text-gray-400">尚未提供流式输出面板。</div></PromptPaneShell>}
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
