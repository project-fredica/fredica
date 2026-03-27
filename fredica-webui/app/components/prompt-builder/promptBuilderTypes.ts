import type { BuildPromptResult, VariableMeta, VariableResolver } from "~/util/prompt-builder/types";
import type { ApiFetchFn } from "~/util/materialWebenApi";
import type { PromptTemplateListItem } from "~/util/prompt-builder/promptTemplateApi";

export type PromptWorkbenchTab = "editor" | "settings" | "preview" | "stream" | "render";

export interface PromptWorkbenchTabItem {
    id: PromptWorkbenchTab;
    label: string;
    disabled?: boolean;
}

export interface PromptBuilderRenderProps {
    activeTab: PromptWorkbenchTab;
    previewResult: BuildPromptResult | null;
}

export interface PromptBuilderProps {
    value: string;
    onChange: (value: string) => void;
    variableResolver: VariableResolver;
    variables?: VariableMeta[];
    previewResult: BuildPromptResult | null;
    previewLoading?: boolean;
    streamPane?: React.ReactNode;
    renderPane?: React.ReactNode;
    onPreview: () => void | Promise<void>;
    onGenerate?: (signal: AbortSignal) => void | Promise<void>;
    generateDisabled?: boolean;
    renderDisabled?: boolean;
    className?: string;
    /** 在编辑器标题栏右侧、预览/生成按钮之前插入额外操作区（如模板选择、另存按钮）。 */
    editorHeaderExtra?: React.ReactNode;
    /** 当前加载的模板名称，展示在编辑器底部信息栏。 */
    currentTemplateName?: string;
    /** PromptBuilder 内部设置持久化作用域 key（不同页面/实例需不同）。 */
    settingsStorageKey: string;
    /** 当前解析出的有效模型 ID 变化时回调给外部页面。 */
    onResolvedModelChange?: (modelId: string | null) => void;
    /** 挂载时自动加载的默认模板 ID。
     * 提供后组件会在 mount 时查询该模板并填入编辑器；为空则编辑器初始内容为空。
     * 同时必须传入 apiFetch。
     */
    defaultTemplateId?: string;
    /** 提供 defaultTemplateId 时必须同时传入，用于查询模板内容。
     * PromptBuilder 也会复用它拉取模型列表与模型可用性。
     */
    apiFetch?: ApiFetchFn;
    /** defaultTemplateId 模板加载完成后的回调，可用于父层同步 loadedTemplate 等状态。 */
    onDefaultTemplateLoaded?: (scriptCode: string, template: PromptTemplateListItem) => void;
    /**
     * 注入到编辑器顶部的只读头部代码（不属于模板内容，不可编辑）。
     * 由页面根据当前上下文（如 materialId）生成，提交时前置于模板代码之前。
     */
    readonlyHeader?: string;
}
