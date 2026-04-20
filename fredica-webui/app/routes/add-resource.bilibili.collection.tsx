import { useEffect, useState } from "react";
import { Outlet, useLocation, useNavigate } from "react-router";
import { SyncSourceCreateButton } from "~/components/bilibili/SyncSourceCreateButton";

type CollectionType = "season" | "series";

interface ParsedCollection {
    type: CollectionType;
    id: string;
    mid: string;
}

function parseCollectionUrl(input: string): ParsedCollection | null {
    const trimmed = input.trim();

    // season: https://space.bilibili.com/{mid}/channel/collectiondetail?sid={season_id}
    const seasonMatch = trimmed.match(/space\.bilibili\.com\/(\d+)\/channel\/collectiondetail\?sid=(\d+)/);
    if (seasonMatch) return { type: "season", mid: seasonMatch[1], id: seasonMatch[2] };

    // series: https://space.bilibili.com/{mid}/channel/seriesdetail?sid={series_id}
    const seriesMatch = trimmed.match(/space\.bilibili\.com\/(\d+)\/channel\/seriesdetail\?sid=(\d+)/);
    if (seriesMatch) return { type: "series", mid: seriesMatch[1], id: seriesMatch[2] };

    // season alt: https://www.bilibili.com/medialist/detail/ml{season_id} — not common, skip
    // season alt: collectiondetail with & params
    const seasonMatch2 = trimmed.match(/space\.bilibili\.com\/(\d+)\/channel\/collectiondetail[^?]*\?[^]*?sid=(\d+)/);
    if (seasonMatch2) return { type: "season", mid: seasonMatch2[1], id: seasonMatch2[2] };

    const seriesMatch2 = trimmed.match(/space\.bilibili\.com\/(\d+)\/channel\/seriesdetail[^?]*\?[^]*?sid=(\d+)/);
    if (seriesMatch2) return { type: "series", mid: seriesMatch2[1], id: seriesMatch2[2] };

    return null;
}

function validate(raw: string): string | null {
    if (!raw) return null;
    const parsed = parseCollectionUrl(raw);
    if (!parsed) return "请输入合集或系列链接（示例：https://space.bilibili.com/12345/channel/collectiondetail?sid=67890）";
    return null;
}

export default function Component() {
    const navigate = useNavigate();
    const { pathname } = useLocation();
    const [urlInput, setUrlInput] = useState("");
    const [validationError, setValidationError] = useState<string | null>(null);

    const paramsFromUrl = pathname.match(/\/(season|series)\/([^/]+)\/([^/]+)$/);
    useEffect(() => {
        if (paramsFromUrl) {
            setUrlInput(`https://space.bilibili.com/${paramsFromUrl[3]}/channel/${paramsFromUrl[1] === "season" ? "collectiondetail" : "seriesdetail"}?sid=${paramsFromUrl[2]}`);
        }
    }, [paramsFromUrl?.[1], paramsFromUrl?.[2], paramsFromUrl?.[3]]);

    const handleChange = (value: string) => {
        setUrlInput(value);
        setValidationError(validate(value.trim()));
    };

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();
        const raw = urlInput.trim();
        const error = validate(raw);
        if (error) { setValidationError(error); return; }

        setValidationError(null);
        const parsed = parseCollectionUrl(raw)!;
        const base = pathname.replace(/\/(season|series)\/[^/]+\/[^/]+$/, "");
        navigate(`${base}/${parsed.type}/${parsed.id}/${parsed.mid}`);
    };

    return (
        <>
            <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                <h2 className="text-base sm:text-lg font-semibold text-gray-900 mb-3 sm:mb-4">输入视频合集信息</h2>
                <form onSubmit={handleSubmit} className="space-y-4">
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">
                            合集或系列链接
                        </label>
                        <input
                            type="text"
                            value={urlInput}
                            onChange={(e) => handleChange(e.target.value)}
                            placeholder="请输入合集或系列链接（collectiondetail 或 seriesdetail）"
                            className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent ${
                                validationError ? "border-red-400 bg-red-50" : "border-gray-300"
                            }`}
                        />
                        {validationError && (
                            <p className="mt-1.5 text-xs text-red-600">{validationError}</p>
                        )}
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                        <button
                            type="submit"
                            disabled={!urlInput.trim()}
                            className="w-full md:w-auto px-6 py-2 bg-blue-600 text-white font-medium rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            获取视频列表
                        </button>
                        {paramsFromUrl && (() => {
                            const [, pType, pId, pMid] = paramsFromUrl;
                            const syncType = pType === "season" ? "bilibili_season" : "bilibili_series";
                            const platformConfig = pType === "season"
                                ? { season_id: Number(pId), mid: Number(pMid) }
                                : { series_id: Number(pId), mid: Number(pMid) };
                            return (
                                <SyncSourceCreateButton
                                    syncType={syncType}
                                    platformConfig={platformConfig}
                                />
                            );
                        })()}
                    </div>
                </form>
            </div>

            <Outlet />
        </>
    );
}
