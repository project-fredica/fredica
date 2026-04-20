import { useState } from "react";
import { Loader, X } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { print_error, reportHttpError } from "~/util/error_handler";
import { CronExpressionInput } from "~/components/ui/CronExpressionInput";
import type { MaterialCategory } from "./materialTypes";

type ModalView = "settings" | "confirm-unsubscribe" | "confirm-delete";

export function SyncSubscriptionSettingsModal({
    category,
    isOwner,
    onClose,
    onUpdated,
}: {
    category: MaterialCategory;
    isOwner: boolean;
    onClose: () => void;
    onUpdated: () => void;
}) {
    const sync = category.sync!;
    const sub = sync.my_subscription;

    const [view, setView] = useState<ModalView>("settings");
    const [cronExpr, setCronExpr] = useState(sub?.cron_expr ?? "");
    const [freshnessWindow, setFreshnessWindow] = useState(
        sub ? String(sub.freshness_window_sec) : "0"
    );
    const [enabled, setEnabled] = useState(sub?.enabled ?? true);
    const [saving, setSaving] = useState(false);
    const [actionLoading, setActionLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const { apiFetch } = useAppFetch();

    const handleSave = async () => {
        if (!sub) return;
        setSaving(true);
        setError(null);
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
                }
            );
            if (!resp.ok) {
                reportHttpError("更新订阅设置失败", resp);
                setError("更新失败");
                return;
            }
            if (data?.error) {
                setError(data.error);
                return;
            }
            onUpdated();
            onClose();
        } catch (err) {
            print_error({ reason: "更新订阅设置时发生网络错误", err });
            setError("网络错误");
        } finally {
            setSaving(false);
        }
    };

    const handleUnsubscribe = async () => {
        if (!sub) return;
        setActionLoading(true);
        setError(null);
        try {
            const { resp, data } = await apiFetch<{ success?: boolean; error?: string }>(
                "/api/v1/MaterialCategorySyncUnsubscribeRoute",
                {
                    method: "POST",
                    body: JSON.stringify({ user_config_id: sub.id }),
                }
            );
            if (!resp.ok) {
                reportHttpError("取消订阅失败", resp);
                setError("取消订阅失败");
                return;
            }
            if (data?.error) {
                setError(data.error);
                return;
            }
            onUpdated();
            onClose();
        } catch (err) {
            print_error({ reason: "取消订阅时发生网络错误", err });
            setError("网络错误");
        } finally {
            setActionLoading(false);
        }
    };

    const handleDeleteSource = async () => {
        setActionLoading(true);
        setError(null);
        try {
            const { resp, data } = await apiFetch<{ success?: boolean; error?: string }>(
                "/api/v1/MaterialCategorySyncDeleteRoute",
                {
                    method: "POST",
                    body: JSON.stringify({ id: category.id }),
                }
            );
            if (!resp.ok) {
                reportHttpError("删除数据源失败", resp);
                setError("删除数据源失败");
                return;
            }
            if (data?.error) {
                setError(data.error);
                return;
            }
            onUpdated();
            onClose();
        } catch (err) {
            print_error({ reason: "删除数据源时发生网络错误", err });
            setError("网络错误");
        } finally {
            setActionLoading(false);
        }
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-sm">
            <div className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4">
                {/* Header */}
                <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200">
                    <h3 className="text-base font-semibold text-gray-900">
                        {view === "settings" && "订阅设置"}
                        {view === "confirm-unsubscribe" && "确认取消订阅"}
                        {view === "confirm-delete" && "确认删除数据源"}
                    </h3>
                    <button onClick={onClose} className="p-1 text-gray-400 hover:text-gray-600 rounded transition-colors">
                        <X className="w-4 h-4" />
                    </button>
                </div>

                {/* Body */}
                <div className="px-5 py-4 space-y-4">
                    {error && (
                        <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded-lg">{error}</p>
                    )}

                    {view === "settings" && sub && (
                        <>
                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-1">
                                    同步状态
                                </label>
                                <label className="inline-flex items-center gap-2 cursor-pointer">
                                    <input
                                        type="checkbox"
                                        checked={enabled}
                                        onChange={e => setEnabled(e.target.checked)}
                                        className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                                    />
                                    <span className="text-sm text-gray-600">
                                        {enabled ? "已启用" : "已暂停"}
                                    </span>
                                </label>
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-1">
                                    定时同步表达式（Cron）
                                </label>
                                <CronExpressionInput
                                    value={cronExpr}
                                    onChange={setCronExpr}
                                />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-1">
                                    新鲜度窗口（秒）
                                </label>
                                <input
                                    type="number"
                                    value={freshnessWindow}
                                    onChange={e => setFreshnessWindow(e.target.value)}
                                    min={0}
                                    className="w-full px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                                />
                                <p className="mt-1 text-xs text-gray-400">
                                    上次同步后多久内跳过重复同步，0 表示不限制
                                </p>
                            </div>
                        </>
                    )}

                    {view === "settings" && !sub && (
                        <p className="text-sm text-gray-500">你尚未订阅此数据源。</p>
                    )}

                    {view === "confirm-unsubscribe" && (
                        <p className="text-sm text-gray-700">
                            确定要取消订阅「{category.name}」吗？取消后将不再自动同步此数据源。
                        </p>
                    )}

                    {view === "confirm-delete" && (
                        <div className="space-y-2">
                            <p className="text-sm text-red-700 font-medium">
                                此操作不可撤销！
                            </p>
                            <p className="text-sm text-gray-700">
                                删除数据源「{category.name}」将同时删除所有同步元数据和订阅者配置。
                                已导入的素材不会被删除，但会移至"待分类"。
                            </p>
                        </div>
                    )}
                </div>

                {/* Footer */}
                <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 bg-gray-50 rounded-b-xl">
                    {view === "settings" && (
                        <>
                            <div className="flex gap-2">
                                {sub && (
                                    <button
                                        onClick={() => setView("confirm-unsubscribe")}
                                        className="px-3 py-1.5 text-xs font-medium text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                                    >
                                        取消订阅
                                    </button>
                                )}
                                {isOwner && (
                                    <button
                                        onClick={() => setView("confirm-delete")}
                                        className="px-3 py-1.5 text-xs font-medium text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                                    >
                                        删除数据源
                                    </button>
                                )}
                            </div>
                            <div className="flex gap-2">
                                <button
                                    onClick={onClose}
                                    className="px-4 py-1.5 text-sm text-gray-600 hover:bg-gray-100 rounded-lg transition-colors"
                                >
                                    取消
                                </button>
                                {sub && (
                                    <button
                                        onClick={handleSave}
                                        disabled={saving}
                                        className="flex items-center gap-1.5 px-4 py-1.5 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                    >
                                        {saving && <Loader className="w-3.5 h-3.5 animate-spin" />}
                                        保存
                                    </button>
                                )}
                            </div>
                        </>
                    )}

                    {view === "confirm-unsubscribe" && (
                        <>
                            <button
                                onClick={() => setView("settings")}
                                className="px-4 py-1.5 text-sm text-gray-600 hover:bg-gray-100 rounded-lg transition-colors"
                            >
                                返回
                            </button>
                            <button
                                onClick={handleUnsubscribe}
                                disabled={actionLoading}
                                className="flex items-center gap-1.5 px-4 py-1.5 text-sm font-medium text-white bg-red-600 rounded-lg hover:bg-red-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                {actionLoading && <Loader className="w-3.5 h-3.5 animate-spin" />}
                                确认取消订阅
                            </button>
                        </>
                    )}

                    {view === "confirm-delete" && (
                        <>
                            <button
                                onClick={() => setView("settings")}
                                className="px-4 py-1.5 text-sm text-gray-600 hover:bg-gray-100 rounded-lg transition-colors"
                            >
                                返回
                            </button>
                            <button
                                onClick={handleDeleteSource}
                                disabled={actionLoading}
                                className="flex items-center gap-1.5 px-4 py-1.5 text-sm font-medium text-white bg-red-600 rounded-lg hover:bg-red-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                {actionLoading && <Loader className="w-3.5 h-3.5 animate-spin" />}
                                确认删除
                            </button>
                        </>
                    )}
                </div>
            </div>
        </div>
    );
}
