import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import { useAppFetch } from "~/util/app_fetch";
import { BilibiliVideoList, type MediaItem } from "~/components/bilibili/BilibiliVideoList";

interface VlistItem {
    aid: number;
    bvid: string;
    title: string;
    pic: string;
    description: string;
    length: string;
    created: number;
    play: number;
    comment: number;
    video_review: number;
    favorites: number;
    author: string;
    mid: number;
}

interface GetPageResult {
    mid: string;
    page: number;
    videos: VlistItem[];
    has_more: boolean | string;
}

function parseDuration(length: string): number {
    const parts = length.split(":").map(Number);
    if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
    if (parts.length === 2) return parts[0] * 60 + parts[1];
    return parts[0] || 0;
}

function vlistToMedia(item: VlistItem): MediaItem {
    return {
        id: item.aid || 0,
        title: item.title || "",
        cover: item.pic || "",
        intro: item.description || "",
        page: 1,
        duration: parseDuration(item.length || "0"),
        upper: {
            name: item.author || "",
            face: "",
        },
        cnt_info: {
            collect: item.favorites || 0,
            play: item.play || 0,
            danmaku: item.video_review || 0,
            view_text_1: "",
        },
        fav_time: item.created || 0,
        bvid: item.bvid || "",
    };
}

function isHasMore(v: boolean | string): boolean {
    if (typeof v === "boolean") return v;
    return v === "true";
}

export default function Component() {
    const { mid, order } = useParams<{ mid: string; order: string }>();

    const [allMedias, setAllMedias] = useState<MediaItem[]>([]);
    const [currentPage, setCurrentPage] = useState(1);
    const [hasMore, setHasMore] = useState(false);
    const targetPageRef = useRef<number | null>(null);
    const [nextPageTrigger, setNextPageTrigger] = useState<{ page: number } | null>(null);

    useEffect(() => {
        setAllMedias([]);
        setCurrentPage(1);
        setHasMore(false);
        setNextPageTrigger(null);
        targetPageRef.current = null;
    }, [mid, order]);

    const { data: firstPageResult, loading, error } = useAppFetch<GetPageResult>({
        appPath: mid
            ? `/api/v1/BilibiliUploaderGetPageRoute?_mid=${encodeURIComponent(mid)}&_order=${encodeURIComponent(order || "pubdate")}`
            : undefined,
        init: mid ? {
            method: "POST",
            body: JSON.stringify({ mid, page: 1, order: order || "pubdate" }),
        } : undefined,
        timeout: 30_000,
    });

    const { data: nextPageResult, loading: nextPageLoading, error: nextPageError } = useAppFetch<GetPageResult>({
        appPath: nextPageTrigger != null && mid
            ? `/api/v1/BilibiliUploaderGetPageRoute?_mid=${encodeURIComponent(mid)}&_order=${encodeURIComponent(order || "pubdate")}&_page=${nextPageTrigger.page}`
            : undefined,
        init: nextPageTrigger != null && mid ? {
            method: "POST",
            body: JSON.stringify({ mid, page: nextPageTrigger.page, order: order || "pubdate" }),
        } : undefined,
        timeout: 30_000,
    });

    useEffect(() => {
        if (firstPageResult) {
            setAllMedias(firstPageResult.videos.map(vlistToMedia));
            setCurrentPage(1);
            setHasMore(isHasMore(firstPageResult.has_more));
            setNextPageTrigger(null);
            targetPageRef.current = null;
        }
    }, [firstPageResult]);

    useEffect(() => {
        if (nextPageResult) {
            const newPage = nextPageResult.page;
            setAllMedias(prev => [...prev, ...nextPageResult.videos.map(vlistToMedia)]);
            setCurrentPage(newPage);
            setHasMore(isHasMore(nextPageResult.has_more));

            if (
                targetPageRef.current !== null &&
                newPage < targetPageRef.current &&
                isHasMore(nextPageResult.has_more)
            ) {
                setNextPageTrigger({ page: newPage + 1 });
            } else {
                targetPageRef.current = null;
            }
        }
    }, [nextPageResult]);

    const handleJumpToPage = (page: number) => {
        if (!mid || nextPageLoading) return;
        if (page <= currentPage) {
            document.getElementById(`bilibili-video-page-${page}`)
                ?.scrollIntoView({ behavior: "smooth", block: "start" });
            return;
        }
        targetPageRef.current = page;
        setNextPageTrigger({ page: currentPage + 1 });
    };

    const orderLabel = order === "view" ? "最多播放" : order === "favorite" ? "最多收藏" : "最新发布";

    const nextPageSlot = hasMore ? (
        <div className="flex flex-col items-center gap-1.5 w-full">
            {nextPageError && (
                <p className="text-xs text-red-500">{nextPageError.message}</p>
            )}
            <button
                onClick={() => handleJumpToPage(currentPage + 1)}
                disabled={nextPageLoading}
                className="px-6 py-2 text-sm font-medium text-blue-700 bg-blue-50 border border-blue-200 rounded-lg hover:bg-blue-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
                {nextPageLoading ? "加载中..." : `加载下一页（第 ${currentPage + 1} 页）`}
            </button>
        </div>
    ) : undefined;

    return (
        <>
            {loading && (
                <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-400">
                    获取UP主视频列表中...
                </div>
            )}
            {error && !loading && (
                <div className="bg-white rounded-lg border border-gray-200 p-4">
                    <p className="text-sm text-red-600">{error.message}</p>
                </div>
            )}

            {firstPageResult && (
                <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                    <div className="flex items-center gap-2">
                        <span className="text-xs font-semibold px-2 py-0.5 rounded bg-blue-100 text-blue-700">
                            UP主投稿
                        </span>
                        <span className="text-sm text-gray-500">
                            UID: {mid}
                        </span>
                        <span className="text-sm text-gray-500">
                            排序: {orderLabel}
                        </span>
                    </div>
                </div>
            )}

            <BilibiliVideoList
                medias={allMedias.length > 0 ? allMedias : firstPageResult?.videos.map(vlistToMedia)}
                nextPageSlot={nextPageSlot}
                currentPage={currentPage}
                onJumpToPage={handleJumpToPage}
                pageLoading={nextPageLoading}
            />
        </>
    );
}
