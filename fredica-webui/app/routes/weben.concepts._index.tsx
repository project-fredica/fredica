import { useState, useEffect, useCallback } from "react";
import { Link, useSearchParams } from "react-router";
import { Search, X, Layers } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import {
    type WebenConcept,
    type WebenConceptTypeHint,
    getConceptTypeInfo,
} from "~/util/weben";
import { fetchWebenConceptTypeHints } from "~/util/materialWebenApi";

const PAGE_SIZE = 30;

function TypeFilterChip({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
    return (
        <button
            onClick={onClick}
            className={`px-3 py-1.5 text-xs rounded-lg font-medium transition-colors whitespace-nowrap ${
                active
                    ? "bg-violet-600 text-white"
                    : "bg-gray-50 border border-gray-200 text-gray-500 hover:bg-gray-100"
            }`}
        >
            {label}
        </button>
    );
}

function ConceptCard({ concept, typeHints }: { concept: WebenConcept; typeHints: WebenConceptTypeHint[] }) {
    const types = concept.concept_type.split(",").map(t => t.trim()).filter(Boolean);
    return (
        <Link
            to={`/weben/concepts/${concept.id}`}
            className="group bg-white rounded-xl border border-gray-200 p-4 hover:border-violet-300 hover:shadow-sm transition-all flex flex-col gap-3"
        >
            <div className="flex items-start justify-between gap-2">
                <h3 className="text-sm font-semibold text-gray-900 group-hover:text-violet-700 transition-colors leading-snug">
                    {concept.canonical_name}
                </h3>
                <div className="flex flex-wrap gap-1 flex-shrink-0">
                    {types.map(t => {
                        const info = getConceptTypeInfo(t, typeHints);
                        return (
                            <span key={t} className={`text-[10px] font-medium px-1.5 py-0.5 rounded-md whitespace-nowrap ring-1 ${info.color}`}>
                                {info.label}
                            </span>
                        );
                    })}
                </div>
            </div>

            {concept.brief_definition && (
                <p className="text-xs text-gray-500 line-clamp-3 leading-relaxed">
                    {concept.brief_definition}
                </p>
            )}
        </Link>
    );
}

export default function WebenConceptsPage() {
    const [searchParams, setSearchParams] = useSearchParams();

    const typeFilter = searchParams.get("type") ?? "";
    const searchQuery = searchParams.get("q") ?? "";
    const page = Math.max(1, parseInt(searchParams.get("page") ?? "1", 10));

    const [concepts, setConcepts] = useState<WebenConcept[]>([]);
    const [typeHints, setTypeHints] = useState<WebenConceptTypeHint[]>([]);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true);
    const [searchInput, setSearchInput] = useState(searchQuery);

    const { apiFetch } = useAppFetch();

    const setParam = (key: string, value: string) => {
        setSearchParams(prev => {
            const next = new URLSearchParams(prev);
            if (value) next.set(key, value);
            else next.delete(key);
            if (key !== "page") next.delete("page");
            return next;
        });
    };

    const setPage = (p: number) => {
        setSearchParams(prev => {
            const next = new URLSearchParams(prev);
            if (p > 1) next.set("page", String(p));
            else next.delete("page");
            return next;
        });
    };

    const fetchConcepts = useCallback(async () => {
        setLoading(true);
        try {
            const params = new URLSearchParams();
            if (typeFilter) params.set("concept_type", typeFilter);
            params.set("limit", String(PAGE_SIZE));
            params.set("offset", String((page - 1) * PAGE_SIZE));

            const { data } = await apiFetch(
                `/api/v1/WebenConceptListRoute?${params}`,
                { method: "GET" },
                { silent: true },
            );
            const pageData = data as { items: WebenConcept[]; total: number } | null;
            if (pageData?.items) {
                const filtered = searchQuery
                    ? pageData.items.filter(c =>
                        c.canonical_name.includes(searchQuery) ||
                        c.brief_definition?.includes(searchQuery)
                    )
                    : pageData.items;
                setConcepts(filtered);
                setTotal(searchQuery ? filtered.length : pageData.total);
            }
        } catch {
            setConcepts([]);
            setTotal(0);
        } finally {
            setLoading(false);
        }
    }, [apiFetch, typeFilter, page, searchQuery]);

    useEffect(() => {
        fetchConcepts();
    }, [fetchConcepts]);

    useEffect(() => {
        fetchWebenConceptTypeHints(apiFetch)
            .then(setTypeHints)
            .catch(() => setTypeHints([]));
    }, [apiFetch]);

    useEffect(() => {
        const id = setTimeout(() => setParam("q", searchInput), 400);
        return () => clearTimeout(id);
    }, [searchInput]);

    const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

    return (
        <SidebarLayout>
            <div className="max-w-4xl mx-auto p-4 sm:p-6 space-y-4">
                <div className="flex items-center justify-between gap-3">
                    <div>
                        <h1 className="text-xl font-semibold text-gray-900 flex items-center gap-2">
                            <Layers className="w-5 h-5 text-violet-500" />
                            概念库
                        </h1>
                        <p className="text-sm text-gray-400 mt-0.5">共 {total} 个概念</p>
                    </div>
                    <Link
                        to="/weben"
                        className="text-xs text-gray-400 hover:text-gray-600 transition-colors"
                    >
                        ← 知识网络
                    </Link>
                </div>

                <div className="bg-white rounded-xl border border-gray-200 p-4 space-y-3">
                    <div className="relative">
                        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
                        <input
                            type="text"
                            value={searchInput}
                            onChange={e => setSearchInput(e.target.value)}
                            placeholder="搜索概念名称或定义…"
                            className="w-full pl-9 pr-9 py-2 text-sm border border-gray-200 rounded-lg focus:ring-2 focus:ring-violet-500 focus:border-transparent outline-none"
                        />
                        {searchInput && (
                            <button
                                onClick={() => { setSearchInput(""); setParam("q", ""); }}
                                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                            >
                                <X className="w-3.5 h-3.5" />
                            </button>
                        )}
                    </div>

                    <div className="flex flex-wrap gap-1.5">
                        <TypeFilterChip label="全部" active={typeFilter === ""} onClick={() => setParam("type", "")} />
                        {typeHints.map(t => (
                            <TypeFilterChip
                                key={t.key}
                                label={t.label}
                                active={typeFilter === t.key}
                                onClick={() => setParam("type", typeFilter === t.key ? "" : t.key)}
                            />
                        ))}
                    </div>
                </div>

                {loading ? (
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                        {Array.from({ length: 6 }).map((_, i) => (
                            <div key={i} className="h-28 bg-gray-100 rounded-xl animate-pulse" />
                        ))}
                    </div>
                ) : concepts.length === 0 ? (
                    <div className="bg-white rounded-xl border border-gray-200 py-16 text-center">
                        <Layers className="w-8 h-8 text-gray-300 mx-auto mb-2" />
                        <p className="text-sm text-gray-400">
                            {searchQuery || typeFilter ? "没有匹配的概念" : "暂无概念数据，请先分析视频素材"}
                        </p>
                    </div>
                ) : (
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                        {concepts.map(c => <ConceptCard key={c.id} concept={c} typeHints={typeHints} />)}
                    </div>
                )}

                {totalPages > 1 && (
                    <div className="flex items-center justify-center gap-3">
                        <button
                            onClick={() => setPage(page - 1)}
                            disabled={page <= 1}
                            className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                        >
                            ← 上一页
                        </button>
                        <span className="text-xs text-gray-500 tabular-nums">
                            {page} / {totalPages} 页
                        </span>
                        <button
                            onClick={() => setPage(page + 1)}
                            disabled={page >= totalPages}
                            className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                        >
                            下一页 →
                        </button>
                    </div>
                )}
            </div>
        </SidebarLayout>
    );
}
