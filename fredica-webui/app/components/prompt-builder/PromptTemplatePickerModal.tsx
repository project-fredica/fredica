import { useCallback, useEffect, useState } from "react";
import { AlertTriangle, BookmarkPlus, Copy, Loader2, Trash2, X } from "lucide-react";
import type { ApiFetchFn } from "~/util/materialWebenApi";
import type { PromptTemplateListItem } from "~/util/prompt-builder/promptTemplateApi";
import {
    deletePromptTemplate,
    fetchPromptTemplateById,
    fetchPromptTemplates,
    savePromptTemplate,
} from "~/util/prompt-builder/promptTemplateApi";
import { print_error } from "~/util/error_handler";

// ── 单行组件 ─────────────────────────────────────────────────────────────────

function TemplateRow({
    template,
    busyId,
    confirmDeleteId,
    badgeClass,
    badgeLabel,
    showDelete,
    onLoad,
    onDuplicate,
    onDeleteRequest,
    onDeleteConfirm,
    onDeleteCancel,
}: {
    template: PromptTemplateListItem;
    busyId: string | null;
    confirmDeleteId: string | null;
    badgeClass: string;
    badgeLabel: string;
    showDelete: boolean;
    onLoad: () => void;
    onDuplicate: () => void;
    onDeleteRequest: () => void;
    onDeleteConfirm: () => void;
    onDeleteCancel: () => void;
}) {
    const isThisRowBusy = busyId === template.id;
    const anyBusy = busyId !== null;
    const awaitingConfirm = confirmDeleteId === template.id;

    return (
        <div className="flex items-center gap-2 px-3 py-2.5 rounded-lg border border-transparent hover:bg-gray-50 hover:border-gray-100 transition-colors">
            {/* 元数据 */}
            <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm font-medium text-gray-900 truncate">{template.name}</span>
                    <span className={`text-xs px-1.5 py-0.5 rounded-full flex-shrink-0 ${badgeClass}`}>{badgeLabel}</span>
                    {template.category ? (
                        <span className="text-xs px-1.5 py-0.5 rounded-full bg-gray-100 text-gray-500 flex-shrink-0">
                            {template.category}
                        </span>
                    ) : null}
                </div>
                {template.description ? (
                    <div className="text-xs text-gray-500 mt-0.5 truncate">{template.description}</div>
                ) : null}
                <div className="text-xs text-gray-400 font-mono mt-0.5 truncate">{template.id}</div>
            </div>

            {/* 操作区 */}
            <div className="flex items-center gap-1 flex-shrink-0">
                {isThisRowBusy ? (
                    <Loader2 className="w-4 h-4 animate-spin text-violet-500 mx-2" />
                ) : awaitingConfirm ? (
                    // 删除确认态
                    <>
                        <span className="text-xs text-red-600 mr-1">确认删除？</span>
                        <button
                            type="button"
                            onClick={onDeleteConfirm}
                            disabled={anyBusy}
                            className="px-2 py-1 text-xs font-medium text-white bg-red-500 rounded-md hover:bg-red-600 transition-colors disabled:opacity-40"
                        >
                            确认
                        </button>
                        <button
                            type="button"
                            onClick={onDeleteCancel}
                            className="px-2 py-1 text-xs text-gray-600 bg-gray-100 rounded-md hover:bg-gray-200 transition-colors"
                        >
                            取消
                        </button>
                    </>
                ) : (
                    // 正常态
                    <>
                        {showDelete ? (
                            <button
                                type="button"
                                onClick={onDeleteRequest}
                                disabled={anyBusy}
                                className="p-1.5 rounded-md text-gray-400 hover:text-red-500 hover:bg-red-50 transition-colors disabled:opacity-40"
                                title="删除"
                            >
                                <Trash2 className="w-3.5 h-3.5" />
                            </button>
                        ) : null}
                        <button
                            type="button"
                            onClick={onDuplicate}
                            disabled={anyBusy}
                            className="p-1.5 rounded-md text-gray-400 hover:text-gray-700 hover:bg-gray-100 transition-colors disabled:opacity-40"
                            title="复制为新模板"
                        >
                            <Copy className="w-3.5 h-3.5" />
                        </button>
                        <button
                            type="button"
                            onClick={onLoad}
                            disabled={anyBusy}
                            className="px-2.5 py-1 text-xs font-medium text-violet-700 bg-violet-50 rounded-md hover:bg-violet-100 transition-colors disabled:opacity-40"
                        >
                            加载
                        </button>
                    </>
                )}
            </div>
        </div>
    );
}

// ── 分组组件 ─────────────────────────────────────────────────────────────────

function TemplateGroup({
    title,
    templates,
    busyId,
    confirmDeleteId,
    badgeClass,
    badgeLabel,
    showDelete,
    onLoad,
    onDuplicate,
    onDeleteRequest,
    onDeleteConfirm,
    onDeleteCancel,
}: {
    title: string;
    templates: PromptTemplateListItem[];
    busyId: string | null;
    confirmDeleteId: string | null;
    badgeClass: string;
    badgeLabel: string;
    showDelete: boolean;
    onLoad: (t: PromptTemplateListItem) => void;
    onDuplicate: (t: PromptTemplateListItem) => void;
    onDeleteRequest: (t: PromptTemplateListItem) => void;
    onDeleteConfirm: (t: PromptTemplateListItem) => void;
    onDeleteCancel: () => void;
}) {
    return (
        <div>
            <div className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1.5">{title}</div>
            <div className="space-y-0.5">
                {templates.map(t => (
                    <TemplateRow
                        key={t.id}
                        template={t}
                        busyId={busyId}
                        confirmDeleteId={confirmDeleteId}
                        badgeClass={badgeClass}
                        badgeLabel={badgeLabel}
                        showDelete={showDelete}
                        onLoad={() => onLoad(t)}
                        onDuplicate={() => onDuplicate(t)}
                        onDeleteRequest={() => onDeleteRequest(t)}
                        onDeleteConfirm={() => onDeleteConfirm(t)}
                        onDeleteCancel={onDeleteCancel}
                    />
                ))}
            </div>
        </div>
    );
}

// ── 主组件 ────────────────────────────────────────────────────────────────────

interface PromptTemplatePickerModalProps {
    apiFetch: ApiFetchFn;
    /** 用户选中后回调，传入脚本内容与对应的列表项元数据。 */
    onSelect: (scriptCode: string, template: PromptTemplateListItem) => void;
    onClose: () => void;
    /** 当前编辑器有未保存修改时为 true，触发切换前确认流程。 */
    hasUnsavedChanges?: boolean;
    /** 有未保存修改时"另存为未命名"所需的当前脚本内容。 */
    currentScriptCode?: string;
    /** 某条用户模板被删除后的回调（用于父层联动清除 loadedTemplate）。 */
    onTemplateDeleted?: (deletedId: string) => void;
}

export function PromptTemplatePickerModal({
    apiFetch,
    onSelect,
    onClose,
    hasUnsavedChanges = false,
    currentScriptCode,
    onTemplateDeleted,
}: PromptTemplatePickerModalProps) {
    const [templates, setTemplates] = useState<PromptTemplateListItem[]>([]);
    const [listLoading, setListLoading] = useState(true);
    const [busyId, setBusyId] = useState<string | null>(null);
    const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
    /** 等待用户处理未保存修改后再加载的目标模板。 */
    const [pendingTemplate, setPendingTemplate] = useState<PromptTemplateListItem | null>(null);
    const [savingUnnamed, setSavingUnnamed] = useState(false);

    const loadList = useCallback(() => {
        let cancelled = false;
        setListLoading(true);
        fetchPromptTemplates(apiFetch)
            .then(items => { if (!cancelled) { setTemplates(items); setListLoading(false); } })
            .catch(err => { if (!cancelled) { print_error({ reason: "加载模板列表失败", err }); setListLoading(false); } });
        return () => { cancelled = true; };
    }, [apiFetch]);

    useEffect(loadList, [loadList]);

    // ── 操作处理 ──────────────────────────────────────────────────────────────

    /** 直接执行加载，不检查未保存状态。 */
    const doLoad = async (t: PromptTemplateListItem) => {
        setBusyId(t.id);
        try {
            const full = await fetchPromptTemplateById(apiFetch, t.id);
            if (full) { onSelect(full.script_code, t); onClose(); }
        } finally {
            setBusyId(null);
        }
    };

    /**
     * 用户点击"加载"时调用。
     * 若有未保存修改，先暂存目标模板并弹出确认层；否则直接加载。
     */
    const handleLoad = (t: PromptTemplateListItem) => {
        if (hasUnsavedChanges) {
            setPendingTemplate(t);
        } else {
            void doLoad(t);
        }
    };

    /** 确认层："另存为未命名"—— 保存当前脚本后再加载目标模板。 */
    const handleSaveUnnamed = async () => {
        if (!pendingTemplate) return;
        setSavingUnnamed(true);
        try {
            if (currentScriptCode) {
                const saved = await savePromptTemplate(apiFetch, {
                    id: crypto.randomUUID(),
                    name: "未命名模板",
                    script_code: currentScriptCode,
                });
                if (!saved) return; // 保存失败，savePromptTemplate 内部已上报错误
            }
            await doLoad(pendingTemplate);
        } finally {
            setSavingUnnamed(false);
            setPendingTemplate(null);
        }
    };

    const handleDuplicate = async (t: PromptTemplateListItem) => {
        setBusyId(t.id);
        try {
            const full = await fetchPromptTemplateById(apiFetch, t.id);
            if (!full) return;
            await savePromptTemplate(apiFetch, {
                id: crypto.randomUUID(),
                name: `${t.name} (副本)`,
                description: t.description,
                category: t.category,
                script_code: full.script_code,
                based_on_template_id: t.id,
            });
            // 重新加载列表以展示新副本
            const updated = await fetchPromptTemplates(apiFetch);
            setTemplates(updated);
        } finally {
            setBusyId(null);
        }
    };

    const handleDeleteConfirm = async (t: PromptTemplateListItem) => {
        setConfirmDeleteId(null);
        setBusyId(t.id);
        try {
            const ok = await deletePromptTemplate(apiFetch, t.id);
            if (ok) {
                setTemplates(prev => prev.filter(item => item.id !== t.id));
                onTemplateDeleted?.(t.id);
            }
        } finally {
            setBusyId(null);
        }
    };

    const systemTemplates = templates.filter(t => t.source_type === "system");
    const userTemplates = templates.filter(t => t.source_type === "user");

    const sharedGroupProps = {
        busyId,
        confirmDeleteId,
        onLoad: (t: PromptTemplateListItem) => handleLoad(t),
        onDuplicate: (t: PromptTemplateListItem) => void handleDuplicate(t),
        onDeleteRequest: (t: PromptTemplateListItem) => setConfirmDeleteId(t.id),
        onDeleteConfirm: (t: PromptTemplateListItem) => void handleDeleteConfirm(t),
        onDeleteCancel: () => setConfirmDeleteId(null),
    };

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-sm"
            onClick={e => e.target === e.currentTarget && onClose()}
        >
            <div className="bg-white rounded-xl shadow-xl w-full max-w-lg mx-4 flex flex-col max-h-[80vh]">

                {/* Header */}
                <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
                    <div>
                        <h2 className="text-base font-semibold text-gray-900">模板库</h2>
                        <p className="text-xs text-gray-500 mt-0.5">加载脚本 · 复制副本 · 删除用户模板</p>
                    </div>
                    <button type="button" onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors">
                        <X className="w-4 h-4 text-gray-500" />
                    </button>
                </div>

                {/* Body（relative 用于锚定未保存修改确认层） */}
                <div className="flex-1 overflow-y-auto relative">
                    <div className="px-5 py-4 space-y-5">
                        {listLoading ? (
                            <div className="py-12 flex justify-center">
                                <Loader2 className="w-5 h-5 animate-spin text-gray-400" />
                            </div>
                        ) : (
                            <>
                                {systemTemplates.length > 0 ? (
                                    <TemplateGroup
                                        title="系统模板"
                                        templates={systemTemplates}
                                        badgeClass="bg-violet-50 text-violet-700"
                                        badgeLabel="系统"
                                        showDelete={false}
                                        {...sharedGroupProps}
                                    />
                                ) : null}
                                {userTemplates.length > 0 ? (
                                    <TemplateGroup
                                        title="我的模板"
                                        templates={userTemplates}
                                        badgeClass="bg-blue-50 text-blue-700"
                                        badgeLabel="用户"
                                        showDelete={true}
                                        {...sharedGroupProps}
                                    />
                                ) : (
                                    !listLoading && systemTemplates.length > 0 ? null : (
                                        <div className="text-sm text-gray-400 py-2">
                                            暂无自定义模板，可在编辑器中另存为模板。
                                        </div>
                                    )
                                )}
                            </>
                        )}
                    </div>

                    {/* 未保存修改确认层 */}
                    {pendingTemplate !== null ? (
                        <div className="absolute inset-0 flex items-center justify-center bg-white/95 backdrop-blur-sm px-5">
                            <div className="w-full rounded-xl border border-amber-200 bg-white shadow-md p-5 space-y-4">
                                <div className="flex items-start gap-3">
                                    <AlertTriangle className="w-5 h-5 text-amber-500 flex-shrink-0 mt-0.5" />
                                    <div>
                                        <div className="text-sm font-semibold text-gray-900">当前脚本有未保存的修改</div>
                                        <div className="text-xs text-gray-500 mt-1">
                                            即将加载「{pendingTemplate.name}」，请选择如何处理当前修改。
                                        </div>
                                    </div>
                                </div>

                                <div className="space-y-2">
                                    {/* 另存为未命名 */}
                                    <button
                                        type="button"
                                        onClick={() => void handleSaveUnnamed()}
                                        disabled={savingUnnamed || !currentScriptCode}
                                        className="w-full flex items-center gap-2 px-4 py-2.5 rounded-lg border border-gray-200 bg-white text-sm text-gray-700 hover:bg-gray-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                                    >
                                        {savingUnnamed
                                            ? <Loader2 className="w-4 h-4 animate-spin flex-shrink-0" />
                                            : <BookmarkPlus className="w-4 h-4 flex-shrink-0 text-gray-500" />}
                                        <span className="font-medium">另存为未命名</span>
                                        <span className="text-xs text-gray-400 ml-auto">保存后再加载</span>
                                    </button>

                                    {/* 丢弃更改 */}
                                    <button
                                        type="button"
                                        onClick={() => { void doLoad(pendingTemplate); setPendingTemplate(null); }}
                                        disabled={savingUnnamed}
                                        className="w-full flex items-center gap-2 px-4 py-2.5 rounded-lg border border-red-100 bg-red-50 text-sm text-red-700 hover:bg-red-100 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                                    >
                                        <Trash2 className="w-4 h-4 flex-shrink-0" />
                                        <span className="font-medium">丢弃更改</span>
                                        <span className="text-xs text-red-400 ml-auto">修改将永久丢失</span>
                                    </button>

                                    {/* 返回 */}
                                    <button
                                        type="button"
                                        onClick={() => setPendingTemplate(null)}
                                        disabled={savingUnnamed}
                                        className="w-full px-4 py-2 rounded-lg text-sm text-gray-500 hover:bg-gray-50 transition-colors disabled:opacity-40"
                                    >
                                        返回
                                    </button>
                                </div>
                            </div>
                        </div>
                    ) : null}
                </div>
            </div>
        </div>
    );
}
