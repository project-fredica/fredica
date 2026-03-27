import { useState, useEffect } from "react";
import { useParams, Outlet, NavLink, Link, useOutletContext, useLocation } from "react-router";
import {
    ArrowLeft, LayoutDashboard, Subtitles, BrainCircuit, Users,
    Film, Zap, ListChecks, PanelLeft, X, Loader,
} from "lucide-react";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { useAppFetch, useImageProxyUrl } from "~/util/app_fetch";
import {
    type MaterialVideo, type BilibiliExtra,
    SOURCE_BADGE, formatDuration,
} from "~/components/material-library/materialTypes";
import { json_parse } from "~/util/json";

// ─── Context ──────────────────────────────────────────────────────────────────

export interface MaterialWorkspaceCtx {
    material: MaterialVideo;
    refreshMaterial: () => void;
}

export function useWorkspaceContext() {
    return useOutletContext<MaterialWorkspaceCtx>();
}

// ─── SubNav config ────────────────────────────────────────────────────────────

const TABS: readonly {
    id: string; label: string; to: string;
    icon: React.ForwardRefExoticComponent<any>;
    end: boolean; also: string[];
}[] = [
    { id: 'overview',  label: '概览',   to: '.',         icon: LayoutDashboard, end: true,  also: [] },
    { id: 'subtitle',  label: '字幕提取', to: 'subtitle',  icon: Subtitles,       end: false, also: ['subtitle-bilibili'] },
    { id: 'summary',   label: '内容总结', to: 'summary',   icon: BrainCircuit,    end: false, also: [] },
    { id: 'diarize',   label: '声纹分类', to: 'diarize',   icon: Users,           end: false, also: [] },
    { id: 'frames',    label: '帧分析',  to: 'frames',    icon: Film,            end: false, also: [] },
    { id: 'transcode', label: '转码',    to: 'transcode', icon: Zap,             end: false, also: [] },
    { id: 'tasks',     label: '任务',    to: 'tasks',     icon: ListChecks,      end: false, also: [] },
];

// ─── MaterialSwitcherDrawer ───────────────────────────────────────────────────

function MaterialSwitcherDrawer({ currentId, onClose }: { currentId: string; onClose: () => void }) {
    const [materials, setMaterials] = useState<MaterialVideo[]>([]);
    const [loading, setLoading] = useState(true);
    const { apiFetch } = useAppFetch();
    const buildProxyUrl = useImageProxyUrl();

    useEffect(() => {
        let cancelled = false;
        apiFetch<MaterialVideo[]>('/api/v1/MaterialListRoute', { method: 'POST', body: '{}' }, { silent: true })
            .then(({ data }) => { if (!cancelled && Array.isArray(data)) setMaterials(data); })
            .catch(() => { /* ignore */ })
            .finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
    }, [apiFetch]);

    return (
        <div className="fixed inset-0 z-50 flex">
            <div className="absolute inset-0 bg-black/30" onClick={onClose} />
            <div className="relative z-10 w-72 bg-white h-full shadow-xl flex flex-col">
                {/* Panel header */}
                <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100 flex-shrink-0">
                    <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">素材切换</span>
                    <button onClick={onClose} className="p-1 rounded text-gray-400 hover:text-gray-600 transition-colors">
                        <X className="w-4 h-4" />
                    </button>
                </div>
                {/* Material list */}
                <div className="flex-1 overflow-y-auto">
                    {loading ? (
                        <div className="flex items-center justify-center py-10">
                            <Loader className="w-4 h-4 animate-spin text-gray-300" />
                        </div>
                    ) : materials.length === 0 ? (
                        <p className="text-sm text-gray-400 text-center py-10">素材库为空</p>
                    ) : (
                        materials.map(m => (
                            <Link
                                key={m.id}
                                to={`/material/${m.id}`}
                                onClick={onClose}
                                className={`flex items-center gap-3 px-4 py-2.5 hover:bg-gray-50 transition-colors border-b border-gray-50 ${
                                    m.id === currentId ? 'bg-violet-50' : ''
                                }`}
                            >
                                <img
                                    src={m.cover_url ? buildProxyUrl(m.cover_url) : ''}
                                    alt={m.title}
                                    className="w-12 h-[26px] object-cover rounded flex-shrink-0 bg-gray-100"
                                />
                                <div className="flex-1 min-w-0">
                                    <p className={`text-xs font-medium truncate leading-tight ${
                                        m.id === currentId ? 'text-violet-700' : 'text-gray-700'
                                    }`}>
                                        {m.title || m.source_id}
                                    </p>
                                    {m.duration > 0 && (
                                        <p className="text-[10px] text-gray-400 font-mono mt-0.5">{formatDuration(m.duration)}</p>
                                    )}
                                </div>
                                {m.id === currentId && (
                                    <div className="w-1.5 h-1.5 rounded-full bg-violet-500 flex-shrink-0" />
                                )}
                            </Link>
                        ))
                    )}
                </div>
            </div>
        </div>
    );
}

// ─── MaterialHeader ───────────────────────────────────────────────────────────

function MaterialHeader({ material, onToggleSwitcher }: {
    material: MaterialVideo | null;
    onToggleSwitcher: () => void;
}) {
    const buildProxyUrl = useImageProxyUrl();

    let upperName: string | undefined;
    upperName = json_parse<BilibiliExtra>(material?.extra ?? '{}')?.upper_name;

    const sourceBadge = SOURCE_BADGE[material?.source_type ?? '']
        ?? { label: material?.source_type ?? '', className: 'bg-gray-100 text-gray-600' };

    return (
        <div className="flex-shrink-0 z-20 flex items-center gap-2 sm:gap-3 px-3 sm:px-4 py-2.5 border-b border-gray-100 bg-white">
            {/* Toggle switcher */}
            <button
                onClick={onToggleSwitcher}
                className="p-1.5 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors flex-shrink-0"
                title="切换素材"
            >
                <PanelLeft className="w-4 h-4" />
            </button>
            {/* Back to library */}
            <Link
                to="/material-library"
                className="p-1.5 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors flex-shrink-0"
                title="返回素材库"
            >
                <ArrowLeft className="w-4 h-4" />
            </Link>

            {material ? (
                <>
                    <img
                        src={material.cover_url ? buildProxyUrl(material.cover_url) : ''}
                        alt={material.title}
                        className="w-14 sm:w-16 h-8 sm:h-9 object-cover rounded flex-shrink-0 bg-gray-100"
                    />
                    <div className="flex-1 min-w-0">
                        <h1 className="text-sm font-semibold text-gray-900 truncate leading-snug">
                            {material.title || material.source_id}
                        </h1>
                        <div className="flex items-center gap-1.5 sm:gap-2 mt-0.5 flex-wrap">
                            <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${sourceBadge.className}`}>
                                {sourceBadge.label}
                            </span>
                            {material.duration > 0 && (
                                <span className="text-[11px] text-gray-400 font-mono">{formatDuration(material.duration)}</span>
                            )}
                            {upperName && (
                                <span className="hidden sm:inline text-[11px] text-gray-400">UP: {upperName}</span>
                            )}
                        </div>
                    </div>
                </>
            ) : (
                <div className="flex items-center gap-3 flex-1">
                    <div className="w-16 h-9 bg-gray-100 rounded animate-pulse flex-shrink-0" />
                    <div className="flex-1 space-y-1.5">
                        <div className="h-3 bg-gray-100 rounded animate-pulse w-2/3" />
                        <div className="h-2.5 bg-gray-100 rounded animate-pulse w-1/3" />
                    </div>
                </div>
            )}
        </div>
    );
}

// ─── MaterialSubNav ───────────────────────────────────────────────────────────

function MaterialSubNav() {
    const location = useLocation();
    // 最后一个路径段，用于 also[] 匹配
    const lastSegment = location.pathname.split('/').pop() ?? '';

    return (
        <div className="flex-shrink-0 z-10 border-b border-gray-100 bg-white overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
            <nav className="flex min-w-max px-2">
                {TABS.map(tab => {
                    const Icon = tab.icon;
                    return (
                        <NavLink
                            key={tab.id}
                            to={tab.to}
                            end={tab.end}
                            className={({ isActive }) => {
                                const active = isActive || tab.also.includes(lastSegment);
                                return `flex items-center gap-1.5 px-3 py-2.5 text-sm font-medium border-b-2 whitespace-nowrap transition-colors ${
                                    active
                                        ? 'border-violet-500 text-violet-700'
                                        : 'border-transparent text-gray-500 hover:text-gray-800 hover:border-gray-200'
                                }`;
                            }}
                        >
                            <Icon className="w-3.5 h-3.5" />
                            <span className="hidden xs:inline">{tab.label}</span>
                        </NavLink>
                    );
                })}
            </nav>
        </div>
    );
}

// ─── Layout ───────────────────────────────────────────────────────────────────

export default function MaterialWorkspaceLayout() {
    const { materialId } = useParams<{ materialId: string }>();
    const [material, setMaterial] = useState<MaterialVideo | null>(null);
    const [switcherOpen, setSwitcherOpen] = useState(false);
    const { apiFetch } = useAppFetch();

    const loadMaterial = async () => {
        if (!materialId) return;
        try {
            const { data } = await apiFetch<MaterialVideo>(
                `/api/v1/MaterialGetRoute?id=${encodeURIComponent(materialId)}`,
                { method: 'GET' }, { silent: true },
            );
            if (data) setMaterial(data);
        } catch { /* ignore */ }
    };

    useEffect(() => { loadMaterial(); }, [materialId]);  // eslint-disable-line react-hooks/exhaustive-deps

    const ctx: MaterialWorkspaceCtx | null = material
        ? { material, refreshMaterial: loadMaterial }
        : null;

    return (
        <SidebarLayout>
            <div className="flex flex-col flex-1 min-h-0">
                <MaterialHeader material={material} onToggleSwitcher={() => setSwitcherOpen(v => !v)} />
                <MaterialSubNav />

                <div className="flex-1 overflow-y-auto min-h-0">
                    {ctx ? (
                        <Outlet context={ctx} />
                    ) : (
                        <div className="flex items-center justify-center py-20 gap-2 text-sm text-gray-400">
                            <Loader className="w-4 h-4 animate-spin" />
                            加载素材信息…
                        </div>
                    )}
                </div>
            </div>

            {switcherOpen && materialId && (
                <MaterialSwitcherDrawer
                    currentId={materialId}
                    onClose={() => setSwitcherOpen(false)}
                />
            )}
        </SidebarLayout>
    );
}
