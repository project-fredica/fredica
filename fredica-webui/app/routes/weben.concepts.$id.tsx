import { useState, useEffect, useCallback, useRef } from "react";
import { Link, useParams } from "react-router";
import {
    ArrowLeft, Edit2, Check, X, Plus, Trash2,
    BookOpen, Network, StickyNote, ExternalLink, Layers,
    ChevronRight,
} from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import {
    type WebenConcept, type WebenConceptAlias, type WebenConceptSource,
    type WebenRelation, type WebenFlashcard, type WebenNote,
    type WebenConceptDetailResponse,
    CONCEPT_TYPES, PREDICATES, getConceptTypeInfo,
    masteryBarColor, masteryLabel, masteryTextColor,
    formatDate, formatRelativeTime, formatReviewInterval,
} from "~/util/weben";

// ─── Sub-components ────────────────────────────────────────────────────────────

function MasteryRing({ mastery }: { mastery: number }) {
    const r = 20;
    const circ = 2 * Math.PI * r;
    const filled = circ * mastery;
    return (
        <svg width={52} height={52} className="flex-shrink-0">
            <circle cx={26} cy={26} r={r} fill="none" stroke="#f3f4f6" strokeWidth={5} />
            <circle
                cx={26} cy={26} r={r} fill="none"
                className={masteryBarColor(mastery).replace('bg-', 'stroke-')}
                strokeWidth={5}
                strokeDasharray={`${filled} ${circ}`}
                strokeLinecap="round"
                transform="rotate(-90 26 26)"
            />
            <text x={26} y={30} textAnchor="middle" fontSize={10} className="fill-gray-700 font-semibold">
                {Math.round(mastery * 100)}%
            </text>
        </svg>
    );
}

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
                    <p className="text-xs text-gray-400 mb-1">掌握度</p>
                    <p className={`text-lg font-bold ${masteryTextColor(concept.mastery)}`}>
                        {masteryLabel(concept.mastery)}
                    </p>
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

// ─── Tab: 关联关系 ─────────────────────────────────────────────────────────────

function RelationRow({ relation, currentId, peerName, onDelete }: {
    relation: WebenRelation;
    currentId: string;
    peerName: string;
    onDelete: () => void;
}) {
    const isSubject = relation.subject_id === currentId;
    return (
        <div className="flex items-center gap-3 px-4 py-3 group hover:bg-gray-50 transition-colors">
            {isSubject ? (
                <>
                    <span className="text-xs text-gray-400 w-16 text-right flex-shrink-0">本概念</span>
                    <span className="text-[10px] bg-violet-100 text-violet-700 px-2 py-0.5 rounded-full flex-shrink-0">
                        {relation.predicate}
                    </span>
                    <Link
                        to={`/weben/concepts/${relation.object_id}`}
                        className="text-sm text-gray-800 hover:text-violet-600 transition-colors flex items-center gap-1 min-w-0 flex-1"
                    >
                        <span className="truncate">{peerName}</span>
                        <ExternalLink className="w-3 h-3 flex-shrink-0 opacity-0 group-hover:opacity-100" />
                    </Link>
                </>
            ) : (
                <>
                    <Link
                        to={`/weben/concepts/${relation.subject_id}`}
                        className="text-sm text-gray-800 hover:text-violet-600 transition-colors flex items-center gap-1 min-w-0 flex-1"
                    >
                        <span className="truncate">{peerName}</span>
                        <ExternalLink className="w-3 h-3 flex-shrink-0 opacity-0 group-hover:opacity-100" />
                    </Link>
                    <span className="text-[10px] bg-blue-100 text-blue-700 px-2 py-0.5 rounded-full flex-shrink-0">
                        {relation.predicate}
                    </span>
                    <span className="text-xs text-gray-400 w-16 flex-shrink-0">本概念</span>
                </>
            )}
            <button
                onClick={onDelete}
                className="opacity-0 group-hover:opacity-100 p-1 text-gray-300 hover:text-red-500 transition-all flex-shrink-0"
            >
                <Trash2 className="w-3.5 h-3.5" />
            </button>
        </div>
    );
}

function RelationsTab({ conceptId, relations, onRelationDeleted, onRelationAdded }: {
    conceptId: string;
    relations: WebenRelation[];
    onRelationDeleted: (id: string) => void;
    onRelationAdded: (r: WebenRelation) => void;
}) {
    const { apiFetch } = useAppFetch();
    const [peerNames, setPeerNames] = useState<Record<string, string>>({});
    const [showAddForm, setShowAddForm] = useState(false);
    const [newPredicate, setNewPredicate] = useState(PREDICATES[0]);
    const [newObjectName, setNewObjectName] = useState('');
    const [searchResults, setSearchResults] = useState<WebenConcept[]>([]);
    const [selectedObject, setSelectedObject] = useState<WebenConcept | null>(null);
    const [adding, setAdding] = useState(false);

    // Resolve peer concept names
    useEffect(() => {
        const peerIds = relations.map(r =>
            r.subject_id === conceptId ? r.object_id : r.subject_id
        ).filter(id => !peerNames[id]);
        if (peerIds.length === 0) return;
        peerIds.forEach(async (id) => {
            try {
                const { data } = await apiFetch(`/api/v1/WebenConceptGetRoute?id=${id}`, { method: 'GET' }, { silent: true });
                const payload = data as { concept?: { canonical_name?: string } } | null;
                if (payload?.concept?.canonical_name) {
                    setPeerNames(prev => ({ ...prev, [id]: payload.concept!.canonical_name! }));
                }
            } catch { /* silent */ }
        });
    }, [relations]);

    // Search for concepts to link
    useEffect(() => {
        if (!newObjectName.trim() || selectedObject) { setSearchResults([]); return; }
        const id = setTimeout(async () => {
            try {
                const { data } = await apiFetch<WebenConcept[]>(
                    `/api/v1/WebenConceptListRoute?limit=6&offset=0`,
                    { method: 'GET' }, { silent: true }
                );
                if (Array.isArray(data)) {
                    const filtered = data
                        .filter(c => c.id !== conceptId && c.canonical_name.includes(newObjectName))
                        .slice(0, 5);
                    setSearchResults(filtered);
                }
            } catch { /* silent */ }
        }, 300);
        return () => clearTimeout(id);
    }, [newObjectName]);

    const handleDelete = async (relationId: string) => {
        try {
            await apiFetch('/api/v1/WebenRelationDeleteRoute', {
                method: 'POST',
                body: JSON.stringify({ id: relationId }),
            }, { silent: true });
            onRelationDeleted(relationId);
        } catch { /* silent */ }
    };

    const handleAdd = async () => {
        if (!selectedObject) return;
        setAdding(true);
        try {
            const { data } = await apiFetch<WebenRelation>('/api/v1/WebenRelationCreateRoute', {
                method: 'POST',
                body: JSON.stringify({
                    subject_id: conceptId,
                    predicate: newPredicate,
                    object_id: selectedObject.id,
                    confidence: 1.0,
                }),
            }, { silent: true });
            if (data) {
                onRelationAdded(data);
                setShowAddForm(false);
                setNewObjectName('');
                setSelectedObject(null);
            }
        } catch { /* silent */ } finally {
            setAdding(false);
        }
    };

    return (
        <div className="space-y-4">
            <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                {relations.length === 0 ? (
                    <div className="py-12 text-center">
                        <Network className="w-8 h-8 text-gray-200 mx-auto mb-2" />
                        <p className="text-sm text-gray-400">暂无关联关系</p>
                    </div>
                ) : (
                    <div className="divide-y divide-gray-50">
                        {relations.map(r => (
                            <RelationRow
                                key={r.id}
                                relation={r}
                                currentId={conceptId}
                                peerName={peerNames[r.subject_id === conceptId ? r.object_id : r.subject_id] ?? '加载中…'}
                                onDelete={() => handleDelete(r.id)}
                            />
                        ))}
                    </div>
                )}
            </div>

            {/* Add relation */}
            {showAddForm ? (
                <div className="bg-white rounded-xl border border-gray-200 p-4 space-y-3">
                    <h3 className="text-sm font-semibold text-gray-700">添加关系</h3>

                    {/* Predicate selector */}
                    <div className="flex flex-wrap gap-1.5">
                        {PREDICATES.map(p => (
                            <button
                                key={p}
                                onClick={() => setNewPredicate(p)}
                                className={`text-xs px-2.5 py-1 rounded-full transition-colors ${
                                    newPredicate === p
                                        ? 'bg-violet-600 text-white'
                                        : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                                }`}
                            >
                                {p}
                            </button>
                        ))}
                    </div>

                    {/* Object search */}
                    <div className="relative">
                        {selectedObject ? (
                            <div className="flex items-center gap-2 border border-violet-300 rounded-lg px-3 py-2 bg-violet-50">
                                <span className="text-sm text-violet-800 flex-1">{selectedObject.canonical_name}</span>
                                <button onClick={() => { setSelectedObject(null); setNewObjectName(''); }}>
                                    <X className="w-3.5 h-3.5 text-violet-500" />
                                </button>
                            </div>
                        ) : (
                            <>
                                <input
                                    type="text"
                                    value={newObjectName}
                                    onChange={e => setNewObjectName(e.target.value)}
                                    placeholder="搜索目标概念…"
                                    className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 focus:ring-2 focus:ring-violet-500 focus:border-transparent outline-none"
                                />
                                {searchResults.length > 0 && (
                                    <div className="absolute top-full left-0 right-0 mt-1 bg-white border border-gray-200 rounded-lg shadow-lg z-10 overflow-hidden">
                                        {searchResults.map(c => (
                                            <button
                                                key={c.id}
                                                onClick={() => { setSelectedObject(c); setSearchResults([]); }}
                                                className="w-full text-left px-3 py-2 text-sm hover:bg-violet-50 flex items-center justify-between"
                                            >
                                                <span>{c.canonical_name}</span>
                                                <span className={`text-[10px] px-1.5 py-0.5 rounded ${getConceptTypeInfo(c.concept_type).color}`}>
                                                    {getConceptTypeInfo(c.concept_type).label}
                                                </span>
                                            </button>
                                        ))}
                                    </div>
                                )}
                            </>
                        )}
                    </div>

                    <div className="flex gap-2 justify-end">
                        <button
                            onClick={() => { setShowAddForm(false); setNewObjectName(''); setSelectedObject(null); }}
                            className="px-3 py-1.5 text-xs rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50"
                        >
                            取消
                        </button>
                        <button
                            onClick={handleAdd}
                            disabled={!selectedObject || adding}
                            className="px-3 py-1.5 text-xs rounded-lg bg-violet-600 text-white hover:bg-violet-700 disabled:opacity-40"
                        >
                            {adding ? '添加中…' : '添加关系'}
                        </button>
                    </div>
                </div>
            ) : (
                <button
                    onClick={() => setShowAddForm(true)}
                    className="w-full py-2.5 text-sm text-violet-600 hover:text-violet-700 border border-dashed border-violet-200 hover:border-violet-400 rounded-xl transition-colors flex items-center justify-center gap-1.5"
                >
                    <Plus className="w-4 h-4" />
                    添加关系
                </button>
            )}
        </div>
    );
}

// ─── Tab: 闪卡 ─────────────────────────────────────────────────────────────────

function FlashcardItem({ card, onDelete }: { card: WebenFlashcard; onDelete: () => void }) {
    const [expanded, setExpanded] = useState(false);
    return (
        <div className="border-b border-gray-50 last:border-0">
            <button
                onClick={() => setExpanded(v => !v)}
                className="w-full text-left px-4 py-3 flex items-start gap-3 hover:bg-gray-50 transition-colors group"
            >
                <div className="flex-1 min-w-0">
                    <p className="text-sm text-gray-800 leading-snug">{card.question}</p>
                    {expanded && (
                        <p className="text-sm text-violet-700 mt-2 pt-2 border-t border-gray-100 leading-relaxed">
                            {card.answer}
                        </p>
                    )}
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                    <span className="text-[10px] text-gray-400">{formatReviewInterval(card.interval_days)}</span>
                    <ChevronRight className={`w-4 h-4 text-gray-300 transition-transform ${expanded ? 'rotate-90' : ''}`} />
                </div>
            </button>
        </div>
    );
}

function FlashcardsTab({ conceptId, flashcardCount }: { conceptId: string; flashcardCount: number }) {
    const { apiFetch } = useAppFetch();
    const [cards, setCards] = useState<WebenFlashcard[]>([]);
    const [loading, setLoading] = useState(true);
    const [showAddForm, setShowAddForm] = useState(false);
    const [newQ, setNewQ] = useState('');
    const [newA, setNewA] = useState('');
    const [newType, setNewType] = useState<'qa' | 'cloze'>('qa');
    const [adding, setAdding] = useState(false);

    useEffect(() => {
        apiFetch<WebenFlashcard[]>(`/api/v1/WebenFlashcardListRoute?concept_id=${conceptId}`, { method: 'GET' }, { silent: true })
            .then(({ data }) => { if (Array.isArray(data)) setCards(data); })
            .catch(() => {})
            .finally(() => setLoading(false));
    }, [conceptId]);

    const handleAdd = async () => {
        if (!newQ.trim() || !newA.trim()) return;
        setAdding(true);
        try {
            const { data } = await apiFetch<WebenFlashcard>('/api/v1/WebenFlashcardCreateRoute', {
                method: 'POST',
                body: JSON.stringify({ concept_id: conceptId, question: newQ, answer: newA, card_type: newType }),
            }, { silent: true });
            if (data) {
                setCards(prev => [data, ...prev]);
                setShowAddForm(false);
                setNewQ(''); setNewA('');
            }
        } catch { /* silent */ } finally {
            setAdding(false);
        }
    };

    return (
        <div className="space-y-4">
            <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                {loading ? (
                    <div className="py-8 text-center text-sm text-gray-400">加载中…</div>
                ) : cards.length === 0 ? (
                    <div className="py-12 text-center">
                        <BookOpen className="w-8 h-8 text-gray-200 mx-auto mb-2" />
                        <p className="text-sm text-gray-400">暂无闪卡</p>
                    </div>
                ) : (
                    cards.map(c => <FlashcardItem key={c.id} card={c} onDelete={() => setCards(p => p.filter(x => x.id !== c.id))} />)
                )}
            </div>

            {showAddForm ? (
                <div className="bg-white rounded-xl border border-gray-200 p-4 space-y-3">
                    <div className="flex gap-2">
                        {(['qa', 'cloze'] as const).map(t => (
                            <button key={t} onClick={() => setNewType(t)}
                                className={`px-2.5 py-1 text-xs rounded-full ${newType === t ? 'bg-violet-600 text-white' : 'bg-gray-100 text-gray-600'}`}>
                                {t === 'qa' ? '问答卡' : '完形填空'}
                            </button>
                        ))}
                    </div>
                    <textarea value={newQ} onChange={e => setNewQ(e.target.value)} rows={2} placeholder="问题…"
                        className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 focus:ring-2 focus:ring-violet-500 focus:border-transparent outline-none resize-none" />
                    <textarea value={newA} onChange={e => setNewA(e.target.value)} rows={3} placeholder="答案…"
                        className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 focus:ring-2 focus:ring-violet-500 focus:border-transparent outline-none resize-none" />
                    <div className="flex gap-2 justify-end">
                        <button onClick={() => setShowAddForm(false)}
                            className="px-3 py-1.5 text-xs rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50">取消</button>
                        <button onClick={handleAdd} disabled={adding || !newQ.trim() || !newA.trim()}
                            className="px-3 py-1.5 text-xs rounded-lg bg-violet-600 text-white hover:bg-violet-700 disabled:opacity-40">
                            {adding ? '添加中…' : '添加'}
                        </button>
                    </div>
                </div>
            ) : (
                <button onClick={() => setShowAddForm(true)}
                    className="w-full py-2.5 text-sm text-violet-600 hover:text-violet-700 border border-dashed border-violet-200 hover:border-violet-400 rounded-xl transition-colors flex items-center justify-center gap-1.5">
                    <Plus className="w-4 h-4" />
                    添加闪卡
                </button>
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
            {/* Add note */}
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

            {/* Notes list */}
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

type TabKey = 'overview' | 'relations' | 'flashcards' | 'notes' | 'sources';

export default function WebenConceptDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { apiFetch } = useAppFetch();

    const [detail, setDetail] = useState<WebenConceptDetailResponse | null>(null);
    const [relations, setRelations] = useState<WebenRelation[]>([]);
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
                setRelations(payload.relations ?? []);
            }
        } catch { /* silent */ } finally {
            setLoading(false);
        }
    }, [apiFetch, id]);

    useEffect(() => { fetchDetail(); }, [fetchDetail]);

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

    const { concept, aliases, sources, flashcard_count, notes } = detail;
    const typeInfo = getConceptTypeInfo(concept.concept_type);

    const tabs: { key: TabKey; label: string; count?: number }[] = [
        { key: 'overview',   label: '概览' },
        { key: 'relations',  label: '关联关系', count: relations.length },
        { key: 'flashcards', label: '闪卡',     count: flashcard_count },
        { key: 'notes',      label: '笔记',     count: notes.length },
        { key: 'sources',    label: '来源',     count: sources.length },
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
                    <div className="flex items-start gap-4">
                        <MasteryRing mastery={concept.mastery} />
                        <div className="flex-1 min-w-0">
                            <div className="flex items-start gap-2 flex-wrap">
                                <h1 className="text-xl font-bold text-gray-900 leading-snug">
                                    {concept.canonical_name}
                                </h1>
                                <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded-md ring-1 mt-1 ${typeInfo.color}`}>
                                    {typeInfo.label}
                                </span>
                            </div>
                            <p className={`text-sm mt-1 ${masteryTextColor(concept.mastery)}`}>
                                {masteryLabel(concept.mastery)}
                                <span className="text-gray-400 ml-2">· 复习过 {concept.mastery > 0 ? '多次' : '0 次'}</span>
                            </p>
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
                        {tab === 'relations' && (
                            <RelationsTab
                                conceptId={concept.id}
                                relations={relations}
                                onRelationDeleted={id => setRelations(p => p.filter(r => r.id !== id))}
                                onRelationAdded={r => setRelations(p => [...p, r])}
                            />
                        )}
                        {tab === 'flashcards' && (
                            <FlashcardsTab conceptId={concept.id} flashcardCount={flashcard_count} />
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
