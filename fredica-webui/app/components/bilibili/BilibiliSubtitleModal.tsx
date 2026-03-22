import { X, Captions } from "lucide-react";
import { BilibiliSubtitlePanel } from "./BilibiliSubtitlePanel";

export function BilibiliSubtitleModal({
    bvid,
    pageIndex = 0,
    onClose,
}: {
    bvid: string;
    pageIndex?: number;
    onClose: () => void;
}) {
    return (
        <div
            className="fixed inset-0 z-60 flex items-center justify-center bg-black/30 backdrop-blur-sm"
            onClick={(e) => e.target === e.currentTarget && onClose()}
        >
            <div className="bg-white rounded-xl shadow-xl w-full max-w-lg mx-4 flex flex-col max-h-[80vh]">
                {/* Modal chrome header */}
                <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100 flex-shrink-0">
                    <div className="flex items-center gap-2">
                        <Captions className="w-4 h-4 text-blue-500" />
                        <h2 className="text-base font-semibold text-gray-900">B站字幕</h2>
                        <span className="text-xs text-gray-400 font-mono">{bvid}</span>
                    </div>
                    <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors">
                        <X className="w-4 h-4 text-gray-500" />
                    </button>
                </div>

                {/* Panel (handles its own toolbar / refresh button) */}
                <div className="flex-1 overflow-hidden flex flex-col px-5 py-3 min-h-0">
                    <BilibiliSubtitlePanel bvid={bvid} pageIndex={pageIndex} />
                </div>
            </div>
        </div>
    );
}
