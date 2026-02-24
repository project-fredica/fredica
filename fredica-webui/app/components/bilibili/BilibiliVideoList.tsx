import { type ReactNode, useState } from "react";
import { ExternalLink, Download, Play, Eye, Heart, MessageSquare } from "lucide-react";
import { useImageProxyUrl } from "~/utils/requests";

export interface MediaItem {
    id: number;
    title: string;
    cover: string;
    intro: string;
    page: number;
    duration: number; // seconds
    upper: {
        name: string;
        face: string;
    };
    cnt_info: {
        collect: number;
        play: number;
        danmaku: number;
        view_text_1: string;
    };
    fav_time: number; // unix timestamp
    bvid: string;
}


function formatDuration(seconds: number): string {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    if (h > 0) {
        return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    }
    return `${m}:${String(s).padStart(2, '0')}`;
}

function formatCount(n: number): string {
    if (n >= 10000) return `${(n / 10000).toFixed(1)}万`;
    return String(n);
}

function formatFavDate(ts: number): string {
    const d = new Date(ts * 1000);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const PAGE_SIZE = 20;

/** 计算哪些页码需要显示（含省略号占位符 0） */
function buildPageWindows(totalPages: number, loadedPage: number): number[] {
    const show = new Set<number>();
    // 始终展示首末两页
    show.add(1);
    show.add(2);
    show.add(totalPages - 1);
    show.add(totalPages);
    // 当前加载页附近 ±2
    for (let d = -2; d <= 2; d++) {
        const p = loadedPage + d;
        if (p >= 1 && p <= totalPages) show.add(p);
    }
    const sorted = Array.from(show).sort((a, b) => a - b);
    // 在不连续处插入 0 作为省略号占位
    const result: number[] = [];
    let prev = 0;
    for (const p of sorted) {
        if (prev > 0 && p > prev + 1) result.push(0);
        result.push(p);
        prev = p;
    }
    return result;
}

export function BilibiliVideoList(param: {
    medias?: MediaItem[];
    nextPageSlot?: ReactNode;
    /** 已加载到的最新页码（1-indexed） */
    currentPage?: number;
    /** 总页数（由 ids_list.length / PAGE_SIZE 算出） */
    totalPages?: number;
    /** 总视频数（ids_list.length） */
    totalCount?: number;
    /** 点击未加载页时的回调，由父组件处理实际请求 */
    onJumpToPage?: (page: number) => void;
    /** 是否正在加载某一页 */
    pageLoading?: boolean;
}) {
    const { medias, nextPageSlot, currentPage, totalPages, totalCount, onJumpToPage, pageLoading } = param;
    const buildProxyUrl = useImageProxyUrl();
    const [selectedBvids, setSelectedBvids] = useState<Set<string>>(new Set());

    const toggle = (bvid: string) => {
        const next = new Set(selectedBvids);
        if (next.has(bvid)) next.delete(bvid); else next.add(bvid);
        setSelectedBvids(next);
    };

    if (!medias || medias.length === 0) {
        return (
            <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-sm text-gray-400">
                暂无视频
            </div>
        );
    }

    const allSelected = medias.length > 0 && selectedBvids.size === medias.length;

    const toggleAll = () => {
        setSelectedBvids(allSelected ? new Set() : new Set(medias.map(v => v.bvid)));
    };

    const scrollToPage = (page: number) => {
        document.getElementById(`bilibili-video-page-${page}`)
            ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    };

    const showPageNav = totalPages !== undefined && totalPages > 1;
    const loadedPage = currentPage ?? 1;

    return (
        <div className="bg-white rounded-lg border border-gray-200">
            {/* Header */}
            <div className="flex items-center justify-between px-4 sm:px-6 py-3 border-b border-gray-100">
                <div className="flex items-center gap-3">
                    <input
                        type="checkbox"
                        checked={allSelected}
                        onChange={toggleAll}
                        className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="text-sm text-gray-500">
                        {totalCount !== undefined
                            ? <>已加载 <span className="font-medium">{medias.length}</span> / {totalCount} 个视频</>
                            : <>共 <span className="font-medium">{medias.length}</span> 个视频</>
                        }
                        {selectedBvids.size > 0 && (
                            <span className="text-blue-600 ml-1">· 已选 {selectedBvids.size} 个</span>
                        )}
                    </span>
                </div>
                {selectedBvids.size > 0 && (
                    <div className="flex gap-2">
                        <button className="px-3 py-1.5 text-xs font-medium text-green-700 bg-green-50 border border-green-200 rounded-lg hover:bg-green-100 transition-colors">
                            批量下载 ({selectedBvids.size})
                        </button>
                        <button className="px-3 py-1.5 text-xs font-medium text-purple-700 bg-purple-50 border border-purple-200 rounded-lg hover:bg-purple-100 transition-colors">
                            批量分析 ({selectedBvids.size})
                        </button>
                    </div>
                )}
            </div>

            {/* Sticky page nav bar */}
            {showPageNav && (
                <div className="sticky top-0 z-10 bg-white border-b border-gray-100 px-4 sm:px-6 py-2 flex items-center gap-1.5 flex-wrap shadow-sm">
                    <span className="text-xs text-gray-400 mr-1 shrink-0">
                        第 {loadedPage}/{totalPages} 页
                    </span>
                    {buildPageWindows(totalPages!, loadedPage).map((p, i) =>
                        p === 0 ? (
                            <span key={`ellipsis-${i}`} className="text-xs text-gray-300 select-none px-0.5">…</span>
                        ) : (
                            <button
                                key={p}
                                onClick={() => p <= loadedPage ? scrollToPage(p) : onJumpToPage?.(p)}
                                disabled={pageLoading === true && p > loadedPage}
                                title={p <= loadedPage ? `跳转到第 ${p} 页` : `加载第 ${p} 页`}
                                className={
                                    `min-w-7 h-6 px-1.5 text-xs rounded font-mono transition-colors ` +
                                    (p <= loadedPage
                                        ? 'bg-blue-100 text-blue-700 hover:bg-blue-200 cursor-pointer'
                                        : 'bg-gray-50 text-gray-400 border border-gray-200 hover:bg-blue-50 hover:text-blue-500 hover:border-blue-200 cursor-pointer') +
                                    (pageLoading && p > loadedPage ? ' opacity-40 pointer-events-none' : '')
                                }
                            >
                                {p}
                            </button>
                        )
                    )}
                    {pageLoading && (
                        <span className="text-xs text-blue-500 ml-1 animate-pulse">加载中…</span>
                    )}
                </div>
            )}

            {/* List */}
            <div className="divide-y divide-gray-100" role="list">
                {medias.map((media, index) => {
                    const selected = selectedBvids.has(media.bvid);
                    // 每一页（每 PAGE_SIZE 条）的第一条打上 id 锚点供滚动定位
                    const pageAnchorId = index % PAGE_SIZE === 0
                        ? `bilibili-video-page-${Math.floor(index / PAGE_SIZE) + 1}`
                        : undefined;
                    return (
                        <div
                            key={media.bvid}
                            id={pageAnchorId}
                            className={`flex gap-3 p-3 sm:p-4 transition-colors ${selected ? 'bg-blue-50' : 'hover:bg-gray-50'}`}
                        >
                            {/* Checkbox */}
                            <div className="flex-shrink-0 pt-1">
                                <input
                                    type="checkbox"
                                    checked={selected}
                                    onChange={() => toggle(media.bvid)}
                                    className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                                />
                            </div>

                            {/* Cover */}
                            <div className="relative flex-shrink-0">
                                <img
                                    src={buildProxyUrl(media.cover)}
                                    alt={media.title}
                                    className="w-32 sm:w-40 h-[72px] sm:h-[90px] object-cover rounded-lg bg-gray-100"
                                />
                                {/* Duration badge */}
                                <span className="absolute bottom-1 right-1 bg-black/70 text-white text-xs px-1 py-0.5 rounded font-mono leading-none">
                                    {formatDuration(media.duration)}
                                </span>
                                {/* Multi-part badge */}
                                {media.page > 1 && (
                                    <span className="absolute top-1 left-1 bg-blue-600/90 text-white text-xs px-1.5 py-0.5 rounded leading-none">
                                        {media.page}P
                                    </span>
                                )}
                            </div>

                            {/* Content */}
                            <div className="flex-1 min-w-0 flex flex-col justify-between gap-1">
                                <h3 className="text-sm font-medium text-gray-900 line-clamp-2 leading-snug">
                                    {media.title}
                                </h3>
                                {/* UP主 */}
                                <div className="flex items-center gap-1.5">
                                    <img
                                        src={buildProxyUrl(media.upper.face)}
                                        alt={media.upper.name}
                                        className="w-4 h-4 rounded-full flex-shrink-0"
                                    />
                                    <span className="text-xs text-gray-500 truncate">{media.upper.name}</span>
                                </div>
                                {/* Stats */}
                                <div className="flex items-center gap-3 text-xs text-gray-400">
                                    <span className="flex items-center gap-0.5">
                                        <Eye className="w-3 h-3" />
                                        {media.cnt_info.view_text_1 || formatCount(media.cnt_info.play)}
                                    </span>
                                    <span className="flex items-center gap-0.5">
                                        <Heart className="w-3 h-3" />
                                        {formatCount(media.cnt_info.collect)}
                                    </span>
                                    <span className="flex items-center gap-0.5">
                                        <MessageSquare className="w-3 h-3" />
                                        {formatCount(media.cnt_info.danmaku)}
                                    </span>
                                </div>
                                {/* BV + fav date */}
                                <div className="flex items-center justify-between">
                                    <span className="text-xs text-gray-400 font-mono">{media.bvid}</span>
                                    <span className="text-xs text-gray-400 hidden sm:block">
                                        收藏于 {formatFavDate(media.fav_time)}
                                    </span>
                                </div>
                            </div>

                            {/* Actions */}
                            <div className="flex flex-col gap-1.5 flex-shrink-0 justify-center">
                                <button
                                    onClick={() => window.open(`https://www.bilibili.com/video/${media.bvid}`, '_blank')}
                                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors whitespace-nowrap"
                                >
                                    <ExternalLink className="w-3.5 h-3.5" />
                                    打开
                                </button>
                                <button
                                    onClick={() => console.log('download', media.bvid)}
                                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-green-700 bg-green-50 rounded-lg hover:bg-green-100 transition-colors"
                                >
                                    <Download className="w-3.5 h-3.5" />
                                    下载
                                </button>
                                <button
                                    onClick={() => console.log('analyze', media.bvid)}
                                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors"
                                >
                                    <Play className="w-3.5 h-3.5" />
                                    分析
                                </button>
                            </div>
                        </div>
                    );
                })}
            </div>

            {/* Next page slot */}
            {nextPageSlot && (
                <div className="px-4 sm:px-6 py-3 border-t border-gray-100 flex justify-center">
                    {nextPageSlot}
                </div>
            )}
        </div>
    );
}


const exampleData = {
    "ids_list": [
        {
            "id": 115669760738456,
            "type": 2,
            "bv_id": "BV1pU2JB5EBu",
            "bvid": "BV1pU2JB5EBu"
        },
        {
            "id": 115542170017459,
            "type": 2,
            "bv_id": "BV1e7CbB1Ekg",
            "bvid": "BV1e7CbB1Ekg"
        },
        {
            "id": 115666405361681,
            "type": 2,
            "bv_id": "BV1mh2LBgE8S",
            "bvid": "BV1mh2LBgE8S"
        },
        {
            "id": 114662406360950,
            "type": 2,
            "bv_id": "BV1P5Mjz4E5c",
            "bvid": "BV1P5Mjz4E5c"
        },
        {
            "id": 113314407383978,
            "type": 2,
            "bv_id": "BV1hTmAYQEX3",
            "bvid": "BV1hTmAYQEX3"
        },
        {
            "id": 632610186,
            "type": 2,
            "bv_id": "BV1Lb4y1m7vD",
            "bvid": "BV1Lb4y1m7vD"
        },
        {
            "id": 112556093997812,
            "type": 2,
            "bv_id": "BV1ZcTQeUEgi",
            "bvid": "BV1ZcTQeUEgi"
        },
        {
            "id": 115561530919191,
            "type": 2,
            "bv_id": "BV1AsCaBBETv",
            "bvid": "BV1AsCaBBETv"
        },
        {
            "id": 114920641336304,
            "type": 2,
            "bv_id": "BV1798hzkErL",
            "bvid": "BV1798hzkErL"
        },
        {
            "id": 114765804341340,
            "type": 2,
            "bv_id": "BV1g9gZzZE8V",
            "bvid": "BV1g9gZzZE8V"
        },
        {
            "id": 114090840162564,
            "type": 2,
            "bv_id": "BV13a9FY8EUR",
            "bvid": "BV13a9FY8EUR"
        }
    ],
    "first_page": {
        "info": {
            "id": 3730985189,
            "fid": 37309851,
            "mid": 42612289,
            "attr": 22,
            "title": "电气",
            "cover": "http://i1.hdslb.com/bfs/archive/f26986693f11a5c62aa0a995d3085b8aa7d797de.jpg",
            "upper": {
                "mid": 42612289,
                "name": "tcsnzh_",
                "face": "https://i1.hdslb.com/bfs/face/05579276c15c8aecc65eebf24b0cbedbb641d304.jpg",
                "followed": false,
                "vip_type": 1,
                "vip_statue": 0
            },
            "cover_type": 2,
            "cnt_info": {
                "collect": 0,
                "play": 0,
                "thumb_up": 0,
                "share": 0
            },
            "type": 11,
            "intro": "",
            "ctime": 1763876017,
            "mtime": 1764300334,
            "state": 0,
            "fav_state": 0,
            "like_state": 0,
            "media_count": 11,
            "is_top": false
        },
        "medias": [
            {
                "id": 115669760738456,
                "type": 2,
                "title": "为什么老电工接线 特别喜欢多绕两圈",
                "cover": "http://i1.hdslb.com/bfs/archive/f26986693f11a5c62aa0a995d3085b8aa7d797de.jpg",
                "intro": "为什么老电工接线 特别喜欢多绕两圈",
                "page": 1,
                "duration": 145,
                "upper": {
                    "mid": 3546722580040633,
                    "name": "矮人爷爷带你学电",
                    "face": "https://i1.hdslb.com/bfs/face/f3a7b1940da415f9194b62cc969aba71275d2ddc.jpg",
                    "jump_link": ""
                },
                "attr": 0,
                "cnt_info": {
                    "collect": 246,
                    "play": 30917,
                    "danmaku": 21,
                    "vt": 0,
                    "play_switch": 0,
                    "reply": 0,
                    "view_text_1": "3.1万"
                },
                "link": "bilibili://video/115669760738456",
                "ctime": 1764980671,
                "pubtime": 1764980670,
                "fav_time": 1764985830,
                "bv_id": "BV1pU2JB5EBu",
                "bvid": "BV1pU2JB5EBu",
                "season": null,
                "ogv": null,
                "ugc": {
                    "first_cid": 34535047958
                },
                "media_list_link": "bilibili://music/playlist/playpage/3773597389?page_type=3&oid=115669760738456&otype=2"
            },
            {
                "id": 115542170017459,
                "type": 2,
                "title": "【干货】超全的电机知识完整版，同时附带实际案例教程，从入门到精通，少走99%的弯路！这还学不会，我退出机械圈！",
                "cover": "http://i2.hdslb.com/bfs/archive/fbb779ddbf7dd7bcb19f82a6e4ac3d9ffd116727.jpg",
                "intro": "一个冷知识：点赞是免费的！但是可以让辛苦做视频的UP主开心快乐一整天！！！\n我给大家准备了solidworks机械设计礼包，关注+评论即可得",
                "page": 18,
                "duration": 17947,
                "upper": {
                    "mid": 354319998,
                    "name": "中国机械设计频道",
                    "face": "https://i0.hdslb.com/bfs/face/52885431ea537e17fa6abec16b1b3def0bdf5d77.jpg",
                    "jump_link": ""
                },
                "attr": 0,
                "cnt_info": {
                    "collect": 408,
                    "play": 2380,
                    "danmaku": 1,
                    "vt": 0,
                    "play_switch": 0,
                    "reply": 0,
                    "view_text_1": "2380"
                },
                "link": "bilibili://video/115542170017459",
                "ctime": 1763033921,
                "pubtime": 1763033921,
                "fav_time": 1764985745,
                "bv_id": "BV1e7CbB1Ekg",
                "bvid": "BV1e7CbB1Ekg",
                "season": null,
                "ogv": null,
                "ugc": {
                    "first_cid": 25908613386
                },
                "media_list_link": "bilibili://music/playlist/playpage/3773597389?page_type=3&oid=115542170017459&otype=2"
            },
            {
                "id": 115666405361681,
                "type": 2,
                "title": "这个时代必须做互联网 给年轻电工们的八条嘱咐",
                "cover": "http://i2.hdslb.com/bfs/archive/dd7a39cc741460cb364ccaa5c245e3cd22d05729.jpg",
                "intro": "这个时代必须做互联网 给年轻电工们的八条嘱咐",
                "page": 1,
                "duration": 298,
                "upper": {
                    "mid": 3546722580040633,
                    "name": "矮人爷爷带你学电",
                    "face": "https://i1.hdslb.com/bfs/face/f3a7b1940da415f9194b62cc969aba71275d2ddc.jpg",
                    "jump_link": ""
                },
                "attr": 0,
                "cnt_info": {
                    "collect": 155,
                    "play": 3449,
                    "danmaku": 1,
                    "vt": 0,
                    "play_switch": 0,
                    "reply": 0,
                    "view_text_1": "3449"
                },
                "link": "bilibili://video/115666405361681",
                "ctime": 1764929371,
                "pubtime": 1764929371,
                "fav_time": 1764945394,
                "bv_id": "BV1mh2LBgE8S",
                "bvid": "BV1mh2LBgE8S",
                "season": null,
                "ogv": null,
                "ugc": {
                    "first_cid": 34523316234
                },
                "media_list_link": "bilibili://music/playlist/playpage/3773597389?page_type=3&oid=115666405361681&otype=2"
            },
            {
                "id": 114662406360950,
                "type": 2,
                "title": "我27年电工生涯2次电气事故教训：对电一定要有敬畏之心 。我的2次电气事故一次伤了人，一次损坏2台关键设备，使停产一周。电气事故无小事，希望你们一定要引以为鉴！",
                "cover": "http://i2.hdslb.com/bfs/archive/b5b5d9a837bb97ebb8f56d6daf419b97f3543f04.jpg",
                "intro": "-",
                "page": 1,
                "duration": 142,
                "upper": {
                    "mid": 667252800,
                    "name": "70后陌雪",
                    "face": "https://i0.hdslb.com/bfs/face/2a25f910d4132d616026bfa745c8e87f167bec4c.jpg",
                    "jump_link": ""
                },
                "attr": 0,
                "cnt_info": {
                    "collect": 642,
                    "play": 54180,
                    "danmaku": 45,
                    "vt": 0,
                    "play_switch": 0,
                    "reply": 0,
                    "view_text_1": "5.4万"
                },
                "link": "bilibili://video/114662406360950",
                "ctime": 1749609590,
                "pubtime": 1749609783,
                "fav_time": 1764310995,
                "bv_id": "BV1P5Mjz4E5c",
                "bvid": "BV1P5Mjz4E5c",
                "season": null,
                "ogv": null,
                "ugc": {
                    "first_cid": 30432167204
                },
                "media_list_link": "bilibili://music/playlist/playpage/3773597389?page_type=3&oid=114662406360950&otype=2"
            },
            {
                "id": 113314407383978,
                "type": 2,
                "title": "老电工教你看图接线",
                "cover": "http://i1.hdslb.com/bfs/archive/60daa239959cfdd50248bd531b563d7f98977018.jpg",
                "intro": "老电工教你看图接线",
                "page": 20,
                "duration": 17374,
                "upper": {
                    "mid": 499778261,
                    "name": "工控圈",
                    "face": "https://i2.hdslb.com/bfs/face/bd51ab7f171eeb1b2b979d918ef965c7fdde3223.jpg",
                    "jump_link": ""
                },
                "attr": 0,
                "cnt_info": {
                    "collect": 26058,
                    "play": 531609,
                    "danmaku": 555,
                    "vt": 0,
                    "play_switch": 0,
                    "reply": 0,
                    "view_text_1": "53.2万"
                },
                "link": "bilibili://video/113314407383978",
                "ctime": 1729040953,
                "pubtime": 1729040953,
                "fav_time": 1764310586,
                "bv_id": "BV1hTmAYQEX3",
                "bvid": "BV1hTmAYQEX3",
                "season": null,
                "ogv": null,
                "ugc": {
                    "first_cid": 26309101841
                },
                "media_list_link": "bilibili://music/playlist/playpage/3773597389?page_type=3&oid=113314407383978&otype=2"
            },
            {
                "id": 632610186,
                "type": 2,
                "title": "【合集】电路原理、电工学-适合零基础学习，配随堂练习",
                "cover": "http://i0.hdslb.com/bfs/archive/7e734cf9c392c6d0041a615f5f421433b09e978a.jpg",
                "intro": "本职工作比较繁忙 加上开的坑比较多 儿子还小还要花时间带 填起来比较费时费力毕竟要保证质量  抽空就会更新 希望同学们理解",
                "page": 105,
                "duration": 127323,
                "upper": {
                    "mid": 2460191,
                    "name": "老游真是太菜了",
                    "face": "https://i2.hdslb.com/bfs/face/708e6c11ad1f77d74d05d6aabb4bc472888e4a49.jpg",
                    "jump_link": ""
                },
                "attr": 0,
                "cnt_info": {
                    "collect": 85543,
                    "play": 1634332,
                    "danmaku": 15109,
                    "vt": 0,
                    "play_switch": 0,
                    "reply": 0,
                    "view_text_1": "163.4万"
                },
                "link": "bilibili://video/632610186",
                "ctime": 1630425617,
                "pubtime": 1630425614,
                "fav_time": 1764305174,
                "bv_id": "BV1Lb4y1m7vD",
                "bvid": "BV1Lb4y1m7vD",
                "season": null,
                "ogv": null,
                "ugc": {
                    "first_cid": 395049627
                },
                "media_list_link": "bilibili://music/playlist/playpage/3773597389?page_type=3&oid=632610186&otype=2"
            },
            {
                "id": 112556093997812,
                "type": 2,
                "title": "电工基础全集（新手必学电工课程） 电工工具典型电路控制传感器变频器技术 电工视频课程电气自动化入门精讲",
                "cover": "http://i2.hdslb.com/bfs/archive/134ede0b922708461a21d18fd7598d938732ef11.jpg",
                "intro": "本课程电工基础学完可以过渡到PLC编程学习！正在加更中。有配套PPT、软件、电气绘图。内容涵盖：电工基础、电气控制及元器件选型、电工工具、典型电路控制、传感器、变频器技术、仪表应用及线路故障诊断排除、电磁阀应用、PLC入门知识及西门子s7-1200plc入门讲解。\r\n本课程适合对象：对电气自动化技术零基础或有一点基础的，例如机械相关、转行、学生、入门不久等想进一步学习电气自动化技术的人员。\r\n喜欢的朋友 关注 硬币 收藏，感谢了亲。棒！",
                "page": 27,
                "duration": 31291,
                "upper": {
                    "mid": 3546688268536070,
                    "name": "PLC工程师余工",
                    "face": "https://i2.hdslb.com/bfs/face/737275938ad5b65292766cdd87bcc8848a6a275e.jpg",
                    "jump_link": ""
                },
                "attr": 0,
                "cnt_info": {
                    "collect": 89958,
                    "play": 2024005,
                    "danmaku": 6347,
                    "vt": 0,
                    "play_switch": 0,
                    "reply": 0,
                    "view_text_1": "202.4万"
                },
                "link": "bilibili://video/112556093997812",
                "ctime": 1717469958,
                "pubtime": 1717469958,
                "fav_time": 1764300296,
                "bv_id": "BV1ZcTQeUEgi",
                "bvid": "BV1ZcTQeUEgi",
                "season": null,
                "ogv": null,
                "ugc": {
                    "first_cid": 500001571036032
                },
                "media_list_link": "bilibili://music/playlist/playpage/3773597389?page_type=3&oid=112556093997812&otype=2"
            },
            {
                "id": 115561530919191,
                "type": 2,
                "title": "1500块钱的自制数控CNC雕刻机",
                "cover": "http://i1.hdslb.com/bfs/archive/aad917953c74b2d19f1bf10dc65b48791348c8f1.jpg",
                "intro": "这次转速24000每分钟，进给F800，下8毫米，吃0.3毫米，B右们看看这个性能如何",
                "page": 1,
                "duration": 205,
                "upper": {
                    "mid": 601473331,
                    "name": "爱搞机的何工",
                    "face": "https://i1.hdslb.com/bfs/face/cf1ef9c2045e317dfe6dbe8659b6f7a69c7572d5.jpg",
                    "jump_link": ""
                },
                "attr": 0,
                "cnt_info": {
                    "collect": 3786,
                    "play": 87916,
                    "danmaku": 43,
                    "vt": 0,
                    "play_switch": 0,
                    "reply": 0,
                    "view_text_1": "8.8万"
                },
                "link": "bilibili://video/115561530919191",
                "ctime": 1763330000,
                "pubtime": 1763330000,
                "fav_time": 1764231717,
                "bv_id": "BV1AsCaBBETv",
                "bvid": "BV1AsCaBBETv",
                "season": null,
                "ogv": null,
                "ugc": {
                    "first_cid": 34055455696
                },
                "media_list_link": "bilibili://music/playlist/playpage/3773597389?page_type=3&oid=115561530919191&otype=2"
            },
            {
                "id": 114920641336304,
                "type": 2,
                "title": "子承父业,自学考取电工证？！超有趣模拟游戏！【电工模拟器】",
                "cover": "http://i0.hdslb.com/bfs/archive/5bd866e83324ed9bcdf09b833e0564c6c38c119e.jpg",
                "intro": "游戏名：Electrical simulator\n好玩！有点小上头！玩完了还想玩啊啊啊！\n喜欢的视频的话不妨多多三连关注支持一下吖！！！非常感谢！",
                "page": 1,
                "duration": 1655,
                "upper": {
                    "mid": 359184884,
                    "name": "无敌帅气の小黄油",
                    "face": "https://i2.hdslb.com/bfs/face/d658b159d41eb5d6e67f338f525bf62012ea021f.jpg",
                    "jump_link": ""
                },
                "attr": 0,
                "cnt_info": {
                    "collect": 684,
                    "play": 39733,
                    "danmaku": 77,
                    "vt": 0,
                    "play_switch": 0,
                    "reply": 0,
                    "view_text_1": "4万"
                },
                "link": "bilibili://video/114920641336304",
                "ctime": 1753550419,
                "pubtime": 1753567380,
                "fav_time": 1764120651,
                "bv_id": "BV1798hzkErL",
                "bvid": "BV1798hzkErL",
                "season": null,
                "ogv": null,
                "ugc": {
                    "first_cid": 31302485961
                },
                "media_list_link": "bilibili://music/playlist/playpage/3773597389?page_type=3&oid=114920641336304&otype=2"
            },
            {
                "id": 114765804341340,
                "type": 2,
                "title": "空气断路器让人拍案叫绝的设计，它究竟如何保护电路安全？",
                "cover": "http://i0.hdslb.com/bfs/archive/2605c0ef19295f340feab4e5494d52fe3231f856.jpg",
                "intro": "空气断路器让人拍案叫绝的设计，它究竟如何保护电路安全？",
                "page": 1,
                "duration": 194,
                "upper": {
                    "mid": 447383713,
                    "name": "讲点理科普",
                    "face": "https://i1.hdslb.com/bfs/face/a07f5b156d330228bbb72a3c6ad5a30662ad40ee.jpg",
                    "jump_link": ""
                },
                "attr": 0,
                "cnt_info": {
                    "collect": 3443,
                    "play": 158378,
                    "danmaku": 45,
                    "vt": 0,
                    "play_switch": 0,
                    "reply": 0,
                    "view_text_1": "15.8万"
                },
                "link": "bilibili://video/114765804341340",
                "ctime": 1751187326,
                "pubtime": 1751196600,
                "fav_time": 1764118319,
                "bv_id": "BV1g9gZzZE8V",
                "bvid": "BV1g9gZzZE8V",
                "season": null,
                "ogv": null,
                "ugc": {
                    "first_cid": 30757618205
                },
                "media_list_link": "bilibili://music/playlist/playpage/3773597389?page_type=3&oid=114765804341340&otype=2"
            },
            {
                "id": 114090840162564,
                "type": 2,
                "title": "如何低成本自学PLC",
                "cover": "http://i0.hdslb.com/bfs/archive/3757d8a71d4f326ddb95ef7da1d0cc17f6818506.jpg",
                "intro": "",
                "page": 1,
                "duration": 1108,
                "upper": {
                    "mid": 1080788701,
                    "name": "amigo12",
                    "face": "https://i1.hdslb.com/bfs/face/3aebc84f50fceab178031653791b103979606959.jpg",
                    "jump_link": ""
                },
                "attr": 0,
                "cnt_info": {
                    "collect": 4043,
                    "play": 55351,
                    "danmaku": 22,
                    "vt": 0,
                    "play_switch": 0,
                    "reply": 0,
                    "view_text_1": "5.5万"
                },
                "link": "bilibili://video/114090840162564",
                "ctime": 1740888538,
                "pubtime": 1740888538,
                "fav_time": 1763876036,
                "bv_id": "BV13a9FY8EUR",
                "bvid": "BV13a9FY8EUR",
                "season": null,
                "ogv": null,
                "ugc": {
                    "first_cid": 28648210734
                },
                "media_list_link": "bilibili://music/playlist/playpage/3773597389?page_type=3&oid=114090840162564&otype=2"
            }
        ],
        "has_more": false,
        "ttl": 1771944214
    }
}