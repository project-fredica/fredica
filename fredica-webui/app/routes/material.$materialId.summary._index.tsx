import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { ArrowRight, BrainCircuit, CheckCircle, Subtitles, XCircle } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { print_error, reportHttpError } from "~/util/error_handler";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import type { MaterialSubtitleItem } from "~/util/materialWebenApi";
import { fetchMaterialSubtitles } from "~/util/materialWebenApi";
import type { WebenConcept, WebenSource } from "~/util/weben";

// ─── Types ────────────────────────────────────────────────────────────────────

type LoadState = "loading" | "ready" | "error";

// ─── Overview data hook ───────────────────────────────────────────────────────

function useOverviewData(materialId: string) {
    const { apiFetch } = useAppFetch();

    const [state,      setState]      = useState<LoadState>("loading");
    const [subtitles,  setSubtitles]  = useState<MaterialSubtitleItem[]>([]);
    const [webenSource, setWebenSource] = useState<WebenSource | null>(null);
    const [concepts,   setConcepts]   = useState<WebenConcept[]>([]);

    useEffect(() => {
        let cancelled = false;
        setState("loading");

        Promise.all([
            fetchMaterialSubtitles(apiFetch, materialId).catch(e => {
                print_error({ reason: "字幕列表加载失败", err: e });
                return [] as MaterialSubtitleItem[];
            }),
            apiFetch(`/api/v1/WebenSourceListRoute?material_id=${encodeURIComponent(materialId)}`, { method: "GET" }, { silent: true })
                .then(({ data }) => (Array.isArray(data) ? data as unknown as WebenSource[] : []))
                .catch(e => { print_error({ reason: "Weben 来源加载失败", err: e }); return [] as WebenSource[]; }),
        ]).then(async ([subs, sources]) => {
            if (cancelled) return;
            setSubtitles(subs);
            const source = sources[0] ?? null;
            setWebenSource(source);

            if (source) {
                const { resp, data } = await apiFetch(
                    `/api/v1/WebenConceptListRoute?source_id=${encodeURIComponent(source.id)}&limit=12&offset=0`,
                    { method: "GET" }, { silent: true },
                ).catch(e => { print_error({ reason: "Weben 概念列表加载失败", err: e }); return { resp: null as unknown as Response, data: null }; });
                if (!cancelled) {
                    if (resp && !resp.ok) { reportHttpError("Weben 概念列表加载失败", resp); }
                    const page = data as { items?: WebenConcept[] } | null;
                    setConcepts(page?.items ?? []);
                }
            }
            if (!cancelled) setState("ready");
        }).catch(e => {
            if (!cancelled) {
                print_error({ reason: "概览数据加载失败", err: e });
                setState("error");
            }
        });

        return () => { cancelled = true; };
    }, [materialId, apiFetch]);

    return { state, subtitles, webenSource, concepts };
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function AnalysisStatusRow({ source, materialId }: { source: WebenSource | null; materialId: string }) {
    if (!source) {
        return (
            <div className="flex items-center justify-between gap-3 p-4">
                <div className="flex items-center gap-2 text-sm text-gray-400">
                    <BrainCircuit className="w-4 h-4" />
                    <span>尚未进行知识提取</span>
                </div>
                <Link
                    to="../summary/weben"
                    relative="path"
                    className="flex items-center gap-1 text-xs text-violet-600 hover:text-violet-800 font-medium whitespace-nowrap"
                >
                    前往知识提取 <ArrowRight className="w-3.5 h-3.5" />
                </Link>
            </div>
        );
    }

    const isCompleted = source.analysis_status === "completed";
    const isFailed    = source.analysis_status === "failed";

    return (
        <div className="flex items-center justify-between gap-3 p-4">
            <div className={`flex items-center gap-2 text-sm ${isCompleted ? "text-green-700" : isFailed ? "text-red-600" : "text-gray-500"}`}>
                {isCompleted
                    ? <CheckCircle className="w-4 h-4" />
                    : isFailed
                        ? <XCircle className="w-4 h-4" />
                        : <BrainCircuit className="w-4 h-4 animate-pulse" />
                }
                <span>
                    {isCompleted
                        ? "已完成知识提取"
                        : isFailed
                            ? "知识提取失败"
                            : "知识提取进行中…"}
                </span>
            </div>
            <Link
                to="weben"
                className="flex items-center gap-1 text-xs text-violet-600 hover:text-violet-800 font-medium whitespace-nowrap"
            >
                知识提取工作台 <ArrowRight className="w-3.5 h-3.5" />
            </Link>
        </div>
    );
}

function ConceptChips({ concepts, sourceId }: { concepts: WebenConcept[]; sourceId: string }) {
    if (concepts.length === 0) return null;
    const displayed = concepts.slice(0, 10);
    const extra = concepts.length - displayed.length;

    return (
        <div className="px-4 pb-4">
            <p className="text-xs text-gray-400 mb-2">已提取概念</p>
            <div className="flex flex-wrap gap-1.5">
                {displayed.map(c => (
                    <Link
                        key={c.id}
                        to={`/weben/concepts/${c.id}`}
                        className="text-xs px-2.5 py-1 bg-violet-50 text-violet-700 rounded-full font-medium hover:bg-violet-100 transition-colors"
                    >
                        {c.canonical_name}
                    </Link>
                ))}
                {extra > 0 && (
                    <Link
                        to={`/weben/concepts?source_id=${encodeURIComponent(sourceId)}`}
                        className="text-xs px-2.5 py-1 bg-gray-100 text-gray-500 rounded-full font-medium hover:bg-gray-200 transition-colors"
                    >
                        +{extra} 更多
                    </Link>
                )}
            </div>
        </div>
    );
}

function SubtitleRow({ subtitles }: { subtitles: MaterialSubtitleItem[] }) {
    if (subtitles.length === 0) return null;
    const first = subtitles[0];

    return (
        <div className="px-4 pb-4 flex items-center justify-between gap-3">
            <div className="flex items-center gap-2 text-sm text-gray-500">
                <Subtitles className="w-4 h-4 text-gray-400" />
                <span>
                    {first.lan_doc || first.lan} 字幕
                    {subtitles.length > 1 && <span className="text-gray-400"> · 共 {subtitles.length} 条轨道</span>}
                </span>
            </div>
            <Link
                to="../subtitle"
                relative="path"
                className="text-xs text-gray-400 hover:text-gray-600"
            >
                字幕面板 →
            </Link>
        </div>
    );
}

// ─── Skeleton ─────────────────────────────────────────────────────────────────

function Skeleton() {
    return (
        <div className="p-4 space-y-3 animate-pulse">
            <div className="h-5 bg-gray-100 rounded w-48" />
            <div className="flex gap-1.5">
                {[60, 80, 70, 90, 65].map(w => (
                    <div key={w} className="h-6 bg-gray-100 rounded-full" style={{ width: w }} />
                ))}
            </div>
            <div className="h-4 bg-gray-100 rounded w-36" />
        </div>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function SummaryOverviewPage() {
    useWorkspaceContext();
    const { materialId } = useParams<{ materialId: string }>();
    const { state, subtitles, webenSource, concepts } = useOverviewData(materialId!);

    if (state === "loading") return <Skeleton />;

    if (state === "error") {
        return (
            <div className="p-4 text-sm text-red-500">概览数据加载失败，请刷新重试。</div>
        );
    }

    return (
        <div className="divide-y divide-gray-50">
            <AnalysisStatusRow source={webenSource} materialId={materialId!} />
            {webenSource && concepts.length > 0 && (
                <ConceptChips concepts={concepts} sourceId={webenSource.id} />
            )}
            {subtitles.length > 0 && <SubtitleRow subtitles={subtitles} />}
        </div>
    );
}
