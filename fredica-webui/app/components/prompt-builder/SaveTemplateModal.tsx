import { useState } from "react";
import { X, Loader2, Save } from "lucide-react";
import type { ApiFetchFn } from "~/util/materialWebenApi";
import type { PromptTemplateListItem } from "~/util/prompt-builder/promptTemplateApi";
import { savePromptTemplate } from "~/util/prompt-builder/promptTemplateApi";
import { print_error } from "~/util/error_handler";

interface SaveTemplateModalProps {
    /** 当前编辑器中的脚本内容。 */
    scriptCode: string;
    /** 上一次从模板选择器加载的模板；为 null 表示从头编写。 */
    loadedFromTemplate: PromptTemplateListItem | null;
    apiFetch: ApiFetchFn;
    /** 保存成功后回调，传入已保存模板的 id。 */
    onSaved: (savedId: string) => void;
    onClose: () => void;
}

export function SaveTemplateModal({
    scriptCode,
    loadedFromTemplate,
    apiFetch,
    onSaved,
    onClose,
}: SaveTemplateModalProps) {
    // 只有加载来源是用户模板时才能"覆盖"；系统模板只能另存
    const canOverwrite = !!loadedFromTemplate && loadedFromTemplate.source_type === "user";
    const [mode, setMode] = useState<"overwrite" | "new">(canOverwrite ? "overwrite" : "new");
    const [name, setName] = useState(loadedFromTemplate?.name ?? "");
    const [description, setDescription] = useState(loadedFromTemplate?.description ?? "");
    const [category, setCategory] = useState(loadedFromTemplate?.category ?? "weben_extract");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const handleSubmit = async (event: React.FormEvent) => {
        event.preventDefault();
        const trimmedName = name.trim();
        if (!trimmedName) {
            setError("模板名称不能为空");
            return;
        }

        setSaving(true);
        setError(null);
        try {
            const id = mode === "overwrite" && loadedFromTemplate
                ? loadedFromTemplate.id
                : crypto.randomUUID();

            const saved = await savePromptTemplate(apiFetch, {
                id,
                name: trimmedName,
                description: description.trim(),
                category: category.trim(),
                script_code: scriptCode,
                // 另存时记录来源；覆盖时保持原模板自身的关联不变（不传）
                based_on_template_id: mode === "new" && loadedFromTemplate
                    ? loadedFromTemplate.id
                    : undefined,
            });

            if (!saved) {
                setError("保存失败，请重试");
                return;
            }
            onSaved(saved.id);
            onClose();
        } catch (err) {
            print_error({ reason: "保存模板异常", err });
            setError("保存时发生异常");
        } finally {
            setSaving(false);
        }
    };

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-sm"
            onClick={e => e.target === e.currentTarget && onClose()}
        >
            <div className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4">

                {/* Header */}
                <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
                    <h2 className="text-base font-semibold text-gray-900">保存为模板</h2>
                    <button
                        type="button"
                        onClick={onClose}
                        className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors"
                    >
                        <X className="w-4 h-4 text-gray-500" />
                    </button>
                </div>

                <form onSubmit={e => void handleSubmit(e)} className="px-5 py-4 space-y-4">
                    {/* 覆盖 / 另存 切换（仅在来源为用户模板时显示） */}
                    {canOverwrite ? (
                        <div className="flex gap-1 p-1 rounded-lg bg-gray-100 text-sm">
                            <button
                                type="button"
                                onClick={() => setMode("overwrite")}
                                className={`flex-1 py-1.5 rounded-md font-medium transition-colors ${
                                    mode === "overwrite" ? "bg-white text-gray-900 shadow-sm" : "text-gray-500 hover:text-gray-700"
                                }`}
                            >
                                覆盖当前模板
                            </button>
                            <button
                                type="button"
                                onClick={() => setMode("new")}
                                className={`flex-1 py-1.5 rounded-md font-medium transition-colors ${
                                    mode === "new" ? "bg-white text-gray-900 shadow-sm" : "text-gray-500 hover:text-gray-700"
                                }`}
                            >
                                另存为新模板
                            </button>
                        </div>
                    ) : null}

                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-1">
                            名称 <span className="text-red-500">*</span>
                        </label>
                        <input
                            type="text"
                            value={name}
                            onChange={e => setName(e.target.value)}
                            placeholder="输入模板名称…"
                            className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg outline-none focus:ring-2 focus:ring-violet-400 focus:border-transparent"
                            autoFocus
                        />
                    </div>

                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-1">描述</label>
                        <input
                            type="text"
                            value={description}
                            onChange={e => setDescription(e.target.value)}
                            placeholder="可选，简短说明模板用途…"
                            className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg outline-none focus:ring-2 focus:ring-violet-400 focus:border-transparent"
                        />
                    </div>

                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-1">分类</label>
                        <input
                            type="text"
                            value={category}
                            onChange={e => setCategory(e.target.value)}
                            placeholder="例如 weben_extract"
                            className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg outline-none focus:ring-2 focus:ring-violet-400 focus:border-transparent"
                        />
                    </div>

                    {error ? (
                        <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
                            {error}
                        </div>
                    ) : null}

                    <div className="flex gap-2 pt-1">
                        <button
                            type="button"
                            onClick={onClose}
                            className="flex-1 px-4 py-2 text-sm font-medium text-gray-600 bg-gray-50 border border-gray-200 rounded-lg hover:bg-gray-100 transition-colors"
                        >
                            取消
                        </button>
                        <button
                            type="submit"
                            disabled={saving || !name.trim()}
                            className="flex-1 inline-flex items-center justify-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-violet-600 rounded-lg hover:bg-violet-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                        >
                            {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                            保存
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
