/**
 * material.$materialId.video-standalone.tsx
 *
 * 独立标签页播放器（Mode C）。
 * 通过 openInternalUrl(`/material/${id}/video-standalone`) 打开。
 * 全屏布局，不含 SubNav；含素材标题和返回链接。
 */

import { useSearchParams } from "react-router";
import { ArrowLeft } from "lucide-react";
import { Link } from "react-router";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { MaterialVideoPlayer } from "~/components/material/MaterialVideoPlayer";

export default function VideoStandalonePage() {
    const { material } = useWorkspaceContext();
    const [searchParams] = useSearchParams();
    const initialSeek = searchParams.get("t") ? Number(searchParams.get("t")) : undefined;

    return (
        <div className="flex flex-col min-h-screen bg-black text-white">
            {/* Top bar */}
            <div className="flex items-center gap-3 px-4 py-2.5 bg-black/80 border-b border-white/10 z-10">
                <Link
                    to={`/material/${material.id}`}
                    className="p-1.5 rounded-lg text-gray-400 hover:text-white hover:bg-white/10 transition-colors flex-shrink-0"
                    title="返回素材工作区"
                >
                    <ArrowLeft className="w-4 h-4" />
                </Link>
                <div className="flex-1 min-w-0">
                    <h1 className="text-sm font-semibold truncate">{material.title || material.source_id}</h1>
                </div>
                <span className="text-xs text-gray-500 flex-shrink-0">独立播放器</span>
            </div>

            {/* Full-screen video */}
            <div className="flex-1 flex items-center justify-center p-4">
                <div className="w-full max-w-5xl">
                    <MaterialVideoPlayer
                        materialId={material.id}
                        mode="standalone"
                        initialSeek={initialSeek}
                        className="w-full aspect-video"
                    />
                </div>
            </div>
        </div>
    );
}
