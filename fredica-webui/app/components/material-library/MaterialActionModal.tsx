import { useMemo, useState } from "react";
import { Loader, X, Trash2, ExternalLink, Copy, Check, FolderOpen, BadgeInfo, Clock3 } from "lucide-react";
import { Link } from "react-router";
import { type MaterialCategory, type MaterialVideo, SOURCE_BADGE, formatDuration, type BilibiliExtra } from "./materialTypes";
import { InfoTab } from "./InfoTab";
import { json_parse } from "~/util/json";
import { print_error } from "~/util/error_handler";

export function MaterialActionModal({
    actionTarget,
    categories,
    onClose,
    deletingVideoIds,
    onDeleteVideo,
    onOpenAiConclusion,
}: {
    actionTarget: MaterialVideo;
    categories: MaterialCategory[] | null;
    onClose: () => void;
    deletingVideoIds: Set<string>;
    onDeleteVideo: (id: string) => void;
    onOpenAiConclusion: (bvid: string) => void;
}) {
    const sourceBadge = SOURCE_BADGE[actionTarget.source_type] ?? { label: actionTarget.source_type, className: 'bg-gray-100 text-gray-600' };
    const videoCats = (categories ?? []).filter(c => actionTarget.category_ids.includes(c.id));
    const bilibiliPage = actionTarget.source_type === 'bilibili'
        ? parseInt(actionTarget.id.match(/__P(\d+)$/)?.[1] ?? '1', 10)
        : 1;
    const bilibiliExtra = actionTarget.source_type === 'bilibili'
        ? json_parse<BilibiliExtra>(actionTarget.extra)
        : null;
    const bilibiliUrl = actionTarget.source_type === 'bilibili'
        ? `https://www.bilibili.com/video/${bilibiliExtra?.bvid ?? actionTarget.source_id}${bilibiliPage > 1 ? `?p=${bilibiliPage}` : ''}`
        : null;
    const copyItems = useMemo(() => {
        const items = [
            { key: 'material_id', label: '复制素材 ID', value: actionTarget.id },
        ];
        if (bilibiliUrl) items.push({ key: 'source_url', label: '复制源站链接', value: bilibiliUrl });
        if (actionTarget.source_id) items.push({ key: 'source_id', label: '复制来源 ID', value: actionTarget.source_id });
        return items;
    }, [actionTarget.id, actionTarget.source_id, bilibiliUrl]);
    const [copiedKey, setCopiedKey] = useState<string | null>(null);

    const handleCopy = async (key: string, value: string) => {
        try {
            await navigator.clipboard.writeText(value);
            setCopiedKey(key);
            setTimeout(() => setCopiedKey(current => current === key ? null : current), 1500);
        } catch (err) {
            print_error({ reason: '复制失败', err, variables: { key } });
        }
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
            <div className="absolute inset-0 bg-black/40" onClick={onClose} />
            <div className="relative bg-white rounded-xl shadow-xl w-full max-w-md mx-4 max-h-[90vh] flex flex-col">
                <div className="flex items-start justify-between gap-2 px-5 pt-5 pb-3 flex-shrink-0 border-b border-gray-200">
                    <div className="min-w-0">
                        <p className="text-xs text-gray-400 mb-0.5">快捷操作</p>
                        <h2 className="text-sm font-semibold text-gray-900 line-clamp-2">
                            {actionTarget.title || actionTarget.source_id}
                        </h2>
                    </div>
                    <button
                        onClick={onClose}
                        className="flex-shrink-0 p-1 rounded-lg hover:bg-gray-100 transition-colors"
                    >
                        <X className="w-4 h-4 text-gray-500" />
                    </button>
                </div>

                <div className="px-5 py-4 overflow-y-auto flex-1 space-y-4">
                    <section className="rounded-xl border border-gray-200 bg-gray-50 p-3 space-y-3">
                        <div className="flex items-center gap-2 flex-wrap">
                            <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${sourceBadge.className}`}>
                                {sourceBadge.label}
                            </span>
                            {actionTarget.duration > 0 && (
                                <span className="inline-flex items-center gap-1 text-[11px] text-gray-500">
                                    <Clock3 className="w-3 h-3" />
                                    {formatDuration(actionTarget.duration)}
                                </span>
                            )}
                        </div>
                        <div className="space-y-1">
                            <p className="text-[11px] text-gray-400">素材标识</p>
                            <p className="text-xs text-gray-700 font-mono break-all">{actionTarget.id}</p>
                        </div>
                        {actionTarget.source_id && (
                            <div className="space-y-1">
                                <p className="text-[11px] text-gray-400">来源标识</p>
                                <p className="text-xs text-gray-700 font-mono break-all">{actionTarget.source_id}</p>
                            </div>
                        )}
                        <div className="space-y-1">
                            <p className="text-[11px] text-gray-400">分类</p>
                            <div className="flex flex-wrap gap-1.5">
                                {videoCats.length > 0 ? videoCats.map(cat => (
                                    <span key={cat.id} className="text-[10px] font-medium px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-600">
                                        {cat.name}
                                    </span>
                                )) : (
                                    <span className="text-xs text-gray-400">未分类</span>
                                )}
                            </div>
                        </div>
                    </section>

                    <section className="space-y-2">
                        <div className="flex items-center gap-2 text-xs font-medium text-gray-500">
                            <FolderOpen className="w-3.5 h-3.5" />
                            基础操作
                        </div>
                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                            {bilibiliUrl && (
                                <button
                                    onClick={() => window.open(bilibiliUrl, '_blank')}
                                    className="flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors"
                                >
                                    <ExternalLink className="w-3.5 h-3.5" />
                                    打开源站
                                </button>
                            )}
                            <Link
                                to={`/tasks/status?material_id=${encodeURIComponent(actionTarget.id)}`}
                                className="flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-medium text-violet-700 bg-violet-50 rounded-lg hover:bg-violet-100 transition-colors"
                                onClick={onClose}
                            >
                                <BadgeInfo className="w-3.5 h-3.5" />
                                前往任务中心
                            </Link>
                        </div>
                    </section>

                    <section className="space-y-2 border-t border-gray-100 pt-4">
                        <div className="flex items-center gap-2 text-xs font-medium text-gray-500">
                            <Copy className="w-3.5 h-3.5" />
                            复制信息
                        </div>
                        <div className="space-y-2">
                            {copyItems.map(item => (
                                <button
                                    key={item.key}
                                    onClick={() => handleCopy(item.key, item.value)}
                                    className="w-full flex items-center justify-between gap-3 px-3 py-2 text-xs rounded-lg border border-gray-200 hover:bg-gray-50 transition-colors"
                                >
                                    <span className="text-gray-700">{item.label}</span>
                                    <span className="inline-flex items-center gap-1 text-gray-500">
                                        {copiedKey === item.key ? <Check className="w-3.5 h-3.5 text-green-600" /> : <Copy className="w-3.5 h-3.5" />}
                                        {copiedKey === item.key ? '已复制' : '复制'}
                                    </span>
                                </button>
                            ))}
                        </div>
                    </section>

                    <section className="border-t border-gray-100 pt-4">
                        <InfoTab
                            actionTarget={actionTarget}
                            onOpenModal={onOpenAiConclusion}
                        />
                    </section>

                    <div className="pt-3 border-t border-red-100">
                        <button
                            onClick={() => { onClose(); onDeleteVideo(actionTarget.id); }}
                            disabled={deletingVideoIds.has(actionTarget.id)}
                            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {deletingVideoIds.has(actionTarget.id) ? <Loader className="w-3 h-3 animate-spin" /> : <Trash2 className="w-3 h-3" />}
                            移除素材库（但不删除数据）
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
