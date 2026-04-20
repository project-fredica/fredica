import { useEffect, useState } from "react";
import { Outlet, useLocation, useNavigate } from "react-router";
import { SyncSourceCreateButton } from "~/components/bilibili/SyncSourceCreateButton";

type SortOrder = "pubdate" | "favorite" | "view";

const ORDER_OPTIONS: { value: SortOrder; label: string }[] = [
    { value: "pubdate", label: "最新发布" },
    { value: "view", label: "最多播放" },
    { value: "favorite", label: "最多收藏" },
];

function parseMid(input: string): string | null {
    const trimmed = input.trim();

    const urlMatch = trimmed.match(/space\.bilibili\.com\/(\d+)/);
    if (urlMatch) return urlMatch[1];

    if (/^\d+$/.test(trimmed)) return trimmed;

    return null;
}

function validate(raw: string): string | null {
    if (!raw) return null;
    const mid = parseMid(raw);
    if (!mid) return "请输入UP主空间链接或UID（示例：https://space.bilibili.com/12345 或 12345）";
    return null;
}

export default function Component() {
    const navigate = useNavigate();
    const { pathname } = useLocation();
    const [urlInput, setUrlInput] = useState("");
    const [order, setOrder] = useState<SortOrder>("pubdate");
    const [validationError, setValidationError] = useState<string | null>(null);

    const paramsFromUrl = pathname.match(/\/uploader\/(\d+)\/(\w+)$/);
    useEffect(() => {
        if (paramsFromUrl) {
            setUrlInput(paramsFromUrl[1]);
            const urlOrder = paramsFromUrl[2] as SortOrder;
            if (ORDER_OPTIONS.some(o => o.value === urlOrder)) {
                setOrder(urlOrder);
            }
        }
    }, [paramsFromUrl?.[1], paramsFromUrl?.[2]]);

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
        const mid = parseMid(raw)!;
        const base = pathname.replace(/\/\d+\/\w+$/, "");
        navigate(`${base}/${mid}/${order}`);
    };

    return (
        <>
            <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                <h2 className="text-base sm:text-lg font-semibold text-gray-900 mb-3 sm:mb-4">输入UP主视频信息</h2>
                <form onSubmit={handleSubmit} className="space-y-4">
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">
                            UP主空间链接或UID
                        </label>
                        <input
                            type="text"
                            value={urlInput}
                            onChange={(e) => handleChange(e.target.value)}
                            placeholder="请输入UP主空间链接或UID（示例：https://space.bilibili.com/12345）"
                            className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent ${
                                validationError ? "border-red-400 bg-red-50" : "border-gray-300"
                            }`}
                        />
                        {validationError && (
                            <p className="mt-1.5 text-xs text-red-600">{validationError}</p>
                        )}
                    </div>
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">
                            排序方式
                        </label>
                        <select
                            value={order}
                            onChange={(e) => setOrder(e.target.value as SortOrder)}
                            className="w-full md:w-48 px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                        >
                            {ORDER_OPTIONS.map(o => (
                                <option key={o.value} value={o.value}>{o.label}</option>
                            ))}
                        </select>
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                        <button
                            type="submit"
                            disabled={!urlInput.trim()}
                            className="w-full md:w-auto px-6 py-2 bg-blue-600 text-white font-medium rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            获取视频列表
                        </button>
                        {paramsFromUrl && (
                            <SyncSourceCreateButton
                                syncType="bilibili_uploader"
                                platformConfig={{ mid: Number(paramsFromUrl[1]) }}
                            />
                        )}
                    </div>
                </form>
            </div>

            <Outlet />
        </>
    );
}
