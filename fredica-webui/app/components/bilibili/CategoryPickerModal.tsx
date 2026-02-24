import { useEffect, useState } from "react";
import { X, Plus, Loader } from "lucide-react";
import { useAppConfig } from "~/context/appConfig";

export interface Category {
    id: string;
    name: string;
    video_count: number;
}

interface CategoryPickerModalProps {
    /** How many videos are about to be imported (ignored in edit mode) */
    videoCount: number;
    onConfirm: (categoryIds: string[]) => void;
    onCancel: () => void;
    /** Category IDs already assigned to this video — pre-checked when modal opens */
    existingCategoryIds?: string[];
    /** When true, changes copy from "加入" to "修改分类" */
    isEditMode?: boolean;
}

/** Double-parse helper — mirrors useAppFetch behaviour for ValidJsonString responses */
async function parseApiResponse(resp: Response): Promise<unknown> {
    let data = await resp.json();
    while (typeof data === "string") {
        try { data = JSON.parse(data); } catch { break; }
    }
    return data;
}

export function CategoryPickerModal({
    videoCount,
    onConfirm,
    onCancel,
    existingCategoryIds,
    isEditMode = false,
}: CategoryPickerModalProps) {
    const [categories, setCategories] = useState<Category[]>([]);
    const [loadingList, setLoadingList] = useState(true);
    const [selected, setSelected] = useState<Set<string>>(new Set(existingCategoryIds ?? []));
    const [newName, setNewName] = useState("");
    const [creating, setCreating] = useState(false);

    const { appConfig } = useAppConfig();
    const apiHost = `${appConfig.webserver_schema ?? "http"}://${appConfig.webserver_domain ?? "localhost"}:${appConfig.webserver_port ?? "7631"}`;
    const authHeaders: Record<string, string> = appConfig.webserver_auth_token
        ? { Authorization: `Bearer ${appConfig.webserver_auth_token}` }
        : {};

    // Fetch categories on open
    useEffect(() => {
        let cancelled = false;
        (async () => {
            setLoadingList(true);
            try {
                const resp = await fetch(`${apiHost}/api/v1/MaterialCategoryListRoute`, {
                    method: "POST",
                    headers: { "Content-Type": "application/json", ...authHeaders },
                    body: "{}",
                });
                if (!resp.ok || cancelled) return;
                const data = await parseApiResponse(resp) as Category[];
                if (!cancelled) setCategories(data);
            } finally {
                if (!cancelled) setLoadingList(false);
            }
        })();
        return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [apiHost]);

    const toggleCategory = (id: string) => {
        setSelected(prev => {
            const next = new Set(prev);
            next.has(id) ? next.delete(id) : next.add(id);
            return next;
        });
    };

    const handleCreate = async () => {
        const name = newName.trim();
        if (!name || creating) return;
        setCreating(true);
        try {
            const resp = await fetch(`${apiHost}/api/v1/MaterialCategoryCreateRoute`, {
                method: "POST",
                headers: { "Content-Type": "application/json", ...authHeaders },
                body: JSON.stringify({ name }),
            });
            if (!resp.ok) return;
            const cat = await parseApiResponse(resp) as Category;
            setCategories(prev =>
                prev.some(c => c.id === cat.id) ? prev : [...prev, { ...cat, video_count: 0 }]
            );
            setSelected(prev => new Set([...prev, cat.id]));
            setNewName("");
        } finally {
            setCreating(false);
        }
    };

    const title = isEditMode ? "修改分类" : "加入素材库";
    const subtitle = isEditMode
        ? `已选 ${selected.size} 个分类`
        : `${videoCount} 个视频 · 选择分类（可跳过）`;
    const leftBtnLabel = isEditMode ? "清除所有分类" : "不分类，直接加入";
    const rightBtnLabel = isEditMode
        ? (selected.size > 0 ? `保存（${selected.size} 个分类）` : "保存（无分类）")
        : (selected.size > 0 ? `加入 ${selected.size} 个分类` : "确认加入");

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-sm"
            onClick={(e) => e.target === e.currentTarget && onCancel()}
        >
            <div className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4 flex flex-col max-h-[80vh]">

                {/* Header */}
                <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
                    <div>
                        <h2 className="text-base font-semibold text-gray-900">{title}</h2>
                        <p className="text-xs text-gray-500 mt-0.5">{subtitle}</p>
                    </div>
                    <button
                        onClick={onCancel}
                        className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors"
                    >
                        <X className="w-4 h-4 text-gray-500" />
                    </button>
                </div>

                {/* Category list */}
                <div className="flex-1 overflow-y-auto px-5 py-3 space-y-0.5">
                    {loadingList ? (
                        <div className="py-8 text-center text-sm text-gray-400">加载分类中…</div>
                    ) : categories.length === 0 ? (
                        <div className="py-6 text-center text-sm text-gray-400">
                            暂无分类，在下方输入名称创建第一个
                        </div>
                    ) : (
                        categories.map(cat => (
                            <label
                                key={cat.id}
                                className="flex items-center gap-3 px-3 py-2.5 rounded-lg hover:bg-gray-50 cursor-pointer"
                            >
                                <input
                                    type="checkbox"
                                    checked={selected.has(cat.id)}
                                    onChange={() => toggleCategory(cat.id)}
                                    className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                                />
                                <span className="flex-1 text-sm text-gray-800">{cat.name}</span>
                                <span className="text-xs text-gray-400">{cat.video_count} 个视频</span>
                            </label>
                        ))
                    )}
                </div>

                {/* New category input */}
                <div className="px-5 py-3 border-t border-gray-100">
                    <div className="flex gap-2">
                        <input
                            type="text"
                            value={newName}
                            onChange={e => setNewName(e.target.value)}
                            onKeyDown={e => e.key === "Enter" && handleCreate()}
                            placeholder="新建分类名称…"
                            className="flex-1 px-3 py-1.5 text-sm border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                        />
                        <button
                            onClick={handleCreate}
                            disabled={!newName.trim() || creating}
                            className="flex items-center gap-1 px-3 py-1.5 text-sm font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {creating
                                ? <Loader className="w-3.5 h-3.5 animate-spin" />
                                : <Plus className="w-3.5 h-3.5" />
                            }
                            创建
                        </button>
                    </div>
                </div>

                {/* Footer */}
                <div className="flex gap-2 px-5 py-4 border-t border-gray-100">
                    <button
                        onClick={() => onConfirm([])}
                        className="flex-1 px-4 py-2 text-sm font-medium text-gray-600 bg-gray-50 border border-gray-200 rounded-lg hover:bg-gray-100 transition-colors"
                    >
                        {leftBtnLabel}
                    </button>
                    <button
                        onClick={() => onConfirm(Array.from(selected))}
                        className="flex-1 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors"
                    >
                        {rightBtnLabel}
                    </button>
                </div>
            </div>
        </div>
    );
}
