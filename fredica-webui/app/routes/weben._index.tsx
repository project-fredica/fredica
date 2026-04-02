import { useState, useEffect, useCallback } from "react";
import { Link } from "react-router";
import { Brain, RefreshCw, ChevronRight, BookOpen, Layers } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import {
    type WebenConcept, type WebenSourceListItem,
    type WebenSourcePageResult,
    getAnalysisStatusInfo, getConceptTypeInfo, formatRelativeTime,
} from "~/util/weben";

// ─── Source row ────────────────────────────────────────────────────────────────

function SourceRow({ item }: { item: WebenSourceListItem }) {
    const { source, concept_count } = item;
    const status = getAnalysisStatusInfo(source.analysis_status);

    return (
        <Link
            to={`/weben/sources/${source.id}`}
            className="flex items-start gap-3 px-4 py-3 hover:bg-violet-50/40 transition-colors group"
        >
            <div className="flex-1 min-w-0 space-y-1">
                <p className="text-sm font-medium text-gray-800 truncate group-hover:text-violet-700 transition-colors leading-snug">
                    {source.title}
                </p>
                <div className="flex items-center gap-2 flex-wrap">
                    <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded-full ${status.badge}`}>
                        {status.label}
                    </span>
                    <span className="text-xs text-gray-400">{formatRelativeTime(source.created_at)}</span>
                    {source.analysis_status === "completed" && concept_count > 0 && (
                        <span className="text-xs text-gray-400 flex items-center gap-0.5">
                            <BookOpen className="w-3 h-3" />
                            {concept_count}
                        </span>
                    )}
                </div>
                {source.analysis_status === "analyzing" && source.progress > 0 && (
                    <div className="flex items-center gap-2 pt-0.5">
                        <div className="flex-1 h-1 bg-gray-100 rounded-full overflow-hidden">
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
            </div>
            <ChevronRight className="w-3.5 h-3.5 text-gray-300 group-hover:text-violet-400 flex-shrink-0 mt-1" />
        </Link>
    );
}

// ─── Concept row ───────────────────────────────────────────────────────────────

function ConceptRow({ concept }: { concept: WebenConcept }) {
    const types = concept.concept_type.split(",").map(t => t.trim()).filter(Boolean);
    return (
        <Link
            to={`/weben/concepts/${concept.id}`}
            className="flex items-center gap-3 px-4 py-3 hover:bg-violet-50/40 transition-colors group"
        >
            <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-gray-800 truncate group-hover:text-violet-600 transition-colors">
                    {concept.canonical_name}
                </p>
                {types.length > 0 && (
                    <p className="text-xs text-gray-400 mt-0.5 truncate">{types.join(" · ")}</p>
                )}
            </div>
            <ChevronRight className="w-3.5 h-3.5 text-gray-300 group-hover:text-violet-400 flex-shrink-0" />
        </Link>
    );
}

// ─── Panel skeleton ────────────────────────────────────────────────────────────

function PanelSkeleton() {
    return (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden animate-pulse">
            <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
                <div className="h-4 bg-gray-200 rounded w-20" />
                <div className="h-3 bg-gray-100 rounded w-12" />
            </div>
            {[0, 1, 2].map(i => (
                <div key={i} className="px-4 py-3 border-b border-gray-50 last:border-0 space-y-1.5">
                    <div className="h-3.5 bg-gray-100 rounded w-3/4" />
                    <div className="h-3 bg-gray-50 rounded w-1/3" />
                </div>
            ))}
        </div>
    );
}

// ─── Section card wrapper ──────────────────────────────────────────────────────

function SectionCard({
    title,
    allLabel,
    allTo,
    empty,
    emptyText,
    icon: Icon,
    children,
}: {
    title: string;
    allLabel?: string;
    allTo?: string;
    empty: boolean;
    emptyText: string;
    icon: React.FC<{ className?: string }>;
    children: React.ReactNode;
}) {
    return (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden flex flex-col">
            <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between flex-shrink-0">
                <div className="flex items-center gap-1.5">
                    <Icon className="w-3.5 h-3.5 text-gray-400" />
                    <h2 className="text-sm font-semibold text-gray-700">{title}</h2>
                </div>
                {allTo && allLabel && (
                    <Link to={allTo} className="text-xs text-violet-500 hover:text-violet-700 transition-colors">
                        {allLabel}
                    </Link>
                )}
            </div>
            {empty ? (
                <div className="px-4 py-10 text-center text-sm text-gray-400">{emptyText}</div>
            ) : (
                <div className="divide-y divide-gray-50">{children}</div>
            )}
        </div>
    );
}

// ─── Page ──────────────────────────────────────────────────────────────────────

export default function WebenIndexPage() {
    const [concepts,   setConcepts]   = useState<WebenConcept[]>([]);
    const [sources,    setSources]    = useState<WebenSourceListItem[]>([]);
    const [totalC,     setTotalC]     = useState<number | null>(null);
    const [totalS,     setTotalS]     = useState<number | null>(null);
    const [loading,    setLoading]    = useState(true);
    const [refreshing, setRefreshing] = useState(false);

    const { apiFetch } = useAppFetch();

    const load = useCallback(async () => {
        const [conceptsRes, sourcesRes] = await Promise.allSettled([
            apiFetch('/api/v1/WebenConceptListRoute?limit=8&offset=0',  { method: 'GET' }, { silent: true }),
            apiFetch('/api/v1/WebenSourceListRoute?limit=6&offset=0',   { method: 'GET' }, { silent: true }),
        ]);

        if (conceptsRes.status === 'fulfilled') {
            const page = conceptsRes.value.data as { items?: WebenConcept[]; total?: number } | null;
            if (page?.items) { setConcepts(page.items); setTotalC(page.total ?? null); }
        }
        if (sourcesRes.status === 'fulfilled') {
            const page = sourcesRes.value.data as WebenSourcePageResult | null;
            if (page?.items) { setSources(page.items); setTotalS(page.total ?? null); }
        }
    }, [apiFetch]);

    useEffect(() => {
        load().finally(() => setLoading(false));
    }, [load]);

    const handleRefresh = async () => {
        setRefreshing(true);
        try { await load(); } finally { setRefreshing(false); }
    };

    return (
        <SidebarLayout>
            <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-5">

                {/* Header */}
                <div className="flex items-start justify-between">
                    <div className="space-y-0.5">
                        <div className="flex items-center gap-2">
                            <Brain className="w-5 h-5 text-violet-500" />
                            <h1 className="text-lg font-semibold text-gray-900">知识网络</h1>
                        </div>
                        {(totalC !== null || totalS !== null) && (
                            <p className="text-xs text-gray-400 pl-7">
                                {totalC !== null && <><span className="font-medium text-gray-600">{totalC}</span> 个概念</>}
                                {totalC !== null && totalS !== null && <span className="mx-1.5 text-gray-200">·</span>}
                                {totalS !== null && <><span className="font-medium text-gray-600">{totalS}</span> 个来源</>}
                            </p>
                        )}
                    </div>
                    <button
                        onClick={handleRefresh}
                        disabled={refreshing || loading}
                        className="p-1.5 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg transition-colors disabled:opacity-50"
                    >
                        <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
                    </button>
                </div>

                {/* Panels */}
                {loading ? (
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                        <PanelSkeleton />
                        <PanelSkeleton />
                    </div>
                ) : (
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">

                        {/* Sources */}
                        <SectionCard
                            title="最近摄入"
                            icon={Layers}
                            empty={sources.length === 0}
                            emptyText="暂无已分析来源"
                        >
                            {sources.map(item => <SourceRow key={item.source.id} item={item} />)}
                        </SectionCard>

                        {/* Concepts */}
                        <SectionCard
                            title="最近概念"
                            allLabel="概念库"
                            allTo="/weben/concepts"
                            icon={BookOpen}
                            empty={concepts.length === 0}
                            emptyText="暂无概念数据"
                        >
                            {concepts.slice(0, 8).map(c => <ConceptRow key={c.id} concept={c} />)}
                        </SectionCard>

                    </div>
                )}

            </div>
        </SidebarLayout>
    );
}
