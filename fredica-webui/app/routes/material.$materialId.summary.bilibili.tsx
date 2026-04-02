import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import { Loader, RefreshCw, Sparkles } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { print_error, reportHttpError } from "~/util/error_handler";
import { useFloatingPlayerCtx } from "~/context/floatingPlayer";

// ─── Types ────────────────────────────────────────────────────────────────────

interface OutlineItem {
    timestamp: number;
    content: string;
}

interface OutlineSection {
    title: string;
    part_outline: OutlineItem[];
}

interface AiConclusionResult {
    code: number;
    message?: string;
    model_result: {
        summary: string;
        outline: OutlineSection[];
    } | null;
}

// ─── Runtime parser ───────────────────────────────────────────────────────────

function parseOutlineItem(item: unknown): OutlineItem {
    if (!item || typeof item !== "object") return { timestamp: 0, content: "" };
    const i = item as Record<string, unknown>;
    return {
        timestamp: typeof i.timestamp === "number" ? i.timestamp : 0,
        content: typeof i.content === "string" ? i.content : "",
    };
}

function parseOutlineSection(sec: unknown): OutlineSection {
    if (!sec || typeof sec !== "object") return { title: "", part_outline: [] };
    const s = sec as Record<string, unknown>;
    return {
        title: typeof s.title === "string" ? s.title : "",
        part_outline: Array.isArray(s.part_outline)
            ? s.part_outline.map(parseOutlineItem)
            : [],
    };
}

function parseAiConclusion(data: unknown): AiConclusionResult | null {
    if (!data || typeof data !== "object") return null;
    const d = data as Record<string, unknown>;
    if (typeof d.code !== "number") return null;

    let model_result: AiConclusionResult["model_result"] = null;
    if (d.model_result != null) {
        if (typeof d.model_result !== "object") return null;
        const mr = d.model_result as Record<string, unknown>;
        model_result = {
            summary: typeof mr.summary === "string" ? mr.summary : "",
            outline: Array.isArray(mr.outline)
                ? mr.outline.map(parseOutlineSection)
                : [],
        };
    }

    return {
        code: d.code,
        message: typeof d.message === "string" ? d.message : undefined,
        model_result,
    };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatTimestamp(seconds: number): string {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

/** bilibili_bvid__BV1xx__P1 → 0 */
function parsePageIndex(materialId: string): number {
    const match = materialId.match(/__P(\d+)$/);
    return match ? Math.max(0, parseInt(match[1], 10) - 1) : 0;
}

/** bilibili_bvid__BV1xx__P1 → "BV1xx" */
function parseBvid(materialId: string): string | null {
    const match = materialId.match(/^bilibili_bvid__(.+)__P\d+$/);
    return match ? match[1] : null;
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function TimestampButton({
    timestamp,
    materialId,
}: {
    timestamp: number;
    materialId: string;
}) {
    const { openFloatingPlayer } = useFloatingPlayerCtx();
    return (
        <button
            onClick={() => openFloatingPlayer(materialId, timestamp)}
            className="font-mono text-violet-600 hover:text-violet-800 hover:underline transition-colors w-10 text-left flex-shrink-0 text-xs"
        >
            {formatTimestamp(timestamp)}
        </button>
    );
}

function OutlineView({
    outline,
    materialId,
}: {
    outline: OutlineSection[];
    materialId: string;
}) {
    return (
        <div className="space-y-4">
            {outline.map((section, si) => (
                <div key={si}>
                    <h3 className="text-sm font-semibold text-gray-800 mb-2">{section.title}</h3>
                    <div className="space-y-1.5 pl-1">
                        {section.part_outline.map((item, ii) => (
                            <div key={ii} className="flex items-start gap-2 text-xs text-gray-600">
                                <TimestampButton timestamp={item.timestamp} materialId={materialId} />
                                <span className="leading-relaxed">{item.content}</span>
                            </div>
                        ))}
                    </div>
                </div>
            ))}
        </div>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function SummaryBilibiliPage() {
    const { materialId } = useParams<{ materialId: string }>();
    const bvid = parseBvid(materialId ?? "");
    const pageIndex = parsePageIndex(materialId ?? "");

    const { apiFetch } = useAppFetch();
    const [loading,    setLoading]    = useState(true);
    const [result,     setResult]     = useState<AiConclusionResult | null>(null);
    const [fetchError, setFetchError] = useState(false);
    const [refreshKey, setRefreshKey] = useState(0);
    const abortRef = useRef<AbortController | null>(null);

    useEffect(() => {
        if (!bvid) return;
        abortRef.current?.abort();
        const abort = new AbortController();
        abortRef.current = abort;
        let cancelled = false;

        setLoading(true);
        setFetchError(false);

        apiFetch(
            "/api/v1/BilibiliVideoAiConclusionRoute",
            {
                method: "POST",
                body: JSON.stringify({ bvid, page_index: pageIndex, is_update: refreshKey > 0 }),
            },
            { signal: abort.signal },
        ).then(({ resp, data }) => {
            if (cancelled) return;
            if (!resp.ok) {
                reportHttpError("B站AI总结获取失败", resp);
                setFetchError(true);
                return;
            }
            setResult(parseAiConclusion(data));
        }).catch(e => {
            if (cancelled || (e as Error)?.name === "AbortError") return;
            print_error({ reason: "B站AI总结获取失败", err: e });
            setFetchError(true);
        }).finally(() => {
            if (!cancelled) setLoading(false);
        });

        return () => { cancelled = true; abort.abort(); };
    }, [bvid, pageIndex, refreshKey]); // eslint-disable-line react-hooks/exhaustive-deps

    const hasContent = result?.code === 0 && result.model_result != null;

    return (
        <div className="p-4 sm:p-6 space-y-5 max-w-2xl">
            {/* Header row */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-sm font-medium text-gray-700">
                    <Sparkles className="w-4 h-4 text-violet-500" />
                    B站 AI 总结
                </div>
                <button
                    onClick={() => setRefreshKey(k => k + 1)}
                    disabled={loading}
                    title="强制刷新（重新从 B 站拉取）"
                    className="p-1.5 rounded-lg hover:bg-gray-100 text-gray-400 hover:text-gray-600 transition-colors disabled:opacity-40"
                >
                    <RefreshCw className={`w-4 h-4 ${loading ? "animate-spin" : ""}`} />
                </button>
            </div>

            {/* Loading */}
            {loading && (
                <div className="flex items-center gap-2 text-sm text-gray-400 py-6">
                    <Loader className="w-4 h-4 animate-spin" />
                    获取中…
                </div>
            )}

            {/* Error / unavailable */}
            {!loading && (fetchError || !hasContent) && (
                <div className="py-8 text-center text-sm text-gray-400">
                    {fetchError
                        ? "获取失败，请检查网络和 B 站账号配置后重试。"
                        : "该视频暂无 AI 总结"}
                </div>
            )}

            {/* Content */}
            {!loading && hasContent && result!.model_result!.summary && (
                <div className="bg-violet-50 rounded-xl p-4">
                    <p className="text-sm text-gray-700 leading-relaxed">{result!.model_result!.summary}</p>
                </div>
            )}
            {!loading && hasContent && result!.model_result!.outline.length > 0 && (
                <div className="bg-white rounded-xl border border-gray-100 p-4">
                    <p className="text-xs font-medium text-gray-400 mb-3 uppercase tracking-wide">章节大纲</p>
                    <OutlineView outline={result!.model_result!.outline} materialId={materialId ?? ""} />
                </div>
            )}
        </div>
    );
}
