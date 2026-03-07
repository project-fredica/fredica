import { Sparkles } from "lucide-react";
import { useAiConclusionStatus } from "~/components/bilibili/useAiConclusionStatus";

/**
 * 视口进入时自动查询缓存（is_update=false），仅当有成功结果时才渲染按钮。
 */
export function BilibiliAiConclusionButton({
    bvid,
    pageIndex,
    onClick,
}: {
    bvid: string;
    pageIndex: number;
    onClick: () => void;
}) {
    const { hasSuccess, setRef } = useAiConclusionStatus(bvid, pageIndex);

    return (
        <span ref={setRef as React.RefCallback<HTMLSpanElement>}>
            {hasSuccess && (
                <button
                    onClick={onClick}
                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors whitespace-nowrap"
                >
                    <Sparkles className="w-3.5 h-3.5" />
                    B站AI总结
                </button>
            )}
        </span>
    );
}
