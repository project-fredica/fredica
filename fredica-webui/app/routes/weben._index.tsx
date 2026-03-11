import { useState, useEffect, useCallback } from "react";
import { Link } from "react-router";
import { Brain, BookOpen, RefreshCw, ArrowRight, Layers, Clock, ChevronRight } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { WebenSourceAnalysisModal } from "~/components/weben/WebenSourceAnalysisModal";
import {
    type WebenConcept, type WebenSource, type WebenReviewQueueResponse,
    getAnalysisStatusInfo, masteryBarColor, masteryLabel, formatRelativeTime,
} from "~/util/weben";

// ─── Sub-components ────────────────────────────────────────────────────────────

function StatCard({ icon, label, value, sub, to, color }: {
    icon: React.ReactNode; label: string; value: string | number;
    sub?: string; to: string; color: string;
}) {
    return (
        <Link to={to} className="group bg-white rounded-xl border border-gray-200 p-5 hover:border-violet-300 hover:shadow-md transition-all">
            <div className="flex items-start justify-between">
                <div className={`p-2.5 rounded-lg ${color}`}>{icon}</div>
                <ChevronRight className="w-4 h-4 text-gray-300 group-hover:text-violet-400 transition-colors mt-1" />
            </div>
            <div className="mt-3">
                <div className="text-2xl font-bold text-gray-900 tabular-nums">{value}</div>
                <div className="text-sm text-gray-500 mt-0.5">{label}</div>
                {sub && <div className="text-xs text-gray-400 mt-0.5">{sub}</div>}
            </div>
        </Link>
    );
}

function SourceRow({ source, onSelect }: { source: WebenSource; onSelect: (s: WebenSource) => void }) {
    const status = getAnalysisStatusInfo(source.analysis_status);
    const isNonTerminal = source.analysis_status === 'pending' || source.analysis_status === 'analyzing';
    // 非终态且有工作流 ID 时可点击查看进度详情
    const clickable = isNonTerminal && source.workflow_run_id != null;

    return (
        <div
            className={`relative flex items-center gap-3 px-4 py-3 overflow-hidden
                ${clickable ? 'cursor-pointer hover:bg-gray-50 transition-colors' : ''}`}
            onClick={clickable ? () => onSelect(source) : undefined}
        >
            <span className={`w-2 h-2 rounded-full flex-shrink-0 ${status.dot}`} />
            <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-gray-800 truncate">{source.title}</p>
                <p className="text-xs text-gray-400">{formatRelativeTime(source.created_at)}</p>
            </div>
            <span className={`text-[10px] font-medium px-2 py-0.5 rounded-full flex-shrink-0 ${status.badge}`}>
                {status.label}
            </span>
            {/* 分析中且有进度时，底部显示细进度条 */}
            {source.analysis_status === 'analyzing' && source.progress > 0 && (
                <div
                    className="absolute bottom-0 left-0 h-[1.5px] bg-blue-500 transition-all duration-500"
                    style={{ width: `${source.progress}%` }}
                />
            )}
        </div>
    );
}

function LowMasteryConceptRow({ concept }: { concept: WebenConcept }) {
    return (
        <Link
            to={`/weben/concepts/${concept.id}`}
            className="flex items-center gap-3 px-4 py-3 hover:bg-gray-50 transition-colors group"
        >
            <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-gray-800 truncate group-hover:text-violet-600 transition-colors">
                    {concept.canonical_name}
                </p>
                <div className="flex items-center gap-2 mt-1">
                    <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden max-w-24">
                        <div
                            className={`h-full rounded-full ${masteryBarColor(concept.mastery)}`}
                            style={{ width: `${Math.max(4, concept.mastery * 100)}%` }}
                        />
                    </div>
                    <span className="text-xs text-gray-400">{masteryLabel(concept.mastery)}</span>
                </div>
            </div>
            <ChevronRight className="w-4 h-4 text-gray-300 group-hover:text-violet-400 flex-shrink-0" />
        </Link>
    );
}

// ─── Page ──────────────────────────────────────────────────────────────────────

export default function WebenIndexPage() {
    const [concepts,      setConcepts]      = useState<WebenConcept[]>([]);
    const [sources,       setSources]       = useState<WebenSource[]>([]);
    const [dueCount,      setDueCount]      = useState(0);
    const [refreshing,    setRefreshing]    = useState(false);
    const [selectedSource, setSelectedSource] = useState<WebenSource | null>(null);

    const { apiFetch } = useAppFetch();

    const refresh = useCallback(async () => {
        const [conceptsRes, sourcesRes, reviewRes] = await Promise.allSettled([
            apiFetch('/api/v1/WebenConceptListRoute?limit=10&offset=0', { method: 'GET' }, { silent: true }),
            apiFetch('/api/v1/WebenSourceListRoute',                    { method: 'GET' }, { silent: true }),
            apiFetch('/api/v1/WebenReviewQueueRoute?limit=200',         { method: 'GET' }, { silent: true }),
        ]);

        if (conceptsRes.status === 'fulfilled') {
            const data = conceptsRes.value.data;
            if (Array.isArray(data)) setConcepts(data as WebenConcept[]);
        }
        if (sourcesRes.status === 'fulfilled') {
            const data = sourcesRes.value.data;
            if (Array.isArray(data)) setSources((data as WebenSource[]).slice(0, 8));
        }
        if (reviewRes.status === 'fulfilled') {
            const data = reviewRes.value.data as WebenReviewQueueResponse | null;
            if (data?.flashcards) setDueCount(data.flashcards.length);
        }
    }, [apiFetch]);

    useEffect(() => { refresh(); }, [refresh]);

    const handleRefresh = async () => {
        setRefreshing(true);
        try { await refresh(); } finally { setRefreshing(false); }
    };

    const totalConcepts = concepts.length;
    const activeSources = sources.filter(s => s.analysis_status !== 'failed').length;
    const weakConcepts  = concepts.filter(c => c.mastery < 0.5).slice(0, 5);

    return (
        <>
        <SidebarLayout>
            <div className="max-w-3xl mx-auto p-4 sm:p-6 space-y-6">

                {/* Header */}
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-xl font-semibold text-gray-900 flex items-center gap-2">
                            <Brain className="w-5 h-5 text-violet-500" />
                            知识网络
                        </h1>
                        <p className="text-sm text-gray-400 mt-0.5">AI 提取的概念图谱与间隔复习</p>
                    </div>
                    <button
                        onClick={handleRefresh}
                        disabled={refreshing}
                        className="p-2 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg transition-colors disabled:opacity-50"
                    >
                        <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
                    </button>
                </div>

                {/* Stats */}
                <div className="grid grid-cols-3 gap-3">
                    <StatCard
                        icon={<Layers className="w-4 h-4 text-violet-600" />}
                        label="个概念" value={totalConcepts}
                        sub="掌握度低的优先"
                        to="/weben/concepts"
                        color="bg-violet-50"
                    />
                    <StatCard
                        icon={<BookOpen className="w-4 h-4 text-amber-600" />}
                        label="待复习" value={dueCount}
                        sub={dueCount > 0 ? '立即开始' : '暂无待复习'}
                        to="/weben/review"
                        color="bg-amber-50"
                    />
                    <StatCard
                        icon={<Clock className="w-4 h-4 text-blue-600" />}
                        label="个来源" value={activeSources}
                        sub="已分析的视频"
                        to="/weben/concepts"
                        color="bg-blue-50"
                    />
                </div>

                {/* Quick actions */}
                {dueCount > 0 && (
                    <Link
                        to="/weben/review"
                        className="flex items-center justify-between w-full px-5 py-4 bg-violet-600 hover:bg-violet-700 text-white rounded-xl transition-colors"
                    >
                        <div>
                            <div className="font-semibold">开始今日复习</div>
                            <div className="text-sm text-violet-200 mt-0.5">{dueCount} 张闪卡待复习</div>
                        </div>
                        <ArrowRight className="w-5 h-5 text-violet-300" />
                    </Link>
                )}

                {/* Bottom two columns */}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">

                    {/* Recent sources */}
                    <div className="bg-white rounded-xl border border-gray-200">
                        <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
                            <h2 className="text-sm font-semibold text-gray-700">最近来源</h2>
                            <Link to="/weben/concepts" className="text-xs text-violet-500 hover:text-violet-700">查看全部</Link>
                        </div>
                        {sources.length === 0 ? (
                            <div className="px-4 py-8 text-center text-sm text-gray-400">暂无已分析来源</div>
                        ) : (
                            <div className="divide-y divide-gray-50">
                                {sources.map(s => (
                                    <SourceRow key={s.id} source={s} onSelect={setSelectedSource} />
                                ))}
                            </div>
                        )}
                    </div>

                    {/* Weak concepts */}
                    <div className="bg-white rounded-xl border border-gray-200">
                        <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
                            <h2 className="text-sm font-semibold text-gray-700">待加强概念</h2>
                            <Link to="/weben/concepts" className="text-xs text-violet-500 hover:text-violet-700">概念库</Link>
                        </div>
                        {weakConcepts.length === 0 ? (
                            <div className="px-4 py-8 text-center text-sm text-gray-400">
                                {concepts.length === 0 ? '暂无概念数据' : '全部概念已掌握 🎉'}
                            </div>
                        ) : (
                            <div className="divide-y divide-gray-50">
                                {weakConcepts.map(c => <LowMasteryConceptRow key={c.id} concept={c} />)}
                            </div>
                        )}
                    </div>
                </div>

            </div>
        </SidebarLayout>
        {/* 分析进度模态框：使用 sources.find 确保显示父组件轮询到的最新 source 数据 */}
        {selectedSource && (
            <WebenSourceAnalysisModal
                source={sources.find(s => s.id === selectedSource.id) ?? selectedSource}
                onClose={() => setSelectedSource(null)}
            />
        )}
    </>
    );
}
