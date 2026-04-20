import { useState, useEffect, useCallback } from "react";
import { useParams, useNavigate } from "react-router";
import { ArrowLeft, RefreshCw, Loader, Trash2, Unplug } from "lucide-react";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { useAppFetch } from "~/util/app_fetch";
import { print_error, reportHttpError } from "~/util/error_handler";
import { CronExpressionInput } from "~/components/ui/CronExpressionInput";
import { WorkflowInfoPanel } from "~/components/ui/WorkflowInfoPanel";
import {
    type MaterialCategory,
    SYNC_TYPE_LABELS, SYNC_STATE_LABELS, formatRelativeTime,
} from "~/components/material-library/materialTypes";

export default function SyncManagePage() {
    const { syncId } = useParams();
    const navigate = useNavigate();
    const { apiFetch } = useAppFetch();

    const [category, setCategory] = useState<MaterialCategory | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const [cronExpr, setCronExpr] = useState("");
    const [freshnessWindow, setFreshnessWindow] = useState("0");
    const [enabled, setEnabled] = useState(true);
    const [saving, setSaving] = useState(false);
    const [saveError, setSaveError] = useState<string | null>(null);
    const [saveSuccess, setSaveSuccess] = useState(false);

    const [actionLoading, setActionLoading] = useState(false);
    const [confirmAction, setConfirmAction] = useState<"unsubscribe" | "delete" | null>(null);

    const fetchCategory = useCallback(async () => {
        try {
            const { data } = await apiFetch<{ items: MaterialCategory[]; total: number }>(
                "/api/v1/MaterialCategoryListRoute",
                { method: "POST", body: "{}" },
                { silent: true },
            );
            const found = data?.items?.find(c => c.id === syncId);
            if (found?.sync) {
                setCategory(found);
                setError(null);
            } else {
                setError("未找到该同步源");
            }
        } catch (err) {
            setError(err instanceof Error ? err.message : "加载失败");
        } finally {
            setLoading(false);
        }
    }, [apiFetch, syncId]);

    useEffect(() => { fetchCategory(); }, [fetchCategory]);

    useEffect(() => {
        if (!category?.sync?.my_subscription) return;
        const sub = category.sync.my_subscription;
        setCronExpr(sub.cron_expr);
        setFreshnessWindow(String(sub.freshness_window_sec));
        setEnabled(sub.enabled);
    }, [category]);

    const sync = category?.sync;
    const sub = sync?.my_subscription;
    const isOwner = category?.is_mine ?? false;
    const stateInfo = sync ? SYNC_STATE_LABELS[sync.sync_state] : null;
    const typeLabel = sync ? (SYNC_TYPE_LABELS[sync.sync_type] ?? sync.sync_type) : "";

    const handleSave = async () => {
        if (!sub) return;
        setSaving(true);
        setSaveError(null);
        setSaveSuccess(false);
        try {
            const { resp, data } = await apiFetch<{ success?: boolean; error?: string }>(
                "/api/v1/MaterialCategorySyncUserConfigUpdateRoute",
                {
                    method: "POST",
                    body: JSON.stringify({
                        user_config_id: sub.id,
                        enabled,
                        cron_expr: cronExpr,
                        freshness_window_sec: parseInt(freshnessWindow, 10) || 0,
                    }),
                },
            );
            if (!resp.ok) { reportHttpError("更新订阅设置失败", resp); setSaveError("更新失败"); return; }
            if (data?.error) { setSaveError(data.error); return; }
            setSaveSuccess(true);
            await fetchCategory();
            setTimeout(() => setSaveSuccess(false), 2000);
        } catch (err) {
            print_error({ reason: "更新订阅设置时发生网络错误", err });
            setSaveError("网络错误");
        } finally {
            setSaving(false);
        }
    };

    const handleSyncTrigger = async () => {
        if (!sync) return;
        try {
            const { resp } = await apiFetch("/api/v1/MaterialCategorySyncTriggerRoute", {
                method: "POST",
                body: JSON.stringify({ platform_info_id: sync.id }),
            });
            if (resp.ok) await fetchCategory();
            else reportHttpError("触发同步失败", resp);
        } catch (err) {
            print_error({ reason: "触发同步失败", err });
        }
    };

    const handleUnsubscribe = async () => {
        if (!sub) return;
        setActionLoading(true);
        try {
            const { resp, data } = await apiFetch<{ success?: boolean; error?: string }>(
                "/api/v1/MaterialCategorySyncUnsubscribeRoute",
                { method: "POST", body: JSON.stringify({ user_config_id: sub.id }) },
            );
            if (!resp.ok) { reportHttpError("取消订阅失败", resp); return; }
            if (data?.error) { setSaveError(data.error); return; }
            navigate("/material-library");
        } catch (err) {
            print_error({ reason: "取消订阅时发生网络错误", err });
        } finally {
            setActionLoading(false);
            setConfirmAction(null);
        }
    };

    const handleDelete = async () => {
        if (!category) return;
        setActionLoading(true);
        try {
            const { resp, data } = await apiFetch<{ success?: boolean; error?: string }>(
                "/api/v1/MaterialCategorySyncDeleteRoute",
                { method: "POST", body: JSON.stringify({ id: category.id }) },
            );
            if (!resp.ok) { reportHttpError("删除数据源失败", resp); return; }
            if (data?.error) { setSaveError(data.error); return; }
            navigate("/material-library");
        } catch (err) {
            print_error({ reason: "删除数据源时发生网络错误", err });
        } finally {
            setActionLoading(false);
            setConfirmAction(null);
        }
    };

    if (loading) {
        return (
            <SidebarLayout>
                <div className="max-w-2xl mx-auto p-6 flex items-center justify-center min-h-[200px]">
                    <Loader className="w-5 h-5 animate-spin text-gray-400" />
                </div>
            </SidebarLayout>
        );
    }

    if (error || !category || !sync) {
        return (
            <SidebarLayout>
                <div className="max-w-2xl mx-auto p-6 space-y-4">
                    <button onClick={() => navigate("/material-library")} className="flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-700 transition-colors">
                        <ArrowLeft className="w-4 h-4" /> 返回素材库
                    </button>
                    <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-500">
                        {error ?? "同步源不存在"}
                    </div>
                </div>
            </SidebarLayout>
        );
    }

    return (
        <SidebarLayout>
            <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-5">
                {/* Header */}
                <div>
                    <button onClick={() => navigate("/material-library")} className="flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-700 transition-colors mb-3">
                        <ArrowLeft className="w-4 h-4" /> 返回素材库
                    </button>
                    <div className="flex items-center justify-between">
                        <div>
                            <h1 className="text-xl font-semibold text-gray-900">{sync.display_name || category.name}</h1>
                            <p className="text-sm text-gray-500 mt-0.5">{typeLabel} · {category.material_count} 个素材</p>
                        </div>
                        {stateInfo && (
                            <span className={`px-2.5 py-1 text-xs font-medium rounded-full ${stateInfo.className}`}>
                                {stateInfo.label}
                            </span>
                        )}
                    </div>
                </div>

                {/* Sync info */}
                <div className="bg-white rounded-lg border border-gray-200 p-4 space-y-3">
                    <h2 className="text-sm font-medium text-gray-700">同步信息</h2>
                    <div className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
                        <div className="text-gray-500">同步类型</div>
                        <div className="text-gray-900">{typeLabel}</div>
                        <div className="text-gray-500">素材数</div>
                        <div className="text-gray-900">{sync.item_count}</div>
                        <div className="text-gray-500">订阅者</div>
                        <div className="text-gray-900">{sync.subscriber_count}</div>
                        {sync.last_synced_at && (
                            <>
                                <div className="text-gray-500">上次同步</div>
                                <div className="text-gray-900">{formatRelativeTime(sync.last_synced_at)}</div>
                            </>
                        )}
                    </div>
                    {sync.sync_state === "failed" && sync.last_error && (
                        <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded-lg">
                            错误：{sync.last_error}（失败 {sync.fail_count} 次）
                        </p>
                    )}
                    <div className="pt-2">
                        <button
                            onClick={handleSyncTrigger}
                            disabled={sync.sync_state === "syncing"}
                            className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            <RefreshCw className={`w-3.5 h-3.5 ${sync.sync_state === "syncing" ? "animate-spin" : ""}`} />
                            立即同步
                        </button>
                    </div>
                </div>

                {/* Workflow panel */}
                {sync.last_workflow_run_id && (
                    <div className="bg-white rounded-lg border border-gray-200 p-4 space-y-3">
                        <h2 className="text-sm font-medium text-gray-700">最近同步任务</h2>
                        <WorkflowInfoPanel
                            workflowRunId={sync.last_workflow_run_id}
                            active={sync.sync_state === "syncing"}
                            defaultExpanded
                        />
                    </div>
                )}

                {/* Subscription settings */}
                <div className="bg-white rounded-lg border border-gray-200 p-4 space-y-4">
                    <h2 className="text-sm font-medium text-gray-700">订阅设置</h2>

                    {sub ? (
                        <>
                            <div>
                                <label className="block text-sm text-gray-600 mb-1">同步状态</label>
                                <label className="inline-flex items-center gap-2 cursor-pointer">
                                    <input
                                        type="checkbox"
                                        checked={enabled}
                                        onChange={e => setEnabled(e.target.checked)}
                                        className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                                    />
                                    <span className="text-sm text-gray-700">{enabled ? "已启用" : "已暂停"}</span>
                                </label>
                            </div>
                            <div>
                                <label className="block text-sm text-gray-600 mb-1">定时同步</label>
                                <CronExpressionInput value={cronExpr} onChange={setCronExpr} />
                            </div>
                            <div>
                                <label className="block text-sm text-gray-600 mb-1">新鲜度窗口（秒）</label>
                                <input
                                    type="number"
                                    value={freshnessWindow}
                                    onChange={e => setFreshnessWindow(e.target.value)}
                                    min={0}
                                    className="w-full px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                                />
                                <p className="mt-1 text-xs text-gray-400">上次同步后多久内跳过重复同步，0 表示不限制</p>
                            </div>

                            {saveError && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded-lg">{saveError}</p>}
                            {saveSuccess && <p className="text-sm text-green-600 bg-green-50 px-3 py-2 rounded-lg">已保存</p>}

                            <div className="flex gap-2 pt-1">
                                <button
                                    onClick={handleSave}
                                    disabled={saving}
                                    className="flex items-center gap-1.5 px-4 py-1.5 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                >
                                    {saving && <Loader className="w-3.5 h-3.5 animate-spin" />}
                                    保存设置
                                </button>
                            </div>
                        </>
                    ) : (
                        <p className="text-sm text-gray-500">你尚未订阅此数据源。</p>
                    )}
                </div>

                {/* Danger zone */}
                {(sub || isOwner) && (
                    <div className="bg-white rounded-lg border border-red-200 p-4 space-y-3">
                        <h2 className="text-sm font-medium text-red-700">危险操作</h2>

                        {confirmAction === "unsubscribe" && (
                            <div className="bg-red-50 rounded-lg p-3 space-y-2">
                                <p className="text-sm text-gray-700">确定要取消订阅「{category.name}」吗？取消后将不再自动同步此数据源。</p>
                                <div className="flex gap-2">
                                    <button
                                        onClick={handleUnsubscribe}
                                        disabled={actionLoading}
                                        className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white bg-red-600 rounded-lg hover:bg-red-700 transition-colors disabled:opacity-50"
                                    >
                                        {actionLoading && <Loader className="w-3.5 h-3.5 animate-spin" />}
                                        确认取消订阅
                                    </button>
                                    <button onClick={() => setConfirmAction(null)} className="px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 rounded-lg transition-colors">
                                        返回
                                    </button>
                                </div>
                            </div>
                        )}

                        {confirmAction === "delete" && (
                            <div className="bg-red-50 rounded-lg p-3 space-y-2">
                                <p className="text-sm text-red-700 font-medium">此操作不可撤销！</p>
                                <p className="text-sm text-gray-700">删除数据源「{category.name}」将同时删除所有同步元数据和订阅者配置。已导入的素材不会被删除，但会移至"待分类"。</p>
                                <div className="flex gap-2">
                                    <button
                                        onClick={handleDelete}
                                        disabled={actionLoading}
                                        className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white bg-red-600 rounded-lg hover:bg-red-700 transition-colors disabled:opacity-50"
                                    >
                                        {actionLoading && <Loader className="w-3.5 h-3.5 animate-spin" />}
                                        确认删除
                                    </button>
                                    <button onClick={() => setConfirmAction(null)} className="px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 rounded-lg transition-colors">
                                        返回
                                    </button>
                                </div>
                            </div>
                        )}

                        {!confirmAction && (
                            <div className="flex gap-2">
                                {sub && (
                                    <button
                                        onClick={() => setConfirmAction("unsubscribe")}
                                        className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-red-600 border border-red-200 rounded-lg hover:bg-red-50 transition-colors"
                                    >
                                        <Unplug className="w-3.5 h-3.5" />
                                        取消订阅
                                    </button>
                                )}
                                {isOwner && (
                                    <button
                                        onClick={() => setConfirmAction("delete")}
                                        className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-red-600 border border-red-200 rounded-lg hover:bg-red-50 transition-colors"
                                    >
                                        <Trash2 className="w-3.5 h-3.5" />
                                        删除数据源
                                    </button>
                                )}
                            </div>
                        )}
                    </div>
                )}
            </div>
        </SidebarLayout>
    );
}
