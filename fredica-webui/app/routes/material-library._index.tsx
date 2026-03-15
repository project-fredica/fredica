import { useState, useEffect, useCallback } from "react";
import { RefreshCw } from "lucide-react";
import { useSearchParams } from "react-router";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { print_error, reportHttpError } from "~/util/error_handler";
import { BilibiliAiConclusionModal } from "~/components/bilibili/BilibiliAiConclusionModal";
import { BilibiliSubtitleModal } from "~/components/bilibili/BilibiliSubtitleModal";
import { type WebenSource } from "~/util/weben";
import {
    type MaterialVideo, type MaterialCategory, type MaterialTask,
    POLL_INTERVAL_MS, PAGE_SIZE,
} from "~/components/material-library/materialTypes";
import { MaterialCategoryPanel } from "~/components/material-library/MaterialCategoryPanel";
import { MaterialVideoRow } from "~/components/material-library/MaterialVideoRow";
import { MaterialActionModal } from "~/components/material-library/MaterialActionModal";

const SOURCE_PAGE_SIZE = 5;

export default function LibraryPage() {
    const [searchParams, setSearchParams] = useSearchParams();
    const taskIdParam = searchParams.get('task_id') ?? '';

    const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(null);
    const [page, setPage] = useState(1);
    const [refreshing, setRefreshing] = useState(false);

    const [videos, setVideos] = useState<MaterialVideo[] | null>(null);
    const [categories, setCategories] = useState<MaterialCategory[] | null>(null);
    const [downloadStatusMap, setDownloadStatusMap] = useState<Record<string, boolean>>({});
    const [videosLoading, setVideosLoading] = useState(true);
    const [categoriesLoading, setCategoriesLoading] = useState(true);
    const [videosError, setVideosError] = useState<Error | null>(null);

    const [deletingVideoIds, setDeletingVideoIds] = useState<Set<string>>(new Set());
    const [deletingCategoryIds, setDeletingCategoryIds] = useState<Set<string>>(new Set());

    const [actionTarget, setActionTarget] = useState<MaterialVideo | null>(null);
    const [aiConclusionTarget, setAiConclusionTarget] = useState<{ bvid: string; pageIndex: number } | null>(null);
    const [subtitleTarget, setSubtitleTarget] = useState<{ bvid: string; pageIndex: number } | null>(null);
    const [actionTab, setActionTab] = useState<'workflow' | 'info' | 'weben'>('workflow');
    const [runningTaskType, setRunningTaskType] = useState<string | null>(null);

    const [webenSources, setWebenSources] = useState<WebenSource[]>([]);
    const [webenSourcesTotal, setWebenSourcesTotal] = useState(0);
    const [webenSourcePage, setWebenSourcePage] = useState(1);
    const [webenSourcesLoading, setWebenSourcesLoading] = useState(false);

    const [newCategoryName, setNewCategoryName] = useState('');
    const [creatingCategory, setCreatingCategory] = useState(false);

    const [tasksMap, setTasksMap] = useState<Map<string, MaterialTask[]>>(new Map());

    const [taskFilterMaterialId, setTaskFilterMaterialId] = useState<string | null | undefined>(
        taskIdParam ? undefined : null
    );

    const { apiFetch } = useAppFetch();

    const fetchData = useCallback(async () => {
        try {
            const [videosResp, categoriesResp] = await Promise.all([
                apiFetch('/api/v1/MaterialListRoute', { method: 'POST', body: '{}' }, { timeout: 15_000, silent: true }),
                apiFetch('/api/v1/MaterialCategoryListRoute', { method: 'POST', body: '{}' }, { silent: true }),
            ]);
            const fetchedVideos = videosResp.data as MaterialVideo[];
            setVideos(fetchedVideos);
            setVideosError(null);
            setCategories(categoriesResp.data as MaterialCategory[]);

            const bilibiliIds = fetchedVideos.filter(v => v.source_type === 'bilibili').map(v => v.id);
            if (bilibiliIds.length > 0) {
                const { resp: dsResp, data: dsData } = await apiFetch(
                    '/api/v1/MaterialDownloadStatusRoute',
                    { method: 'POST', body: JSON.stringify({ material_ids: bilibiliIds }) },
                    { silent: true },
                );
                if (dsResp.ok) setDownloadStatusMap(dsData as Record<string, boolean>);
            }
        } catch (err) {
            setVideosError(err instanceof Error ? err : new Error(String(err)));
        } finally {
            setVideosLoading(false);
            setCategoriesLoading(false);
        }
    }, [apiFetch]);

    useEffect(() => {
        fetchData();
        const id = setInterval(fetchData, POLL_INTERVAL_MS);
        return () => clearInterval(id);
    }, [fetchData]);

    useEffect(() => {
        if (!taskIdParam) { setTaskFilterMaterialId(null); return; }
        setTaskFilterMaterialId(undefined);
        const params = new URLSearchParams({ id: taskIdParam, page_size: '1' });
        apiFetch(`/api/v1/WorkerTaskListRoute?${params.toString()}`, { method: 'GET' }, { silent: true })
            .then(({ data }) => {
                const result = data as { items: Array<{ material_id: string }>; total: number };
                setTaskFilterMaterialId(result?.items?.[0]?.material_id ?? null);
            })
            .catch(() => setTaskFilterMaterialId(null));
    }, [taskIdParam, apiFetch]);

    useEffect(() => { setPage(1); }, [selectedCategoryId]);

    const handleManualRefresh = async () => {
        setRefreshing(true);
        try { await fetchData(); } finally { setRefreshing(false); }
    };

    const loadTasksForMaterial = async (materialId: string) => {
        if (tasksMap.has(materialId)) return;
        try {
            const { resp, data } = await apiFetch('/api/v1/MaterialTaskListRoute', {
                method: 'POST',
                body: JSON.stringify({ material_id: materialId }),
            });
            if (resp.ok) {
                setTasksMap(prev => new Map(prev).set(materialId, data as MaterialTask[]));
            } else {
                reportHttpError('加载任务状态失败', resp);
            }
        } catch (err) {
            print_error({ reason: '加载任务状态失败', err });
        }
    };

    const fetchWebenSources = useCallback(async (materialId: string, pg: number = 1) => {
        setWebenSourcesLoading(true);
        try {
            const params = new URLSearchParams();
            params.set('material_id', materialId);
            params.set('limit', String(SOURCE_PAGE_SIZE));
            params.set('offset', String((pg - 1) * SOURCE_PAGE_SIZE));
            const { data } = await apiFetch(`/api/v1/WebenSourceListRoute?${params}`, { method: 'GET' }, { silent: true });
            const d = data as { items: WebenSource[]; total: number } | null;
            if (d?.items) { setWebenSources(d.items); setWebenSourcesTotal(d.total); }
        } catch { /* silent */ } finally {
            setWebenSourcesLoading(false);
        }
    }, [apiFetch]);

    useEffect(() => {
        if (!actionTarget) return;
        fetchWebenSources(actionTarget.id, webenSourcePage);
        const id = setInterval(() => fetchWebenSources(actionTarget.id, webenSourcePage), 5_000);
        return () => clearInterval(id);
    }, [actionTarget, fetchWebenSources, webenSourcePage]);

    // ── Handlers ─────────────────────────────────────────────────────────────

    const handleDeleteVideo = async (id: string) => {
        setDeletingVideoIds(prev => new Set([...prev, id]));
        try {
            const { resp } = await apiFetch('/api/v1/MaterialDeleteRoute', { method: 'POST', body: JSON.stringify({ ids: [id] }) });
            if (resp.ok) await fetchData();
            else reportHttpError('删除素材失败', resp);
        } finally {
            setDeletingVideoIds(prev => { const n = new Set(prev); n.delete(id); return n; });
        }
    };

    const handleDeleteCategory = async (id: string) => {
        setDeletingCategoryIds(prev => new Set([...prev, id]));
        try {
            const { resp } = await apiFetch('/api/v1/MaterialCategoryDeleteRoute', { method: 'POST', body: JSON.stringify({ id }) });
            if (resp.ok) { if (selectedCategoryId === id) setSelectedCategoryId(null); await fetchData(); }
            else reportHttpError('删除分类失败', resp);
        } finally {
            setDeletingCategoryIds(prev => { const n = new Set(prev); n.delete(id); return n; });
        }
    };

    const handleCreateCategory = async () => {
        const name = newCategoryName.trim();
        if (!name || creatingCategory) return;
        setCreatingCategory(true);
        try {
            const { resp } = await apiFetch('/api/v1/MaterialCategoryCreateRoute', { method: 'POST', body: JSON.stringify({ name }) });
            if (resp.ok) { setNewCategoryName(''); await fetchData(); }
            else reportHttpError('创建分类失败', resp);
        } finally { setCreatingCategory(false); }
    };

    const handleOpenAction = (video: MaterialVideo) => {
        setActionTarget(video);
        setRunningTaskType(null);
        setWebenSources([]);
        setWebenSourcesTotal(0);
        setWebenSourcePage(1);
    };

    const handleCloseAction = () => {
        setActionTarget(null);
        setActionTab('workflow');
        setRunningTaskType(null);
        setWebenSources([]);
        setWebenSourcesTotal(0);
        setWebenSourcePage(1);
    };

    const handleRunTask = async (taskType: string) => {
        if (!actionTarget || runningTaskType) return;
        setRunningTaskType(taskType);
        const routeMap: Record<string, string> = {
            'DOWNLOAD_BILIBILI_VIDEO': '/api/v1/BilibiliVideoDownloadRoute',
            'TRANSCODE_MP4': '/api/v1/BilibiliVideoMaterialTranscodeMp4Route',
        };
        const route = routeMap[taskType];
        if (!route) { setRunningTaskType(null); return; }
        try {
            const { resp } = await apiFetch(route, { method: 'POST', body: JSON.stringify({ material_id: actionTarget.id }) });
            if (!resp.ok) reportHttpError('提交任务失败', resp);
        } finally { setRunningTaskType(null); }
    };

    const handleStartWorkflow = async (template: string) => {
        if (!actionTarget || runningTaskType) return;
        setRunningTaskType(template);
        try {
            const { resp } = await apiFetch('/api/v1/WorkflowRunStartRoute', { method: 'POST', body: JSON.stringify({ material_id: actionTarget.id, template }) });
            if (!resp.ok) reportHttpError('启动工作流失败', resp);
        } finally { setRunningTaskType(null); }
    };

    const buildAnalyzeUrl = (video: MaterialVideo): string => {
        return `/weben-analyze/${video.id}`;
    };

    // ── Filtering & pagination ────────────────────────────────────────────────

    const filtered = (videos ?? []).filter(v => {
        if (selectedCategoryId && !v.category_ids.includes(selectedCategoryId)) return false;
        if (taskIdParam && taskFilterMaterialId !== null && taskFilterMaterialId !== undefined) {
            if (v.id !== taskFilterMaterialId) return false;
        }
        if (taskIdParam && taskFilterMaterialId === null) return false;
        return true;
    });

    const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
    const pagedVideos = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

    const categoryExists = categories?.some(c => c.id === selectedCategoryId) ?? true;
    const effectiveCategoryId = categoryExists ? selectedCategoryId : null;

    // ── Render ────────────────────────────────────────────────────────────────

    return (
        <>
            {aiConclusionTarget && (
                <BilibiliAiConclusionModal
                    bvid={aiConclusionTarget.bvid}
                    pageIndex={aiConclusionTarget.pageIndex}
                    onClose={() => setAiConclusionTarget(null)}
                />
            )}
            {subtitleTarget && (
                <BilibiliSubtitleModal
                    bvid={subtitleTarget.bvid}
                    pageIndex={subtitleTarget.pageIndex}
                    onClose={() => setSubtitleTarget(null)}
                />
            )}
            {actionTarget && (
                <MaterialActionModal
                    actionTarget={actionTarget}
                    actionTab={actionTab}
                    onTabChange={setActionTab}
                    onClose={handleCloseAction}
                    workflow={{
                        materialId: actionTarget.id,
                        runningTaskType,
                        deletingVideoIds,
                        onStartWorkflow: handleStartWorkflow,
                        onRunTask: handleRunTask,
                        onDeleteVideo: handleDeleteVideo,
                    }}
                    onOpenAiConclusion={(bvid) => setAiConclusionTarget({ bvid, pageIndex: 0 })}
                    onOpenSubtitle={(bvid) => setSubtitleTarget({ bvid, pageIndex: 0 })}
                    weben={{
                        total: webenSourcesTotal,
                        sources: webenSources,
                        page: webenSourcePage,
                        loading: webenSourcesLoading,
                        onPageChange: setWebenSourcePage,
                        analyzeUrl: buildAnalyzeUrl(actionTarget),
                    }}
                />
            )}

            <SidebarLayout>
                <div className="max-w-4xl mx-auto p-4 sm:p-6 space-y-4">

                    <div className="flex items-center justify-between">
                        <div>
                            <h1 className="text-xl font-semibold text-gray-900">素材库</h1>
                            {videos && (
                                <p className="text-sm text-gray-500 mt-0.5">共 {videos.length} 个视频 · 每 {POLL_INTERVAL_MS / 1000}s 自动刷新</p>
                            )}
                        </div>
                        <button
                            onClick={handleManualRefresh}
                            disabled={refreshing}
                            className="p-2 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors disabled:opacity-50"
                            title="立即刷新"
                        >
                            <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
                        </button>
                    </div>

                    <MaterialCategoryPanel
                        categories={categories}
                        categoriesLoading={categoriesLoading}
                        videos={videos}
                        effectiveCategoryId={effectiveCategoryId}
                        deletingCategoryIds={deletingCategoryIds}
                        newCategoryName={newCategoryName}
                        creatingCategory={creatingCategory}
                        taskFilterMaterialId={taskFilterMaterialId}
                        onSelectCategory={setSelectedCategoryId}
                        onDeleteCategory={handleDeleteCategory}
                        onNewCategoryNameChange={setNewCategoryName}
                        onCreateCategory={handleCreateCategory}
                    />

                    {videosLoading && (
                        <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-400">
                            加载中…
                        </div>
                    )}
                    {videosError && !videosLoading && (
                        <div className="bg-white rounded-lg border border-gray-200 p-4">
                            <p className="text-sm text-red-600">{videosError.message}</p>
                        </div>
                    )}
                    {!videosLoading && !videosError && filtered.length === 0 && (
                        <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-400">
                            {!videos || videos.length === 0
                                ? '素材库为空，前往收藏夹浏览并加入素材吧'
                                : '该筛选条件下暂无素材'}
                        </div>
                    )}

                    {filtered.length > 0 && (
                        <div className="bg-white rounded-lg border border-gray-200 divide-y divide-gray-100">
                            {pagedVideos.map(video => (
                                <div key={video.id} onMouseEnter={() => loadTasksForMaterial(video.id)}>
                                    <MaterialVideoRow
                                        video={video}
                                        categories={categories}
                                        downloadStatusMap={downloadStatusMap}
                                        tasksMap={tasksMap}
                                        deletingVideoIds={deletingVideoIds}
                                        onOpenAction={handleOpenAction}
                                        onOpenActionWeben={(v) => { handleOpenAction(v); setActionTab('weben'); }}
                                        onOpenAiConclusion={(bvid, pageIndex) => setAiConclusionTarget({ bvid, pageIndex })}
                                        onSelectCategory={setSelectedCategoryId}
                                    />
                                </div>
                            ))}
                        </div>
                    )}

                    {totalPages > 1 && (
                        <div className="flex items-center justify-center gap-3">
                            <button
                                onClick={() => setPage(p => Math.max(1, p - 1))}
                                disabled={page <= 1}
                                className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                            >
                                ← 上一页
                            </button>
                            <span className="text-xs text-gray-500 tabular-nums">
                                第 {page} / {totalPages} 页（共 {filtered.length} 个）
                            </span>
                            <button
                                onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                                disabled={page >= totalPages}
                                className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                            >
                                下一页 →
                            </button>
                        </div>
                    )}
                </div>
            </SidebarLayout>
        </>
    );
}
