import { useState, useEffect } from "react";
import { Loader, Plus, X, ChevronDown, ChevronRight, RefreshCw, Trash2, Pencil, ExternalLink } from "lucide-react";
import { WorkflowInfoPanel } from "~/components/ui/WorkflowInfoPanel";
import { useSearchParams, useNavigate } from "react-router";
import {
    type MaterialCategory, type MaterialVideo, type GroupedCategories,
    groupCategories, formatRelativeTime, getSyncDisplayLabel,
    SYNC_STATE_LABELS, SYNC_TYPE_LABELS,
} from "./materialTypes";

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
    onSyncTrigger,
    onRenameCategory,
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
    onSyncTrigger?: (platformInfoId: string) => void;
    onRenameCategory?: (id: string, newName: string) => void;
}) {
    const [searchParams, setSearchParams] = useSearchParams();
    const taskIdParam = searchParams.get('task_id') ?? '';
    const navigate = useNavigate();

    const [expandedSyncId, setExpandedSyncId] = useState<string | null>(null);
    const [renamingCategory, setRenamingCategory] = useState<{ id: string; name: string } | null>(null);
    const [renameInput, setRenameInput] = useState("");

    useEffect(() => {
        if (renamingCategory) setRenameInput(renamingCategory.name);
    }, [renamingCategory]);

    const grouped: GroupedCategories = categories ? groupCategories(categories) : { mine: [], publicOthers: [], synced: [] };

    const handleRenameSubmit = () => {
        const trimmed = renameInput.trim();
        if (!trimmed || !renamingCategory) return;
        onRenameCategory?.(renamingCategory.id, trimmed);
        setRenamingCategory(null);
    };

    return (
        <div className="bg-white rounded-lg border border-gray-200 px-4 py-3 space-y-3">
            <div className="flex items-center justify-between">
                <span className="text-sm font-medium text-gray-700">分类</span>
                {categoriesLoading && <Loader className="w-3.5 h-3.5 animate-spin text-gray-400" />}
            </div>

            {/* ── 我的分类 ── */}
            <div>
                <p className="text-xs text-gray-400 mb-1.5">我的分类</p>
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
                    <button
                        onClick={() => onSelectCategory('__uncategorized__')}
                        className={`px-3 py-1 text-xs font-medium rounded-full transition-colors ${effectiveCategoryId === '__uncategorized__'
                            ? 'bg-blue-600 text-white'
                            : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                        }`}
                    >
                        未分类{videos ? ` (${videos.filter(v => v.category_ids.length === 0).length})` : ''}
                    </button>
                    {grouped.mine.map(cat => (
                        <CategoryPill
                            key={cat.id}
                            cat={cat}
                            isActive={effectiveCategoryId === cat.id}
                            isDeleting={deletingCategoryIds.has(cat.id)}
                            showDelete
                            onSelect={() => onSelectCategory(effectiveCategoryId === cat.id ? null : cat.id)}
                            onDelete={() => onDeleteCategory(cat.id)}
                            onRename={() => setRenamingCategory({ id: cat.id, name: cat.name })}
                        />
                    ))}
                </div>
            </div>

            {/* ── 公开分类 ── */}
            {grouped.publicOthers.length > 0 && (
                <div>
                    <p className="text-xs text-gray-400 mb-1.5">公开分类</p>
                    <div className="flex flex-wrap gap-2">
                        {grouped.publicOthers.map(cat => (
                            <CategoryPill
                                key={cat.id}
                                cat={cat}
                                isActive={effectiveCategoryId === cat.id}
                                isDeleting={deletingCategoryIds.has(cat.id)}
                                showDelete={false}
                                onSelect={() => onSelectCategory(effectiveCategoryId === cat.id ? null : cat.id)}
                                onDelete={() => {}}
                            />
                        ))}
                    </div>
                </div>
            )}

            {/* ── 同步信源 ── */}
            <div>
                <p className="text-xs text-gray-400 mb-1.5">同步信源</p>
                {grouped.synced.length > 0 ? (
                    <>
                        <div className="flex flex-wrap gap-2">
                            {grouped.synced.map(cat => (
                                <SyncCategoryPill
                                    key={cat.id}
                                    cat={cat}
                                    isActive={effectiveCategoryId === cat.id}
                                    isDeleting={deletingCategoryIds.has(cat.id)}
                                    isExpanded={expandedSyncId === cat.id}
                                    onSelect={() => onSelectCategory(effectiveCategoryId === cat.id ? null : cat.id)}
                                    onToggleExpand={() => setExpandedSyncId(expandedSyncId === cat.id ? null : cat.id)}
                                    onSyncTrigger={() => cat.sync && onSyncTrigger?.(cat.sync.id)}
                                />
                            ))}
                        </div>
                        {expandedSyncId && (() => {
                            const syncCat = grouped.synced.find(c => c.id === expandedSyncId);
                            if (!syncCat?.sync) return null;
                            return (
                                <SyncDetailPanel
                                    cat={syncCat}
                                    onSyncTrigger={onSyncTrigger}
                                    onDeleteSource={() => onDeleteCategory(syncCat.id)}
                                />
                            );
                        })()}
                    </>
                ) : (
                    <p className="text-xs text-gray-400 py-1">暂无同步信源，可在「添加资源」页面创建</p>
                )}
            </div>

            {/* ── New category input ── */}
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

            {/* ── Task ID filter ── */}
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

            {/* ── Rename modal ── */}
            {renamingCategory && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-sm" onClick={() => setRenamingCategory(null)}>
                    <div className="bg-white rounded-lg shadow-xl border border-gray-200 p-5 w-full max-w-sm mx-4" onClick={e => e.stopPropagation()}>
                        <h3 className="text-sm font-medium text-gray-900 mb-3">重命名分类</h3>
                        <input
                            type="text"
                            value={renameInput}
                            onChange={e => setRenameInput(e.target.value)}
                            onKeyDown={e => e.key === 'Enter' && handleRenameSubmit()}
                            autoFocus
                            className="w-full px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                        />
                        <div className="flex justify-end gap-2 mt-4">
                            <button
                                onClick={() => setRenamingCategory(null)}
                                className="px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 rounded-lg transition-colors"
                            >
                                取消
                            </button>
                            <button
                                onClick={handleRenameSubmit}
                                disabled={!renameInput.trim() || renameInput.trim() === renamingCategory.name}
                                className="px-3 py-1.5 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                确认
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}

function CategoryPill({
    cat, isActive, isDeleting, showDelete, onSelect, onDelete, onRename,
}: {
    cat: MaterialCategory;
    isActive: boolean;
    isDeleting: boolean;
    showDelete: boolean;
    onSelect: () => void;
    onDelete: () => void;
    onRename?: () => void;
}) {
    return (
        <button
            onClick={onSelect}
            className={`inline-flex items-center gap-1 ${showDelete ? 'pl-3 pr-1' : 'px-3'} py-1 text-xs font-medium rounded-full transition-colors ${isActive
                ? 'bg-blue-600 text-white'
                : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
            } ${isDeleting ? 'opacity-50' : ''}`}
        >
            <span className="leading-none">
                {cat.name} ({cat.material_count})
            </span>
            {onRename && (
                <span
                    role="button"
                    onClick={e => { e.stopPropagation(); onRename(); }}
                    className={`rounded-full p-0.5 transition-colors ${isActive ? 'hover:bg-blue-500' : 'hover:bg-gray-300'}`}
                    title={`重命名分类「${cat.name}」`}
                >
                    <Pencil className="w-3 h-3" />
                </span>
            )}
            {showDelete && (
                <span
                    role="button"
                    onClick={e => { e.stopPropagation(); onDelete(); }}
                    aria-disabled={isDeleting}
                    className={`rounded-full p-0.5 transition-colors ${isActive ? 'hover:bg-blue-500' : 'hover:bg-gray-300'} ${isDeleting ? 'cursor-not-allowed' : ''}`}
                    title={`删除分类「${cat.name}」`}
                >
                    <X className="w-3 h-3" />
                </span>
            )}
        </button>
    );
}

function SyncCategoryPill({
    cat, isActive, isDeleting, isExpanded, onSelect, onToggleExpand, onSyncTrigger,
}: {
    cat: MaterialCategory;
    isActive: boolean;
    isDeleting: boolean;
    isExpanded: boolean;
    onSelect: () => void;
    onToggleExpand: () => void;
    onSyncTrigger: () => void;
}) {
    const sync = cat.sync!;
    const stateInfo = SYNC_STATE_LABELS[sync.sync_state];

    return (
        <button
            onClick={onSelect}
            className={`inline-flex items-center gap-1.5 pl-3 pr-1.5 py-1 text-xs font-medium rounded-full transition-colors ${isActive
                ? 'bg-blue-600 text-white'
                : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
            } ${isDeleting ? 'opacity-50' : ''}`}
        >
            <span className="leading-none">
                {getSyncDisplayLabel(sync)} ({cat.material_count})
            </span>
            {stateInfo && sync.sync_state !== 'idle' && (
                <span className={`px-1.5 py-0.5 text-[10px] rounded-full ${isActive ? 'bg-blue-500 text-white' : stateInfo.className}`}>
                    {stateInfo.label}
                </span>
            )}
            {sync.sync_state === 'idle' && sync.last_synced_at && (
                <span className={`text-[10px] ${isActive ? 'text-blue-200' : 'text-gray-400'}`}>
                    {formatRelativeTime(sync.last_synced_at)}
                </span>
            )}
            <span
                role="button"
                onClick={e => { e.stopPropagation(); onSyncTrigger(); }}
                className={`p-2 -m-0.5 rounded-full transition-colors ${isActive ? 'hover:bg-blue-500' : 'hover:bg-gray-300'}`}
                title="立即同步"
            >
                <RefreshCw className={`w-4 h-4 ${sync.sync_state === 'syncing' ? 'animate-spin' : ''}`} />
            </span>
            <span
                role="button"
                onClick={e => { e.stopPropagation(); onToggleExpand(); }}
                className={`p-2 -m-0.5 rounded-full transition-colors ${isActive ? 'hover:bg-blue-500' : 'hover:bg-gray-300'}`}
            >
                {isExpanded
                    ? <ChevronDown className="w-4 h-4" />
                    : <ChevronRight className="w-4 h-4" />
                }
            </span>
        </button>
    );
}

function SyncDetailPanel({ cat, onSyncTrigger, onDeleteSource }: {
    cat: MaterialCategory;
    onSyncTrigger?: (platformInfoId: string) => void;
    onDeleteSource?: () => void;
}) {
    const navigate = useNavigate();
    const sync = cat.sync!;
    const typeLabel = SYNC_TYPE_LABELS[sync.sync_type] ?? sync.sync_type;
    const stateInfo = SYNC_STATE_LABELS[sync.sync_state];

    return (
        <div className="mt-2 p-3 bg-gray-50 rounded-lg border border-gray-200 text-xs space-y-2">
            <div className="flex items-center justify-between">
                <span className="font-medium text-gray-700">{getSyncDisplayLabel(sync)}</span>
                {stateInfo && (
                    <span className={`px-2 py-0.5 rounded-full ${stateInfo.className}`}>
                        {stateInfo.label}
                    </span>
                )}
            </div>
            <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-gray-500">
                <span>类型：{typeLabel}</span>
                <span>素材数：{sync.item_count}</span>
                <span>订阅者：{sync.subscriber_count}</span>
                {sync.last_synced_at && <span>上次同步：{formatRelativeTime(sync.last_synced_at)}</span>}
            </div>
            {sync.sync_state === 'failed' && sync.last_error && (
                <p className="text-red-600">错误：{sync.last_error}（失败 {sync.fail_count} 次）</p>
            )}
            {sync.my_subscription && (
                <div className="text-gray-500">
                    订阅：{sync.my_subscription.enabled ? '已启用' : '已暂停'}
                    {sync.my_subscription.cron_expr && ` · ${sync.my_subscription.cron_expr}`}
                </div>
            )}
            {sync.last_workflow_run_id && sync.sync_state === 'syncing' && (
                <WorkflowInfoPanel workflowRunId={sync.last_workflow_run_id} defaultExpanded />
            )}
            <div className="flex gap-2 pt-1">
                <button
                    onClick={() => onSyncTrigger?.(sync.id)}
                    className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-blue-700 bg-blue-50 rounded hover:bg-blue-100 transition-colors"
                >
                    <RefreshCw className="w-3 h-3" />
                    立即同步
                </button>
                <button
                    onClick={() => navigate(`/material-library/sync/${cat.id}`)}
                    className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-gray-600 bg-gray-100 rounded hover:bg-gray-200 transition-colors"
                >
                    <ExternalLink className="w-3 h-3" />
                    查看详情
                </button>
                {onDeleteSource && sync.owner_id === cat.owner_id && (
                    <button
                        onClick={onDeleteSource}
                        className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-red-600 bg-red-50 rounded hover:bg-red-100 transition-colors"
                    >
                        <Trash2 className="w-3 h-3" />
                        删除数据源
                    </button>
                )}
            </div>
        </div>
    );
}
