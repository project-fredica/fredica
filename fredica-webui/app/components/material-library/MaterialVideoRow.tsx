import { type ReactNode } from "react";
import { Link } from "react-router";
import { ExternalLink, SquareArrowOutUpRight } from "lucide-react";
import { useImageProxyUrl } from "~/util/app_fetch";
import { MaterialTaskBadge } from "~/components/MaterialTaskBadge";
import { BilibiliAiConclusionButton } from "~/components/bilibili/BilibiliAiConclusionButton";
import {
    type MaterialVideo, type MaterialCategory, type MaterialTask, type BilibiliExtra,
    SOURCE_BADGE, formatDuration, formatCount,
} from "./materialTypes";
import { json_parse } from "~/util/json";

export function MaterialVideoRow({
    video,
    categories,
    downloadStatusMap,
    tasksMap,
    deletingVideoIds,
    onOpenAction,
    onOpenAiConclusion,
    onSelectCategory,
}: {
    video: MaterialVideo;
    categories: MaterialCategory[] | null;
    downloadStatusMap: Record<string, boolean>;
    tasksMap: Map<string, MaterialTask[]>;
    deletingVideoIds: Set<string>;
    onOpenAction: (video: MaterialVideo) => void;
    onOpenAiConclusion: (bvid: string, pageIndex: number) => void;
    onSelectCategory: (id: string) => void;
}) {
    const buildProxyUrl = useImageProxyUrl();
    const sourceBadge = SOURCE_BADGE[video.source_type] ?? { label: video.source_type, className: 'bg-gray-100 text-gray-600' };
    const isDeleting = deletingVideoIds.has(video.id);
    const videoTasks = tasksMap.get(video.id);
    const videoCats = (categories ?? []).filter(c => video.category_ids.includes(c.id));

    const bilibiliPage = video.source_type === 'bilibili'
        ? parseInt(video.id.match(/__P(\d+)$/)?.[1] ?? '1', 10)
        : 1;
    const bilibiliUrl = video.source_type === 'bilibili'
        ? `https://www.bilibili.com/video/${video.source_id}${bilibiliPage > 1 ? `?p=${bilibiliPage}` : ''}`
        : null;

    let extraInfo: ReactNode = null;
    if (video.source_type === 'bilibili') {
        const ext = json_parse<BilibiliExtra>(video.extra);
        extraInfo = (
            <div className="flex items-center gap-3 text-xs text-gray-400">
                {ext?.upper_name && <span>UP: {ext.upper_name}</span>}
                {ext?.cnt_play !== undefined && <span>播放 {formatCount(ext.cnt_play)}</span>}
                {ext?.cnt_collect !== undefined && <span>收藏 {formatCount(ext.cnt_collect)}</span>}
            </div>
        );
    }

    return (
        <div
            className={`flex gap-3 p-3 sm:p-4 transition-colors hover:bg-gray-50 ${isDeleting ? 'opacity-50' : ''}`}
        >
            {/* Cover */}
            <div className="relative flex-shrink-0">
                <img
                    src={video.cover_url ? buildProxyUrl(video.cover_url) : ''}
                    alt={video.title}
                    className="w-32 sm:w-40 h-[72px] sm:h-[90px] object-cover rounded-lg bg-gray-100"
                />
                {video.duration > 0 && (
                    <span className="absolute bottom-1 right-1 bg-black/70 text-white text-xs px-1 py-0.5 rounded font-mono leading-none">
                        {formatDuration(video.duration)}
                    </span>
                )}
            </div>

            {/* Content */}
            <div className="flex-1 min-w-0 flex flex-col justify-between gap-1">
                <Link to={`/material/${video.id}`} className="group/title">
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-2 leading-snug group-hover/title:text-violet-700 transition-colors">
                        {video.title || video.source_id}
                    </h3>
                </Link>
                {extraInfo}

                <div className="flex items-center gap-1.5 flex-wrap">
                    <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${sourceBadge.className}`}>
                        {sourceBadge.label}
                    </span>
                    {video.source_type === 'bilibili' && video.id in downloadStatusMap && (
                        <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${downloadStatusMap[video.id] ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
                            {downloadStatusMap[video.id] ? '已下载' : '未下载'}
                        </span>
                    )}
                    {videoCats.map(cat => (
                        <span
                            key={cat.id}
                            onClick={() => onSelectCategory(cat.id)}
                            className="text-[10px] font-medium px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-600 cursor-pointer hover:bg-indigo-100"
                        >
                            {cat.name}
                        </span>
                    ))}
                </div>

                <span className="text-xs text-gray-400 font-mono">{video.source_id}</span>

                {videoTasks && videoTasks.length > 0 && (
                    <div className="flex items-center gap-1 flex-wrap">
                        {videoTasks.map(task => (
                            <MaterialTaskBadge key={task.id} taskType={task.task_type} status={task.status} />
                        ))}
                    </div>
                )}
            </div>

            {/* Actions */}
            <div className="flex flex-col gap-1.5 flex-shrink-0 justify-center w-[72px]">
                {bilibiliUrl && (
                    <button
                        onClick={() => window.open(bilibiliUrl, '_blank')}
                        className="w-full flex items-center justify-center gap-1 px-2 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors"
                    >
                        <ExternalLink className="w-3.5 h-3.5 flex-shrink-0" />
                        打开
                    </button>
                )}
                {video.source_type === 'bilibili' && (
                    <BilibiliAiConclusionButton
                        bvid={video.source_id}
                        pageIndex={bilibiliPage - 1}
                        onClick={() => onOpenAiConclusion(video.source_id, bilibiliPage - 1)}
                    />
                )}
                <Link
                    to={`/material/${video.id}`}
                    className="w-full flex items-center justify-center gap-1 px-2 py-1.5 text-xs font-medium text-violet-700 bg-violet-50 rounded-lg hover:bg-violet-100 transition-colors"
                    title="进入工作区"
                >
                    <SquareArrowOutUpRight className="w-3.5 h-3.5 flex-shrink-0" />
                    工作区
                </Link>
                <button
                    onClick={() => onOpenAction(video)}
                    className="w-full flex items-center justify-center gap-1 px-2 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
                >
                    <svg className="w-3.5 h-3.5 flex-shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/></svg>
                    操作
                </button>
            </div>
        </div>
    );
}
