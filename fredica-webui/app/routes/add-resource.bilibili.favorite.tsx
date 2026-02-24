import { useState } from "react";
import { useAppFetch, useImageProxyUrl } from "~/utils/requests";
import { BilibiliVideoList, type MediaItem } from "~/components/bilibili/BilibiliVideoList";

interface FavoriteInfo {
    title: string;
    cover: string;
    intro: string;
    media_count: number;
    upper: { name: string; face: string };
}

interface BilibiliFavoriteVideoListResult {
    ids_list: { id: number; bvid: string }[];
    first_page: {
        info: FavoriteInfo;
        medias: MediaItem[];
        has_more: boolean;
    };
}

/** 从链接或纯数字中提取 fid */
function extractFid(input: string): string {
    const match = input.match(/[?&]fid=(\d+)/);
    if (match) return match[1];
    return input.trim();
}

export default function Component() {
    const buildProxyUrl = useImageProxyUrl();
    const [urlInput, setUrlInput] = useState('');
    const [submittedFid, setSubmittedFid] = useState<string | null>(null);

    // 将 fid 嵌入 URL，使 useEffect 在每次提交时感知到 url 变化并重新请求
    const { data: result, loading, error } = useAppFetch<BilibiliFavoriteVideoListResult>({
        appPath: submittedFid != null
            ? `/api/v1/BilibiliFavoriteGetVideoListRoute?_fid=${encodeURIComponent(submittedFid)}`
            : undefined,
        init: submittedFid != null ? {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fid: submittedFid }),
        } : undefined,
        timeout: 30_000,
    });

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();
        const fid = extractFid(urlInput);
        if (!fid) return;
        setSubmittedFid(fid);
    };

    return (
        <>
            {/* 表单 */}
            <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                <h2 className="text-base sm:text-lg font-semibold text-gray-900 mb-3 sm:mb-4">输入收藏夹信息</h2>
                <form onSubmit={handleSubmit} className="space-y-4">
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">
                            收藏夹链接或ID
                        </label>
                        <input
                            type="text"
                            value={urlInput}
                            onChange={(e) => setUrlInput(e.target.value)}
                            placeholder="请输入收藏夹链接或ID"
                            className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                        />
                    </div>
                    {error && (
                        <p className="text-sm text-red-600">{error.message}</p>
                    )}
                    <button
                        type="submit"
                        disabled={loading || !urlInput.trim()}
                        className="w-full md:w-auto px-6 py-2 bg-blue-600 text-white font-medium rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {loading ? '获取中…' : '获取视频列表'}
                    </button>
                </form>
            </div>

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
            <BilibiliVideoList medias={result?.first_page.medias} />
        </>
    );
}
