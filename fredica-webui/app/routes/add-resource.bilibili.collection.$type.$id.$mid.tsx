import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import { useAppFetch } from "~/util/app_fetch";
import { BilibiliVideoList, type MediaItem } from "~/components/bilibili/BilibiliVideoList";

interface ArchiveItem {
    aid: number;
    bvid: string;
    title: string;
    pic: string;
    duration: number;
    pubdate: number;
    desc?: string;
    stat?: {
        view: number;
        danmaku: number;
        like: number;
        favorite: number;
    };
    owner?: {
        mid: number;
        name: string;
        face: string;
    };
    page?: number;
}

interface GetPageResult {
    season_id?: string;
    series_id?: string;
    mid: string;
    page: number;
    videos: ArchiveItem[];
    has_more: boolean | string;
}

function archiveToMedia(item: ArchiveItem): MediaItem {
    return {
        id: item.aid || 0,
        title: item.title || "",
        cover: item.pic || "",
        intro: item.desc || "",
        page: item.page || 1,
        duration: item.duration || 0,
        upper: {
            name: item.owner?.name || "",
            face: item.owner?.face || "",
        },
        cnt_info: {
            collect: item.stat?.favorite || 0,
            play: item.stat?.view || 0,
            danmaku: item.stat?.danmaku || 0,
            view_text_1: "",
        },
        fav_time: item.pubdate || 0,
        bvid: item.bvid || "",
    };
}

function isHasMore(v: boolean | string): boolean {
    if (typeof v === "boolean") return v;
    return v === "true";
}

export default function Component() {
    const { type, id, mid } = useParams<{ type: string; id: string; mid: string }>();

    const [allMedias, setAllMedias] = useState<MediaItem[]>([]);
    const [currentPage, setCurrentPage] = useState(1);
    const [hasMore, setHasMore] = useState(false);
    const targetPageRef = useRef<number | null>(null);
    const [nextPageTrigger, setNextPageTrigger] = useState<{ page: number } | null>(null);

    const routeName = type === "season" ? "BilibiliSeasonGetPageRoute" : "BilibiliSeriesGetPageRoute";
    const idField = type === "season" ? "season_id" : "series_id";
    const typeLabel = type === "season" ? "合集" : "系列";

    useEffect(() => {
        setAllMedias([]);
        setCurrentPage(1);
        setHasMore(false);
        setNextPageTrigger(null);
        targetPageRef.current = null;
    }, [type, id, mid]);

    const { data: firstPageResult, loading, error } = useAppFetch<GetPageResult>({
        appPath: id && mid
            ? `/api/v1/${routeName}?_id=${encodeURIComponent(id)}&_mid=${encodeURIComponent(mid)}`
            : undefined,
        init: id && mid ? {
            method: "POST",
            body: JSON.stringify({ [idField]: id, mid, page: 1 }),
        } : undefined,
        timeout: 30_000,
    });

    const { data: nextPageResult, loading: nextPageLoading, error: nextPageError } = useAppFetch<GetPageResult>({
        appPath: nextPageTrigger != null && id && mid
            ? `/api/v1/${routeName}?_id=${encodeURIComponent(id)}&_mid=${encodeURIComponent(mid)}&_page=${nextPageTrigger.page}`
            : undefined,
        init: nextPageTrigger != null && id && mid ? {
            method: "POST",
            body: JSON.stringify({ [idField]: id, mid, page: nextPageTrigger.page }),
        } : undefined,
        timeout: 30_000,
    });

    useEffect(() => {
        if (firstPageResult) {
            setAllMedias(firstPageResult.videos.map(archiveToMedia));
            setCurrentPage(1);
            setHasMore(isHasMore(firstPageResult.has_more));
            setNextPageTrigger(null);
            targetPageRef.current = null;
        }
    }, [firstPageResult]);

    useEffect(() => {
        if (nextPageResult) {
            const newPage = nextPageResult.page;
            setAllMedias(prev => [...prev, ...nextPageResult.videos.map(archiveToMedia)]);
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
        if (!id || !mid || nextPageLoading) return;
        if (page <= currentPage) {
            document.getElementById(`bilibili-video-page-${page}`)
                ?.scrollIntoView({ behavior: "smooth", block: "start" });
            return;
        }
        targetPageRef.current = page;
        setNextPageTrigger({ page: currentPage + 1 });
    };

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
                    获取{typeLabel}视频列表中...
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
                        <span className={`text-xs font-semibold px-2 py-0.5 rounded ${
                            type === "season"
                                ? "bg-purple-100 text-purple-700"
                                : "bg-teal-100 text-teal-700"
                        }`}>
                            {typeLabel}
                        </span>
                        <span className="text-sm text-gray-500">
                            ID: {id}
                        </span>
                        <span className="text-sm text-gray-500">
                            UP主: {mid}
                        </span>
                    </div>
                </div>
            )}

            <BilibiliVideoList
                medias={allMedias.length > 0 ? allMedias : firstPageResult?.videos.map(archiveToMedia)}
                nextPageSlot={nextPageSlot}
                currentPage={currentPage}
                onJumpToPage={handleJumpToPage}
                pageLoading={nextPageLoading}
            />
        </>
    );
}
