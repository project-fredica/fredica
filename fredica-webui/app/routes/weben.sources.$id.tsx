import { useState, useEffect, useCallback, useRef } from "react";
import { Link, useParams } from "react-router";
import {
    ArrowLeft, Link2, Clock, Play, ChevronDown, FileText,
} from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { print_error, reportHttpError } from "~/util/error_handler";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import {
    type WebenSource,
    type WebenExtractionRun,
    getAnalysisStatusInfo,
    formatRelativeTime,
    formatDuration,
} from "~/util/weben";
import {
    type ApiFetchFn,
    fetchExtractionRunList,
    fetchExtractionRunDetail,
} from "~/util/materialWebenApi";
import { normalizeWebenSource } from "~/util/materialWebenGuards";
import { openInternalUrl } from "~/util/bridge";

// ─── Extraction run row ────────────────────────────────────────────────────────

function RunRow({ run, apiFetch }: { run: WebenExtractionRun; apiFetch: ApiFetchFn }) {
    const [expanded, setExpanded] = useState(false);
    const [detail, setDetail] = useState<WebenExtractionRun | null>(null);
    const [loadingDetail, setLoadingDetail] = useState(false);

    const toggleExpand = async () => {
        if (detail !== null) { setExpanded(e => !e); return; }
        if (loadingDetail) return;
        setExpanded(true);
        setLoadingDetail(true);
        try {
            const d = await fetchExtractionRunDetail(apiFetch, run.id);
            setDetail(d);
        } catch { /* silent */ } finally {
            setLoadingDetail(false);
        }
    };

    const ts = new Date(run.created_at * 1000).toLocaleString("zh-CN", {
        month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit",
    });

    return (
        <div className="border-b border-gray-50 last:border-0">
            <button
                onClick={toggleExpand}
                className="w-full flex items-center gap-3 px-4 py-3 hover:bg-gray-50 text-left transition-colors"
            >
                <FileText className="w-3.5 h-3.5 text-gray-300 flex-shrink-0" />
                <div className="flex-1 min-w-0 flex items-center gap-2 flex-wrap">
                    <span className="text-sm text-gray-700">{ts}</span>
                    <span className="text-xs bg-violet-50 text-violet-600 px-1.5 py-0.5 rounded-full tabular-nums">
                        {run.concept_count} 个概念
                    </span>
                    {run.llm_model_id && (
                        <span className="text-xs text-gray-400 truncate">{run.llm_model_id}</span>
                    )}
                </div>
                <ChevronDown className={`w-3.5 h-3.5 text-gray-300 flex-shrink-0 transition-transform duration-150 ${expanded ? "rotate-180" : ""}`} />
            </button>

            {expanded && (
                <div className="px-4 pb-4 space-y-3">
                    {loadingDetail ? (
                        <div className="animate-pulse space-y-2">
                            <div className="h-3 bg-gray-100 rounded w-full" />
                            <div className="h-3 bg-gray-100 rounded w-3/4" />
                        </div>
                    ) : detail === null ? (
                        <p className="text-xs text-gray-400">加载失败</p>
                    ) : (
                        <>
                            {detail.prompt_text && (
                                <div>
                                    <p className="text-xs font-medium text-gray-500 mb-1.5">Prompt</p>
                                    <pre className="text-xs text-gray-600 bg-gray-50 rounded-lg p-3 whitespace-pre-wrap max-h-48 overflow-y-auto">
                                        {detail.prompt_text}
                                    </pre>
                                </div>
                            )}
                            {detail.llm_output_raw && (
                                <div>
                                    <p className="text-xs font-medium text-gray-500 mb-1.5">LLM 输出</p>
                                    <pre className="text-xs text-gray-600 bg-gray-50 rounded-lg p-3 whitespace-pre-wrap max-h-48 overflow-y-auto">
                                        {detail.llm_output_raw}
                                    </pre>
                                </div>
                            )}
                            {!detail.prompt_text && !detail.llm_output_raw && (
                                <p className="text-xs text-gray-400">暂无详细记录</p>
                            )}
                        </>
                    )}
                </div>
            )}
        </div>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function WebenSourceDetailPage() {
    const { id: sourceId } = useParams<{ id: string }>();
    const { apiFetch } = useAppFetch();

    const [source, setSource] = useState<WebenSource | null>(null);
    const [runs, setRuns] = useState<WebenExtractionRun[]>([]);
    const [runsTotal, setRunsTotal] = useState(0);
    const [loadingSource, setLoadingSource] = useState(true);
    const [loadingRuns, setLoadingRuns] = useState(true);
    const [error, setError] = useState(false);
    const prevStatusRef = useRef<string | undefined>(undefined);

    const fetchSource = useCallback(async () => {
        if (!sourceId) return;
        try {
            const { resp, data } = await apiFetch(
                `/api/v1/WebenSourceGetRoute?id=${encodeURIComponent(sourceId)}`,
                { method: "GET" },
                { silent: true },
            );
            if (!resp.ok) { reportHttpError("来源加载失败", resp); setError(true); return; }
            if (!data) { setError(true); return; }
            const raw = data as { source: unknown } | null;
            const normalized = raw?.source ? normalizeWebenSource(raw.source) : null;
            if (!normalized) { setError(true); return; }
            setSource(normalized);
        } catch (e) {
            print_error({ reason: "来源加载失败", err: e });
            setError(true);
        }
    }, [sourceId, apiFetch]);

    const fetchRuns = useCallback(async () => {
        if (!sourceId) return;
        try {
            const { items, total } = await fetchExtractionRunList(apiFetch, sourceId);
            setRuns(items);
            setRunsTotal(total);
        } catch { /* silent */ } finally {
            setLoadingRuns(false);
        }
    }, [sourceId, apiFetch]);

    useEffect(() => {
        if (!sourceId) return;
        Promise.all([fetchSource(), fetchRuns()]).finally(() => setLoadingSource(false));
    }, [sourceId, fetchSource, fetchRuns]);

    // Poll while analyzing; re-fetch runs when completed
    useEffect(() => {
        if (!source) return;
        const prev = prevStatusRef.current;
        prevStatusRef.current = source.analysis_status;

        if (prev === "analyzing" && source.analysis_status === "completed") {
            fetchRuns();
        }

        if (source.analysis_status !== "analyzing") return;
        const timer = setInterval(fetchSource, 2000);
        return () => clearInterval(timer);
    }, [source?.analysis_status, fetchSource, fetchRuns]);

    if (loadingSource) {
        return (
            <SidebarLayout>
                <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-4 animate-pulse">
                    <div className="h-4 bg-gray-200 rounded w-24" />
                    <div className="bg-white rounded-xl border border-gray-200 p-5 space-y-3">
                        <div className="h-5 bg-gray-200 rounded w-2/3" />
                        <div className="h-3 bg-gray-100 rounded w-1/3" />
                    </div>
                    <div className="bg-white rounded-xl border border-gray-200 h-40" />
                </div>
            </SidebarLayout>
        );
    }

    if (error || !source) {
        return (
            <SidebarLayout>
                <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-4">
                    <Link
                        to="/weben"
                        className="inline-flex items-center gap-1.5 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                    >
                        <ArrowLeft className="w-3.5 h-3.5" /> 知识网络
                    </Link>
                    <div className="bg-white rounded-xl border border-gray-200 p-8 text-center">
                        <p className="text-sm text-gray-400">找不到该来源，可能已被删除或加载失败。</p>
                    </div>
                </div>
            </SidebarLayout>
        );
    }

    const status = getAnalysisStatusInfo(source.analysis_status);
    const isAnalyzing = source.analysis_status === "analyzing";

    return (
        <SidebarLayout>
            <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-4">

                {/* Back */}
                <Link
                    to="/weben"
                    className="inline-flex items-center gap-1.5 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                >
                    <ArrowLeft className="w-3.5 h-3.5" /> 知识网络
                </Link>

                {/* Source header */}
                <div className="bg-white rounded-xl border border-gray-200 p-5 space-y-3">
                    <div>
                        <h1 className="text-base font-semibold text-gray-900 leading-snug">
                            {source.title}
                        </h1>
                        <div className="flex items-center gap-2 flex-wrap mt-1.5">
                            <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${status.badge}`}>
                                {status.label}
                            </span>
                            <span className="text-xs text-gray-400">{formatRelativeTime(source.created_at)}</span>
                            {source.duration_sec != null && (
                                <span className="flex items-center gap-0.5 text-xs text-gray-400">
                                    <Clock className="w-3 h-3" />
                                    {formatDuration(source.duration_sec)}
                                </span>
                            )}
                        </div>
                    </div>

                    {/* Progress bar (analyzing only) */}
                    {isAnalyzing && source.progress > 0 && (
                        <div className="flex items-center gap-2">
                            <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                                <div
                                    className="h-full bg-blue-400 rounded-full transition-all duration-500"
                                    style={{ width: `${source.progress}%` }}
                                />
                            </div>
                            <span className="text-[10px] text-gray-400 tabular-nums flex-shrink-0">
                                {source.progress}%
                            </span>
                        </div>
                    )}

                    <div className="flex items-center gap-2 flex-wrap">
                        {/* Material link (opens internal tab via bridge) */}
                        {source.material_id && (
                            <button
                                onClick={() => openInternalUrl(`/material/${source.material_id}`)}
                                className="inline-flex items-center gap-1.5 text-xs text-gray-500 hover:text-violet-600 bg-gray-50 hover:bg-violet-50 px-3 py-1.5 rounded-lg transition-colors"
                            >
                                <Play className="w-3 h-3" />
                                查看原始视频
                            </button>
                        )}
                    </div>
                </div>

                {/* Extraction runs */}
                <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                    <div className="px-4 py-3 border-b border-gray-100 flex items-center gap-1.5">
                        <FileText className="w-3.5 h-3.5 text-gray-400" />
                        <h2 className="text-sm font-semibold text-gray-700">提取记录</h2>
                        {runsTotal > 0 && (
                            <span className="text-[10px] bg-gray-100 text-gray-500 px-1.5 py-0.5 rounded-full tabular-nums">
                                {runsTotal}
                            </span>
                        )}
                    </div>

                    {loadingRuns ? (
                        <div className="divide-y divide-gray-50 animate-pulse">
                            {[0, 1, 2].map(i => (
                                <div key={i} className="px-4 py-3 flex items-center gap-3">
                                    <div className="w-3.5 h-3.5 bg-gray-100 rounded" />
                                    <div className="flex-1 space-y-1.5">
                                        <div className="h-3.5 bg-gray-100 rounded w-1/3" />
                                    </div>
                                </div>
                            ))}
                        </div>
                    ) : runs.length === 0 ? (
                        <div className="py-12 text-center">
                            <FileText className="w-8 h-8 text-gray-200 mx-auto mb-2" />
                            <p className="text-sm text-gray-400">暂无提取记录</p>
                        </div>
                    ) : (
                        <div>
                            {runs.map(run => (
                                <RunRow key={run.id} run={run} apiFetch={apiFetch} />
                            ))}
                        </div>
                    )}
                </div>

            </div>
        </SidebarLayout>
    );
}
