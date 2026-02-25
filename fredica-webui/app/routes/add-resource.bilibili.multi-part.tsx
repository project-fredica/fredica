import { useState } from "react";
import { Loader, Search } from "lucide-react";
import { useAppFetch } from "~/utils/app_fetch";
import { CategoryPickerModal } from "~/components/bilibili/CategoryPickerModal";
import { useImageProxyUrl } from "~/utils/app_fetch";

// ─── Types ────────────────────────────────────────────────────────────────────

interface PageInfo {
    page: number;
    title: string;
    duration: number;
    cover: string;
}

interface SelectedPageEntry {
    page: number;
    title: string;
    duration: number;
    cover: string;
    selected: boolean;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function extractBvid(input: string): string {
    const trimmed = input.trim();
    // Try to extract BVID from a URL like https://www.bilibili.com/video/BV1abc123
    const urlMatch = trimmed.match(/BV[a-zA-Z0-9]+/);
    if (urlMatch) return urlMatch[0];
    return trimmed;
}

function formatDuration(seconds: number): string {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${String(s).padStart(2, '0')}`;
}

// ─── Component ────────────────────────────────────────────────────────────────

export default function MultiPartPage() {
    const [urlInput, setUrlInput] = useState('');
    const [pages, setPages] = useState<SelectedPageEntry[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [currentBvid, setCurrentBvid] = useState('');
    const [pickerOpen, setPickerOpen] = useState(false);
    const [importingState, setImportingState] = useState<'idle' | 'importing' | 'done'>('idle');

    const { apiFetch } = useAppFetch();
    const buildProxyUrl = useImageProxyUrl();

    // ── Fetch pages ───────────────────────────────────────────────────────

    const handleFetchPages = async (e: React.FormEvent) => {
        e.preventDefault();
        const bvid = extractBvid(urlInput);
        if (!bvid) return;

        setLoading(true);
        setError(null);
        setPages([]);
        setCurrentBvid(bvid);
        setImportingState('idle');

        try {
            const { resp, data } = await apiFetch('/api/v1/BilibiliVideoGetPagesRoute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ bvid }),
            });
            if (!resp.ok) {
                setError(`获取失败：HTTP ${resp.status}`);
                return;
            }
            const pageList = data as PageInfo[];
            setPages(pageList.map(p => ({ ...p, selected: true })));
        } catch (err) {
            setError('网络错误，请检查服务器连接');
        } finally {
            setLoading(false);
        }
    };

    // ── Selection helpers ─────────────────────────────────────────────────

    const togglePage = (pageNum: number) => {
        setPages(prev => prev.map(p => p.page === pageNum ? { ...p, selected: !p.selected } : p));
    };

    const allSelected = pages.length > 0 && pages.every(p => p.selected);
    const toggleAll = () => {
        setPages(prev => prev.map(p => ({ ...p, selected: !allSelected })));
    };

    const selectedPages = pages.filter(p => p.selected);

    // ── Import ────────────────────────────────────────────────────────────

    const handleImport = async (categoryIds: string[]) => {
        setPickerOpen(false);
        if (selectedPages.length === 0 || !currentBvid) return;

        setImportingState('importing');
        try {
            const videos = selectedPages.map(p => ({
                id: 0,
                title: p.title,
                cover: p.cover,
                intro: '',
                page: p.page,
                duration: p.duration,
                upper: { mid: 0, name: '', face: '' },
                cnt_info: { collect: 0, play: 0, danmaku: 0, view_text_1: '' },
                fav_time: 0,
                bvid: currentBvid,
            }));

            const { resp } = await apiFetch('/api/v1/MaterialImportRoute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    source_type: 'bilibili',
                    source_fid: '',
                    videos,
                    category_ids: categoryIds,
                }),
            });
            if (resp.ok) {
                setImportingState('done');
            } else {
                setImportingState('idle');
                setError(`导入失败：HTTP ${resp.status}`);
            }
        } catch {
            setImportingState('idle');
            setError('导入时发生网络错误');
        }
    };

    // ── Render ────────────────────────────────────────────────────────────

    return (
        <>
            {pickerOpen && (
                <CategoryPickerModal
                    videoCount={selectedPages.length}
                    onConfirm={handleImport}
                    onCancel={() => setPickerOpen(false)}
                />
            )}

            <div className="space-y-4">
                {/* Input form */}
                <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                    <h2 className="text-base sm:text-lg font-semibold text-gray-900 mb-4">多P视频导入</h2>
                    <form onSubmit={handleFetchPages} className="flex flex-col sm:flex-row gap-2">
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
                            获取分P
                        </button>
                    </form>
                    {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
                </div>

                {/* Page list */}
                {pages.length > 0 && (
                    <div className="bg-white rounded-lg border border-gray-200">
                        {/* Header */}
                        <div className="flex items-center justify-between flex-wrap gap-2 px-4 sm:px-6 py-3 border-b border-gray-100">
                            <div className="flex items-center gap-3">
                                <input
                                    type="checkbox"
                                    checked={allSelected}
                                    onChange={toggleAll}
                                    className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                                />
                                <span className="text-sm text-gray-600">
                                    共 <span className="font-medium">{pages.length}</span> 个分P
                                    {selectedPages.length > 0 && selectedPages.length < pages.length && (
                                        <span className="text-blue-600 ml-1">· 已选 {selectedPages.length} 个</span>
                                    )}
                                </span>
                            </div>
                            {selectedPages.length > 0 && (
                                <button
                                    onClick={() => setPickerOpen(true)}
                                    disabled={importingState === 'importing'}
                                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-green-700 bg-green-50 border border-green-200 rounded-lg hover:bg-green-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                >
                                    {importingState === 'importing'
                                        ? <Loader className="w-3.5 h-3.5 animate-spin" />
                                        : null
                                    }
                                    {importingState === 'done'
                                        ? `已导入 ${selectedPages.length} 个分P`
                                        : `导入选中的 ${selectedPages.length} 个分P`
                                    }
                                </button>
                            )}
                        </div>

                        {/* Page rows */}
                        <div className="divide-y divide-gray-100">
                            {pages.map(p => (
                                <div
                                    key={p.page}
                                    onClick={() => togglePage(p.page)}
                                    className={`flex items-center gap-3 px-4 sm:px-6 py-3 cursor-pointer transition-colors ${
                                        p.selected ? 'bg-blue-50' : 'hover:bg-gray-50'
                                    }`}
                                >
                                    <input
                                        type="checkbox"
                                        checked={p.selected}
                                        onChange={() => togglePage(p.page)}
                                        onClick={e => e.stopPropagation()}
                                        className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 flex-shrink-0"
                                    />
                                    {p.cover && (
                                        <img
                                            src={buildProxyUrl(p.cover)}
                                            alt={p.title}
                                            className="w-16 h-9 object-cover rounded flex-shrink-0 bg-gray-100"
                                        />
                                    )}
                                    <span className="hidden sm:inline text-xs font-mono text-gray-400 flex-shrink-0 w-8">
                                        P{p.page}
                                    </span>
                                    <span className="flex-1 text-sm text-gray-800 line-clamp-1">{p.title}</span>
                                    <span className="text-xs text-gray-400 font-mono flex-shrink-0">
                                        {formatDuration(p.duration)}
                                    </span>
                                </div>
                            ))}
                        </div>
                    </div>
                )}
            </div>
        </>
    );
}
