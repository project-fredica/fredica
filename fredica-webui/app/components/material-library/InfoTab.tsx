import { type MaterialVideo, type BilibiliExtra } from "./materialTypes";
import { json_parse } from "~/util/json";

export function InfoTab({ actionTarget, onOpenModal, onOpenSubtitleModal }: {
    actionTarget: MaterialVideo;
    onOpenModal: (bvid: string) => void;
    onOpenSubtitleModal: (bvid: string) => void;
}) {
    const bvid = actionTarget.source_type === 'bilibili'
        ? (json_parse<BilibiliExtra>(actionTarget.extra)?.bvid ?? actionTarget.source_id)
        : null;

    if (!bvid) {
        return <p className="text-sm text-gray-400 py-4 text-center">暂无可拉取的信息</p>;
    }

    return (
        <div className="space-y-3">
            <div className="flex items-center justify-between gap-3 py-1.5">
                <div className="min-w-0">
                    <span className="text-sm text-gray-700">B站 AI 总结</span>
                    <p className="text-xs text-gray-400 mt-0.5">查询该视频的 B 站 AI 总结内容（需登录凭证）</p>
                </div>
                <button
                    onClick={() => onOpenModal(bvid)}
                    className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors"
                >
                    查询
                </button>
            </div>
            <div className="flex items-center justify-between gap-3 py-1.5">
                <div className="min-w-0">
                    <span className="text-sm text-gray-700">B站字幕</span>
                    <p className="text-xs text-gray-400 mt-0.5">查询该视频的字幕内容（需登录凭证）</p>
                </div>
                <button
                    onClick={() => onOpenSubtitleModal(bvid)}
                    className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors"
                >
                    查询
                </button>
            </div>
        </div>
    );
}
