import { Loader, Plus, X } from "lucide-react";
import { useSearchParams } from "react-router";
import { type MaterialCategory, type MaterialVideo } from "./materialTypes";

export function MaterialCategoryPanel({
    categories,
    categoriesLoading,
    videos,
    effectiveCategoryId,
    deletingCategoryIds,
    newCategoryName,
    creatingCategory,
    taskFilterMaterialId,
    onSelectCategory,
    onDeleteCategory,
    onNewCategoryNameChange,
    onCreateCategory,
}: {
    categories: MaterialCategory[] | null;
    categoriesLoading: boolean;
    videos: MaterialVideo[] | null;
    effectiveCategoryId: string | null;
    deletingCategoryIds: Set<string>;
    newCategoryName: string;
    creatingCategory: boolean;
    taskFilterMaterialId: string | null | undefined;
    onSelectCategory: (id: string | null) => void;
    onDeleteCategory: (id: string) => void;
    onNewCategoryNameChange: (name: string) => void;
    onCreateCategory: () => void;
}) {
    const [searchParams, setSearchParams] = useSearchParams();
    const taskIdParam = searchParams.get('task_id') ?? '';

    return (
        <div className="bg-white rounded-lg border border-gray-200 px-4 py-3 space-y-3">
            <div className="flex items-center justify-between">
                <span className="text-sm font-medium text-gray-700">分类</span>
                {categoriesLoading && <Loader className="w-3.5 h-3.5 animate-spin text-gray-400" />}
            </div>

            {/* Category filter pills */}
            <div className="flex flex-wrap gap-2">
                <button
                    onClick={() => onSelectCategory(null)}
                    className={`px-3 py-1 text-xs font-medium rounded-full transition-colors ${!effectiveCategoryId
                        ? 'bg-blue-600 text-white'
                        : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                    }`}
                >
                    全部{videos ? ` (${videos.length})` : ''}
                </button>
                {(categories ?? []).map(cat => {
                    const isActive = effectiveCategoryId === cat.id;
                    const isDeleting = deletingCategoryIds.has(cat.id);
                    return (
                        <span
                            key={cat.id}
                            className={`inline-flex items-center gap-1 pl-3 pr-1.5 py-1 text-xs font-medium rounded-full transition-colors ${isActive
                                ? 'bg-blue-600 text-white'
                                : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                            } ${isDeleting ? 'opacity-50' : ''}`}
                        >
                            <button onClick={() => onSelectCategory(isActive ? null : cat.id)} className="leading-none">
                                {cat.name} ({cat.material_count})
                            </button>
                            <button
                                onClick={() => onDeleteCategory(cat.id)}
                                disabled={isDeleting}
                                className={`rounded-full p-0.5 transition-colors ${isActive ? 'hover:bg-blue-500' : 'hover:bg-gray-300'} disabled:cursor-not-allowed`}
                                title={`删除分类「${cat.name}」`}
                            >
                                <X className="w-3 h-3" />
                            </button>
                        </span>
                    );
                })}
            </div>

            {/* New category input */}
            <div className="flex gap-2 pt-1">
                <input
                    type="text"
                    value={newCategoryName}
                    onChange={e => onNewCategoryNameChange(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && onCreateCategory()}
                    placeholder="新建分类名称…"
                    className="flex-1 px-3 py-1.5 text-sm border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                />
                <button
                    onClick={onCreateCategory}
                    disabled={!newCategoryName.trim() || creatingCategory}
                    className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    {creatingCategory ? <Loader className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
                    创建
                </button>
            </div>

            {/* Task ID filter */}
            <div className="flex gap-2 pt-1 border-t border-gray-100">
                <input
                    type="text"
                    value={taskIdParam}
                    onChange={e => {
                        setSearchParams(prev => {
                            const n = new URLSearchParams(prev);
                            if (e.target.value) n.set('task_id', e.target.value);
                            else n.delete('task_id');
                            return n;
                        });
                    }}
                    placeholder="按任务 ID 筛选素材…"
                    className="flex-1 px-3 py-1.5 text-sm border border-gray-200 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none"
                />
                {taskIdParam && (
                    <button
                        onClick={() => setSearchParams(prev => { const n = new URLSearchParams(prev); n.delete('task_id'); return n; })}
                        className="flex items-center gap-1 px-3 py-1.5 text-sm text-gray-500 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors"
                    >
                        <X className="w-3.5 h-3.5" />
                        清除
                    </button>
                )}
            </div>
            {taskIdParam && (
                <p className="text-xs text-purple-600 mt-0.5">
                    {taskFilterMaterialId === undefined
                        ? '正在解析任务…'
                        : taskFilterMaterialId === null
                            ? '未找到该任务对应的素材'
                            : `已筛选至素材：${taskFilterMaterialId}`
                    }
                </p>
            )}
        </div>
    );
}
