import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import { Bookmark, BookmarkCheck, Calendar, Eye, Film, Share2, ThumbsUp } from "lucide-react";
import { useAppFetch, useImageProxyUrl } from "~/utils/requests";
import { BilibiliVideoList, type MediaItem } from "~/components/bilibili/BilibiliVideoList";

const PAGE_SIZE = 20;

function formatCount(n: number): string {
    if (n >= 100_000_000) return `${(n / 100_000_000).toFixed(1)}亿`;
    if (n >= 10_000) return `${(n / 10_000).toFixed(1)}万`;
    return String(n);
}

function formatDate(ts: number): string {
    const d = new Date(ts * 1000);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

interface FavoriteInfo {
    id: number;
    fid: number;
    title: string;
    cover: string;
    intro: string;
    media_count: number;
    upper: {
        mid: number;
        name: string;
        face: string;
        followed: boolean;
        vip_type: number;
    };
    cnt_info: {
        collect: number;
        play: number;
        thumb_up: number;
        share: number;
    };
    ctime: number;
    mtime: number;
    is_top: boolean;
    fav_state: number;
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

            {/* 收藏夹信息 */}
            {result && (() => {
                const info = result.first_page.info;
                return (
                    <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6 space-y-3">
                        {/* 标题行：封面 + 主要信息 */}
                        <div className="flex gap-4 items-start">
                            {info.cover && (
                                <img
                                    src={buildProxyUrl(info.cover)}
                                    alt={info.title}
                                    className="w-28 h-[72px] object-cover rounded-lg flex-shrink-0 bg-gray-100"
                                />
                            )}
                            <div className="flex-1 min-w-0 space-y-1.5">
                                <div className="flex items-center gap-2 flex-wrap">
                                    <h2 className="text-base sm:text-lg font-semibold text-gray-900 leading-tight">
                                        {info.title}
                                    </h2>
                                    {info.is_top && (
                                        <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 shrink-0">
                                            已置顶
                                        </span>
                                    )}
                                    {info.fav_state === 1 && (
                                        <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded bg-pink-100 text-pink-700 shrink-0">
                                            已收藏
                                        </span>
                                    )}
                                </div>
                                {info.intro && (
                                    <p className="text-sm text-gray-500 line-clamp-2">{info.intro}</p>
                                )}
                                {/* UP主 */}
                                <a
                                    href={`https://space.bilibili.com/${info.upper.mid}`}
                                    target="_blank"
                                    rel="noreferrer"
                                    className="inline-flex items-center gap-1.5 text-xs text-gray-500 hover:text-blue-600 transition-colors"
                                >
                                    <img
                                        src={buildProxyUrl(info.upper.face)}
                                        alt={info.upper.name}
                                        className="w-4 h-4 rounded-full flex-shrink-0 bg-gray-100"
                                    />
                                    <span>{info.upper.name}</span>
                                    {info.upper.followed && (
                                        <span className="text-[10px] text-green-600">已关注</span>
                                    )}
                                </a>
                            </div>
                        </div>

                        {/* 统计数据 */}
                        <div className="flex flex-wrap gap-x-5 gap-y-1.5 text-xs text-gray-500 pt-1 border-t border-gray-100">
                            <span className="flex items-center gap-1">
                                <Film className="w-3.5 h-3.5 text-gray-400" />
                                {info.media_count} 个视频
                            </span>
                            <span className="flex items-center gap-1">
                                <Eye className="w-3.5 h-3.5 text-gray-400" />
                                {formatCount(info.cnt_info.play)} 播放
                            </span>
                            <span className="flex items-center gap-1">
                                <Bookmark className="w-3.5 h-3.5 text-gray-400" />
                                {formatCount(info.cnt_info.collect)} 收藏
                            </span>
                            <span className="flex items-center gap-1">
                                <ThumbsUp className="w-3.5 h-3.5 text-gray-400" />
                                {formatCount(info.cnt_info.thumb_up)} 点赞
                            </span>
                            <span className="flex items-center gap-1">
                                <Share2 className="w-3.5 h-3.5 text-gray-400" />
                                {formatCount(info.cnt_info.share)} 分享
                            </span>
                        </div>

                        {/* 时间 + fid */}
                        <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-400">
                            <span className="flex items-center gap-1">
                                <Calendar className="w-3 h-3" />
                                创建于 {formatDate(info.ctime)}
                            </span>
                        </div>
                    </div>
                );
            })()}

            {/* 视频列表 */}
            <BilibiliVideoList
                medias={allMedias.length > 0 ? allMedias : result?.first_page.medias}
                nextPageSlot={nextPageSlot}
                currentPage={currentPage}
                totalPages={totalPages}
                totalCount={totalCount}
                onJumpToPage={handleJumpToPage}
                pageLoading={nextPageLoading}
                fid={fid}
            />
        </>
    );
}
