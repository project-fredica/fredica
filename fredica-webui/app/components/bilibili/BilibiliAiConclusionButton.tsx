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

    if (!hasSuccess) return <span ref={setRef as React.RefCallback<HTMLSpanElement>} />;

    return (
        <button
            ref={setRef as React.RefCallback<HTMLButtonElement>}
            onClick={onClick}
            className="w-[72px] flex items-center justify-center gap-1 px-2 py-1.5 text-xs font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors"
        >
            <Sparkles className="w-3.5 h-3.5 flex-shrink-0" />
            AI总结
        </button>
    );
}
