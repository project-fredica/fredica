import { useState } from "react";
import { Loader, Search } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { SyncSourceCreateButton } from "~/components/bilibili/SyncSourceCreateButton";
import { BilibiliVideoList, type MediaItem } from "~/components/bilibili/BilibiliVideoList";
import { print_error, reportHttpError } from "~/util/error_handler";

// ─── Types ────────────────────────────────────────────────────────────────────

interface VideoInfoResult {
    bvid: string;
    title: string;
    cover: string;
    desc: string;
    duration: number;
    owner: { mid: number; name: string; face: string };
    stat: { view: number; danmaku: number; favorite: number; coin: number; like: number; share: number };
    pages: { page: number; part: string; duration: number; first_frame: string }[];
    error?: string;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function extractBvid(input: string): string {
    const trimmed = input.trim();
    const urlMatch = trimmed.match(/BV[a-zA-Z0-9]+/);
    if (urlMatch) return urlMatch[0];
    return trimmed;
}

function buildMediaItems(info: VideoInfoResult): MediaItem[] {
    const pages = info.pages ?? [];
    if (pages.length <= 1) {
        return [{
            id: 0,
            title: info.title,
            cover: info.cover,
            intro: info.desc,
            page: 1,
            duration: info.duration,
            upper: { name: info.owner.name, face: info.owner.face },
            cnt_info: { collect: info.stat.favorite, play: info.stat.view, danmaku: info.stat.danmaku, view_text_1: '' },
            fav_time: 0,
            bvid: info.bvid,
        }];
    }
    return pages.map(p => ({
        id: 0,
        title: p.part || `P${p.page}`,
        cover: p.first_frame || info.cover,
        intro: '',
        page: p.page,
        duration: p.duration,
        upper: { name: info.owner.name, face: info.owner.face },
        cnt_info: { collect: info.stat.favorite, play: info.stat.view, danmaku: info.stat.danmaku, view_text_1: '' },
        fav_time: 0,
        bvid: info.bvid,
        dbId: `bilibili_bvid__${info.bvid}__P${p.page}`,
    }));
}

// ─── Component ────────────────────────────────────────────────────────────────

export default function MultiPartPage() {
    const [urlInput, setUrlInput] = useState('');
    const [medias, setMedias] = useState<MediaItem[]>([]);
    const [loading, setLoading] = useState(false);
    const [currentBvid, setCurrentBvid] = useState('');

    const { apiFetch } = useAppFetch();

    // ── Fetch video info ──────────────────────────────────────────────────

    const handleFetch = async (e: React.FormEvent) => {
        e.preventDefault();
        const bvid = extractBvid(urlInput);
        if (!bvid) return;

        setLoading(true);
        setMedias([]);
        setCurrentBvid(bvid);

        try {
            const { resp, data } = await apiFetch<VideoInfoResult>('/api/v1/BilibiliVideoGetInfoRoute', {
                method: 'POST',
                body: JSON.stringify({ bvid }),
            });
            if (!resp.ok) {
                reportHttpError('获取视频信息失败', resp);
                return;
            }
            const errMsg = (data as unknown as Record<string, unknown>)?.error;
            if (typeof errMsg === 'string') {
                print_error({ reason: `获取视频信息失败: ${errMsg}`, err: new Error(errMsg) });
                return;
            }
            if (data) {
                setMedias(buildMediaItems(data));
            }
        } catch (err) {
            print_error({ reason: '获取视频信息网络错误', err });
        } finally {
            setLoading(false);
        }
    };

    // ── Render ────────────────────────────────────────────────────────────

    return (
        <div className="space-y-4">
            {/* Input form */}
            <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                <h2 className="text-base sm:text-lg font-semibold text-gray-900 mb-4">多P视频导入</h2>
                <form onSubmit={handleFetch} className="flex flex-col sm:flex-row gap-2">
                    <input
                        type="text"
                        value={urlInput}
                        onChange={e => setUrlInput(e.target.value)}
                        placeholder="输入 BVID 或 B 站视频链接"
                        className="flex-1 px-4 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                    />
                    <button
                        type="submit"
                        disabled={!urlInput.trim() || loading}
                        className="flex items-center justify-center gap-1.5 px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {loading
                            ? <Loader className="w-4 h-4 animate-spin" />
                            : <Search className="w-4 h-4" />
                        }
                        获取视频
                    </button>
                </form>
                {currentBvid && medias.length > 0 && (
                    <SyncSourceCreateButton
                        syncType="bilibili_video_pages"
                        platformConfig={{ bvid: currentBvid }}
                    />
                )}
            </div>

            {/* Video list */}
            {medias.length > 0 && (
                <BilibiliVideoList medias={medias} />
            )}
        </div>
    );
}
