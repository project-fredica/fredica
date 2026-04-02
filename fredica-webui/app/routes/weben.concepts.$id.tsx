import { useState, useEffect, useCallback, useRef } from "react";
import { Link, useParams } from "react-router";
import {
    ArrowLeft, Edit2, Check, X, StickyNote, Trash2,
} from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import {
    type WebenConcept, type WebenConceptAlias, type WebenConceptSource,
    type WebenNote,
    type WebenConceptDetailResponse,
    type WebenConceptTypeHint,
    getConceptTypeInfo,
    formatDate, formatRelativeTime,
} from "~/util/weben";
import { fetchWebenConceptTypeHints } from "~/util/materialWebenApi";

// ─── Tab helpers ───────────────────────────────────────────────────────────────

function TabButton({ label, active, count, onClick }: {
    label: string; active: boolean; count?: number; onClick: () => void;
}) {
    return (
        <button
            onClick={onClick}
            className={`px-4 py-2.5 text-sm font-medium border-b-2 transition-colors whitespace-nowrap ${
                active
                    ? 'border-violet-600 text-violet-700'
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
            }`}
        >
            {label}
            {count !== undefined && (
                <span className={`ml-1.5 text-xs px-1.5 py-0.5 rounded-full ${
                    active ? 'bg-violet-100 text-violet-700' : 'bg-gray-100 text-gray-500'
                }`}>
                    {count}
                </span>
            )}
        </button>
    );
}

// ─── Tab: 概览 ─────────────────────────────────────────────────────────────────

function OverviewTab({ concept, aliases, onUpdate }: {
    concept: WebenConcept;
    aliases: WebenConceptAlias[];
    onUpdate: (patch: { brief_definition?: string; concept_type?: string }) => Promise<void>;
}) {
    const [editingDef, setEditingDef] = useState(false);
    const [defDraft, setDefDraft] = useState(concept.brief_definition ?? '');
    const [saving, setSaving] = useState(false);

    const saveDef = async () => {
        setSaving(true);
        try {
            await onUpdate({ brief_definition: defDraft });
            setEditingDef(false);
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className="space-y-5">
            {/* Definition */}
            <div className="bg-white rounded-xl border border-gray-200 p-4">
                <div className="flex items-center justify-between mb-3">
                    <h3 className="text-sm font-semibold text-gray-700">定义</h3>
                    {!editingDef && (
                        <button
                            onClick={() => setEditingDef(true)}
                            className="p-1 text-gray-400 hover:text-violet-600 transition-colors"
                        >
                            <Edit2 className="w-3.5 h-3.5" />
                        </button>
                    )}
                </div>
                {editingDef ? (
                    <div className="space-y-2">
                        <textarea
                            value={defDraft}
                            onChange={e => setDefDraft(e.target.value)}
                            rows={4}
                            className="w-full text-sm border border-gray-200 rounded-lg p-3 focus:ring-2 focus:ring-violet-500 focus:border-transparent outline-none resize-none"
                            placeholder="输入概念定义…"
                        />
                        <div className="flex gap-2 justify-end">
                            <button
                                onClick={() => { setEditingDef(false); setDefDraft(concept.brief_definition ?? ''); }}
                                className="px-3 py-1.5 text-xs rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50"
                            >
                                取消
                            </button>
                            <button
                                onClick={saveDef}
                                disabled={saving}
                                className="px-3 py-1.5 text-xs rounded-lg bg-violet-600 text-white hover:bg-violet-700 disabled:opacity-50"
                            >
                                {saving ? '保存中…' : '保存'}
                            </button>
                        </div>
                    </div>
                ) : (
                    <p className="text-sm text-gray-600 leading-relaxed">
                        {concept.brief_definition || <span className="text-gray-400 italic">暂无定义，点击编辑添加</span>}
                    </p>
                )}
            </div>

            {/* Stats */}
            <div className="grid grid-cols-2 gap-3">
                <div className="bg-white rounded-xl border border-gray-200 p-4">
                    <p className="text-xs text-gray-400 mb-1">置信度</p>
                    <p className="text-lg font-bold text-gray-900">{Math.round(concept.confidence * 100)}%</p>
                </div>
                <div className="bg-white rounded-xl border border-gray-200 p-4">
                    <p className="text-xs text-gray-400 mb-1">类型</p>
                    <div className="flex flex-wrap gap-1 mt-1">
                        {concept.concept_type.split(",").map(t => t.trim()).filter(Boolean).map(t => (
                            <span key={t} className="text-sm font-semibold text-gray-700">{t}</span>
                        ))}
                    </div>
                </div>
                <div className="bg-white rounded-xl border border-gray-200 p-4">
                    <p className="text-xs text-gray-400 mb-1">首次出现</p>
                    <p className="text-sm font-medium text-gray-700">{formatDate(concept.first_seen_at)}</p>
                </div>
                <div className="bg-white rounded-xl border border-gray-200 p-4">
                    <p className="text-xs text-gray-400 mb-1">最近出现</p>
                    <p className="text-sm font-medium text-gray-700">{formatDate(concept.last_seen_at)}</p>
                </div>
            </div>

            {/* Aliases */}
            {aliases.length > 0 && (
                <div className="bg-white rounded-xl border border-gray-200 p-4">
                    <h3 className="text-sm font-semibold text-gray-700 mb-3">别名</h3>
                    <div className="flex flex-wrap gap-2">
                        {aliases.map(a => (
                            <span key={a.id} className="text-xs bg-gray-100 text-gray-600 px-2.5 py-1 rounded-full">
                                {a.alias}
                                {a.alias_source && (
                                    <span className="text-gray-400 ml-1">· {a.alias_source}</span>
                                )}
                            </span>
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
}

// ─── Tab: 笔记 ─────────────────────────────────────────────────────────────────

function NotesTab({ conceptId, initialNotes }: { conceptId: string; initialNotes: WebenNote[] }) {
    const { apiFetch } = useAppFetch();
    const [notes, setNotes] = useState<WebenNote[]>(initialNotes);
    const [draft, setDraft] = useState('');
    const [saving, setSaving] = useState(false);
    const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

    const saveNote = useCallback(async (content: string) => {
        if (!content.trim()) return;
        setSaving(true);
        try {
            const { data } = await apiFetch<WebenNote>('/api/v1/WebenNoteSaveRoute', {
                method: 'POST',
                body: JSON.stringify({ concept_id: conceptId, content }),
            }, { silent: true });
            if (data) {
                setNotes(prev => [data, ...prev]);
                setDraft('');
            }
        } catch { /* silent */ } finally {
            setSaving(false);
        }
    }, [apiFetch, conceptId]);

    const deleteNote = async (noteId: string) => {
        try {
            await apiFetch('/api/v1/WebenNoteDeleteRoute', {
                method: 'POST',
                body: JSON.stringify({ id: noteId }),
            }, { silent: true });
            setNotes(prev => prev.filter(n => n.id !== noteId));
        } catch { /* silent */ }
    };

    return (
        <div className="space-y-4">
            <div className="bg-white rounded-xl border border-gray-200 p-4">
                <textarea
                    value={draft}
                    onChange={e => setDraft(e.target.value)}
                    rows={3}
                    placeholder="记录关于此概念的笔记…"
                    className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 focus:ring-2 focus:ring-violet-500 focus:border-transparent outline-none resize-none"
                />
                <div className="flex justify-end mt-2">
                    <button
                        onClick={() => saveNote(draft)}
                        disabled={saving || !draft.trim()}
                        className="px-3 py-1.5 text-xs rounded-lg bg-violet-600 text-white hover:bg-violet-700 disabled:opacity-40"
                    >
                        {saving ? '保存中…' : '保存笔记'}
                    </button>
                </div>
            </div>

            {notes.length > 0 && (
                <div className="space-y-2">
                    {notes.map(note => (
                        <div key={note.id} className="bg-white rounded-xl border border-gray-200 p-4 group">
                            <p className="text-sm text-gray-700 whitespace-pre-wrap leading-relaxed">{note.content}</p>
                            <div className="flex items-center justify-between mt-3">
                                <span className="text-xs text-gray-400">{formatRelativeTime(note.created_at)}</span>
                                <button
                                    onClick={() => deleteNote(note.id)}
                                    className="opacity-0 group-hover:opacity-100 p-1 text-gray-300 hover:text-red-500 transition-all"
                                >
                                    <Trash2 className="w-3.5 h-3.5" />
                                </button>
                            </div>
                        </div>
                    ))}
                </div>
            )}

            {notes.length === 0 && (
                <div className="bg-white rounded-xl border border-gray-200 py-10 text-center">
                    <StickyNote className="w-8 h-8 text-gray-200 mx-auto mb-2" />
                    <p className="text-sm text-gray-400">暂无笔记</p>
                </div>
            )}
        </div>
    );
}

// ─── Tab: 来源 ─────────────────────────────────────────────────────────────────

function SourcesTab({ sources }: { sources: WebenConceptSource[] }) {
    return (
        <div className="space-y-2">
            {sources.length === 0 ? (
                <div className="bg-white rounded-xl border border-gray-200 py-12 text-center">
                    <p className="text-sm text-gray-400">暂无来源记录</p>
                </div>
            ) : (
                sources.map(s => (
                    <div key={s.id} className="bg-white rounded-xl border border-gray-200 p-4">
                        {s.excerpt && (
                            <p className="text-sm text-gray-600 italic leading-relaxed mb-2">
                                「{s.excerpt}」
                            </p>
                        )}
                        <div className="flex items-center gap-3 text-xs text-gray-400">
                            <span>来源 ID: {s.source_id.slice(0, 8)}…</span>
                            {s.timestamp_sec !== null && (
                                <span>
                                    时间戳: {Math.floor(s.timestamp_sec / 60)}:{String(Math.floor(s.timestamp_sec % 60)).padStart(2, '0')}
                                </span>
                            )}
                        </div>
                    </div>
                ))
            )}
        </div>
    );
}

// ─── Page ──────────────────────────────────────────────────────────────────────

type TabKey = 'overview' | 'notes' | 'sources';

export default function WebenConceptDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { apiFetch } = useAppFetch();

    const [detail, setDetail] = useState<WebenConceptDetailResponse | null>(null);
    const [typeHints, setTypeHints] = useState<WebenConceptTypeHint[]>([]);
    const [loading, setLoading] = useState(true);
    const [tab, setTab] = useState<TabKey>('overview');

    const fetchDetail = useCallback(async () => {
        if (!id) return;
        setLoading(true);
        try {
            const { data } = await apiFetch(`/api/v1/WebenConceptGetRoute?id=${id}`, { method: 'GET' }, { silent: true });
            const payload = data as WebenConceptDetailResponse | null;
            if (payload?.concept) {
                setDetail(payload);
            }
        } catch { /* silent */ } finally {
            setLoading(false);
        }
    }, [apiFetch, id]);

    useEffect(() => { fetchDetail(); }, [fetchDetail]);

    useEffect(() => {
        fetchWebenConceptTypeHints(apiFetch)
            .then(setTypeHints)
            .catch(() => setTypeHints([]));
    }, [apiFetch]);

    const handleUpdate = async (patch: { brief_definition?: string; concept_type?: string }) => {
        if (!id) return;
        await apiFetch('/api/v1/WebenConceptUpdateRoute', {
            method: 'POST',
            body: JSON.stringify({ id, ...patch }),
        }, { silent: true });
        await fetchDetail();
    };

    if (loading) {
        return (
            <SidebarLayout>
                <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-4">
                    <div className="h-24 bg-gray-100 rounded-xl animate-pulse" />
                    <div className="h-10 bg-gray-100 rounded-xl animate-pulse" />
                    <div className="h-48 bg-gray-100 rounded-xl animate-pulse" />
                </div>
            </SidebarLayout>
        );
    }

    if (!detail) {
        return (
            <SidebarLayout>
                <div className="max-w-2xl mx-auto p-4 sm:p-6 text-center py-20">
                    <p className="text-gray-400">概念不存在或加载失败</p>
                    <Link to="/weben/concepts" className="mt-4 inline-block text-sm text-violet-600 hover:text-violet-700">
                        ← 返回概念库
                    </Link>
                </div>
            </SidebarLayout>
        );
    }

    const { concept, aliases, sources, notes } = detail;

    const tabs: { key: TabKey; label: string; count?: number }[] = [
        { key: 'overview', label: '概览' },
        { key: 'notes',    label: '笔记',  count: notes.length },
        { key: 'sources',  label: '来源',  count: sources.length },
    ];

    return (
        <SidebarLayout>
            <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-4">

                {/* Back */}
                <Link
                    to="/weben/concepts"
                    className="inline-flex items-center gap-1.5 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                >
                    <ArrowLeft className="w-3.5 h-3.5" />
                    概念库
                </Link>

                {/* Header card */}
                <div className="bg-white rounded-xl border border-gray-200 p-5">
                    <div className="flex items-start gap-3">
                        <div className="flex-1 min-w-0">
                            <div className="flex items-start gap-2 flex-wrap">
                                <h1 className="text-xl font-bold text-gray-900 leading-snug">
                                    {concept.canonical_name}
                                </h1>
                                {concept.concept_type.split(",").map(t => t.trim()).filter(Boolean).map(t => {
                                    const info = getConceptTypeInfo(t, typeHints);
                                    return (
                                        <span key={t} className={`text-[10px] font-medium px-1.5 py-0.5 rounded-md ring-1 mt-1 ${info.color}`}>
                                            {info.label}
                                        </span>
                                    );
                                })}
                            </div>
                            {concept.brief_definition && (
                                <p className="text-sm text-gray-500 mt-1 leading-relaxed line-clamp-2">
                                    {concept.brief_definition}
                                </p>
                            )}
                        </div>
                    </div>
                </div>

                {/* Tabs */}
                <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                    <div className="flex overflow-x-auto border-b border-gray-100 px-2">
                        {tabs.map(t => (
                            <TabButton
                                key={t.key}
                                label={t.label}
                                count={t.count}
                                active={tab === t.key}
                                onClick={() => setTab(t.key)}
                            />
                        ))}
                    </div>

                    <div className="p-4">
                        {tab === 'overview' && (
                            <OverviewTab concept={concept} aliases={aliases} onUpdate={handleUpdate} />
                        )}
                        {tab === 'notes' && (
                            <NotesTab conceptId={concept.id} initialNotes={notes} />
                        )}
                        {tab === 'sources' && (
                            <SourcesTab sources={sources} />
                        )}
                    </div>
                </div>

            </div>
        </SidebarLayout>
    );
}
