import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import { useAppFetch, useImageProxyUrl } from "~/utils/requests";
import { BilibiliVideoList, type MediaItem } from "~/components/bilibili/BilibiliVideoList";

const PAGE_SIZE = 20;

interface FavoriteInfo {
    title: string;
    cover: string;
    intro: string;
    media_count: number;
    upper: { name: string; face: string };
}

interface BilibiliFavoriteVideoListResult {
    ids_list: { id: number; type: number; bvid: string }[];
    first_page: {
        info: FavoriteInfo;
        medias: MediaItem[];
        has_more: boolean;
    };
}

interface BilibiliFavoriteGetPageResult {
    fid: string;
    page: number;
    medias: MediaItem[];
    has_more: boolean;
}

export default function Component() {
    const { fid } = useParams<{ fid: string }>();
    const buildProxyUrl = useImageProxyUrl();

    // 累积的视频列表、当前已加载页、是否还有更多页
    const [allMedias, setAllMedias] = useState<MediaItem[]>([]);
    const [currentPage, setCurrentPage] = useState(1);
    const [hasMore, setHasMore] = useState(false);

    // 目标页：用于跳转时连续拉取直到目标页（不触发 re-render，避免闭包失效）
    const targetPageRef = useRef<number | null>(null);

    // 分页请求触发器：{ fid, page } 变化时触发请求
    const [nextPageTrigger, setNextPageTrigger] = useState<{ fid: string; page: number } | null>(null);

    // fid 变更时立即清空旧数据，避免新请求返回前短暂显示上一个收藏夹内容
    useEffect(() => {
        setAllMedias([]);
        setCurrentPage(1);
        setHasMore(false);
        setNextPageTrigger(null);
        targetPageRef.current = null;
    }, [fid]);

    // 将 fid 嵌入 URL，使 useAppFetch 在 fid 变化时感知并重新请求
    const { data: result, loading, error } = useAppFetch<BilibiliFavoriteVideoListResult>({
        appPath: fid != null
            ? `/api/v1/BilibiliFavoriteGetVideoListRoute?_fid=${encodeURIComponent(fid)}`
            : undefined,
        init: fid != null ? {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fid }),
        } : undefined,
        timeout: 30_000,
    });

    // 分页请求
    const { data: nextPageResult, loading: nextPageLoading, error: nextPageError } = useAppFetch<BilibiliFavoriteGetPageResult>({
        appPath: nextPageTrigger != null
            ? `/api/v1/BilibiliFavoriteGetPageRoute?_fid=${encodeURIComponent(nextPageTrigger.fid)}&_page=${nextPageTrigger.page}`
            : undefined,
        init: nextPageTrigger != null ? {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fid: nextPageTrigger.fid, page: nextPageTrigger.page }),
        } : undefined,
        timeout: 30_000,
    });

    // 初始结果到达时，重置累积列表
    useEffect(() => {
        if (result) {
            setAllMedias(result.first_page.medias ?? []);
            setCurrentPage(1);
            setHasMore(result.first_page.has_more);
            setNextPageTrigger(null);
            targetPageRef.current = null;
        }
    }, [result]);

    // 分页结果到达时，追加列表；若未达目标页则继续触发下一页请求
    useEffect(() => {
        if (nextPageResult) {
            const newPage = nextPageResult.page;
            setAllMedias(prev => [...prev, ...(nextPageResult.medias ?? [])]);
            setCurrentPage(newPage);
            setHasMore(nextPageResult.has_more);

            if (
                targetPageRef.current !== null &&
                newPage < targetPageRef.current &&
                nextPageResult.has_more
            ) {
                // 还没到目标页，继续加载下一页
                setNextPageTrigger({ fid: nextPageResult.fid, page: newPage + 1 });
            } else {
                targetPageRef.current = null;
            }
        }
    }, [nextPageResult]);

    /**
     * 跳转到指定页：
     * - 若目标页已加载，滚动定位到该页首条视频
     * - 若未加载，从下一页开始连续拉取直到目标页
     */
    const handleJumpToPage = (page: number) => {
        if (!fid || nextPageLoading) return;
        if (page <= currentPage) {
            document.getElementById(`bilibili-video-page-${page}`)
                ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
            return;
        }
        targetPageRef.current = page;
        setNextPageTrigger({ fid, page: currentPage + 1 });
    };

    // 从 ids_list 计算总数和总页数（ids_list 包含全量 ID，初次请求即返回）
    const totalCount = result?.ids_list.length;
    const totalPages = totalCount !== undefined && totalCount > 0
        ? Math.ceil(totalCount / PAGE_SIZE)
        : undefined;

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
                {nextPageLoading ? '加载中…' : `加载下一页（第 ${currentPage + 1} 页）`}
            </button>
        </div>
    ) : undefined;

    return (
        <>
            {/* 加载/错误状态 */}
            {loading && (
                <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-400">
                    获取中…
                </div>
            )}
            {error && !loading && (
                <div className="bg-white rounded-lg border border-gray-200 p-4">
                    <p className="text-sm text-red-600">{error.message}</p>
                </div>
            )}

            {/* 收藏夹信息摘要 */}
            {result && (
                <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                    <h2 className="text-base sm:text-lg font-semibold text-gray-900 mb-3">收藏夹信息</h2>
                    <div className="flex gap-4 items-start">
                        {result.first_page.info.cover && (
                            <img
                                src={buildProxyUrl(result.first_page.info.cover)}
                                alt={result.first_page.info.title}
                                className="w-24 h-16 object-cover rounded-lg flex-shrink-0"
                            />
                        )}
                        <div className="space-y-1">
                            <p className="font-medium text-gray-900">{result.first_page.info.title}</p>
                            {result.first_page.info.intro && (
                                <p className="text-sm text-gray-500">{result.first_page.info.intro}</p>
                            )}
                            <p className="text-sm text-gray-600">
                                共 <span className="font-medium">{result.first_page.info.media_count}</span> 个视频
                            </p>
                        </div>
                    </div>
                </div>
            )}

            {/* 视频列表 */}
            <BilibiliVideoList
                medias={allMedias.length > 0 ? allMedias : result?.first_page.medias}
                nextPageSlot={nextPageSlot}
                currentPage={currentPage}
                totalPages={totalPages}
                totalCount={totalCount}
                onJumpToPage={handleJumpToPage}
                pageLoading={nextPageLoading}
            />
        </>
    );
}
