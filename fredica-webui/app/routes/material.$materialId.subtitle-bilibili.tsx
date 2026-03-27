import { ArrowLeft } from "lucide-react";
import { Link } from "react-router";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { BilibiliSubtitlePanel } from "~/components/bilibili/BilibiliSubtitlePanel";
import { type BilibiliExtra } from "~/components/material-library/materialTypes";
import { useFloatingPlayerCtx } from "~/context/floatingPlayer";
import { json_parse } from "~/util/json";

// ─── Helpers ──────────────────────────────────────────────────────────────────

function getBilibiliInfo(material: { source_type: string; source_id: string; id: string; extra: string }) {
    if (material.source_type !== "bilibili") return null;
    let bvid = material.source_id;
    const ext = json_parse<BilibiliExtra>(material.extra);
    if (ext?.bvid) bvid = ext.bvid;
    // 分 P 素材 ID 格式：<bvid>__P<n>
    const pageMatch = material.id.match(/__P(\d+)$/);
    const pageIndex = pageMatch ? parseInt(pageMatch[1], 10) - 1 : 0;
    return { bvid, pageIndex };
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function SubtitleBilibiliPage() {
    const { material } = useWorkspaceContext();
    const bilibiliInfo = getBilibiliInfo(material);
    const { openFloatingPlayer } = useFloatingPlayerCtx();

    const handleSeek = (seconds: number) => {
        openFloatingPlayer(material.id, seconds);
    };

    return (
        <div className="max-w-2xl mx-auto p-4 sm:p-6 flex flex-col gap-4 h-full">

            {/* Breadcrumb */}
            <div className="flex items-center gap-2">
                <Link
                    to="../subtitle"
                    relative="path"
                    className="flex items-center gap-1.5 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                >
                    <ArrowLeft className="w-3.5 h-3.5" />
                    字幕提取
                </Link>
                <span className="text-xs text-gray-300">/</span>
                <span className="text-xs text-gray-500 font-medium">B站平台字幕</span>
            </div>

            {/* Non-bilibili guard */}
            {!bilibiliInfo ? (
                <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center py-16">
                    <p className="text-sm font-medium text-gray-600">该素材不是 B站 来源</p>
                    <p className="text-xs text-gray-400">仅 Bilibili 素材支持平台字幕查询</p>
                    <Link
                        to="../subtitle"
                        relative="path"
                        className="mt-2 text-xs text-violet-600 hover:text-violet-800 transition-colors"
                    >
                        ← 返回字幕提取
                    </Link>
                </div>
            ) : (
                /* Panel: grow to fill available height */
                <div className="flex-1 bg-white rounded-xl border border-gray-200 p-4 sm:p-5 flex flex-col min-h-0">
                    <BilibiliSubtitlePanel
                        materialId={material.id}
                        bvid={bilibiliInfo.bvid}
                        pageIndex={bilibiliInfo.pageIndex}
                        onSeek={handleSeek}
                    />
                </div>
            )}
        </div>
    );
}
