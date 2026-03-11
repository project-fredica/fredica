import { useState, useEffect, useCallback } from "react";
import { Link } from "react-router";
import { BookOpen, Check, X, ArrowLeft, RotateCcw, Trophy } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import {
    type WebenFlashcard, type WebenReviewQueueResponse,
    masteryTextColor, masteryLabel,
} from "~/util/weben";

// ─── Rating button config ────────────────────────────────────────────────────

const RATINGS = [
    { label: '再来',  value: 1, color: 'bg-red-50 border-red-200 text-red-700 hover:bg-red-100' },
    { label: '困难',  value: 2, color: 'bg-orange-50 border-orange-200 text-orange-700 hover:bg-orange-100' },
    { label: '还行',  value: 3, color: 'bg-yellow-50 border-yellow-200 text-yellow-700 hover:bg-yellow-100' },
    { label: '简单',  value: 5, color: 'bg-green-50 border-green-200 text-green-700 hover:bg-green-100' },
] as const;

// ─── Progress bar ────────────────────────────────────────────────────────────

function ProgressBar({ current, total }: { current: number; total: number }) {
    const pct = total > 0 ? (current / total) * 100 : 0;
    return (
        <div className="flex items-center gap-3">
            <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                <div
                    className="h-full bg-violet-500 rounded-full transition-all duration-500"
                    style={{ width: `${pct}%` }}
                />
            </div>
            <span className="text-xs text-gray-400 tabular-nums whitespace-nowrap">
                {current} / {total}
            </span>
        </div>
    );
}

// ─── Flashcard ────────────────────────────────────────────────────────────────

function Flashcard({
    card,
    conceptName,
    revealed,
    onReveal,
    onRate,
    submitting,
}: {
    card: WebenFlashcard;
    conceptName: string;
    revealed: boolean;
    onReveal: () => void;
    onRate: (rating: number) => void;
    submitting: boolean;
}) {
    return (
        <div className="flex flex-col gap-4">
            {/* Concept name chip */}
            <div className="text-center">
                <span className="text-xs bg-violet-100 text-violet-700 px-2.5 py-1 rounded-full">
                    {conceptName}
                </span>
            </div>

            {/* Card face */}
            <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
                {/* Question */}
                <div className="p-6 min-h-[120px] flex items-center justify-center">
                    <p className="text-base font-medium text-gray-900 text-center leading-relaxed">
                        {card.question}
                    </p>
                </div>

                {/* Reveal divider */}
                {!revealed ? (
                    <div className="border-t border-dashed border-gray-200 p-4 flex justify-center">
                        <button
                            onClick={onReveal}
                            className="px-8 py-2.5 bg-violet-600 text-white text-sm font-medium rounded-xl hover:bg-violet-700 transition-colors"
                        >
                            显示答案
                        </button>
                    </div>
                ) : (
                    <>
                        <div className="border-t border-dashed border-gray-200 bg-violet-50 p-6 min-h-[100px] flex items-center justify-center">
                            <p className="text-sm text-violet-800 text-center leading-relaxed whitespace-pre-wrap">
                                {card.answer}
                            </p>
                        </div>

                        {/* Rating buttons */}
                        <div className="border-t border-gray-100 p-4">
                            <p className="text-xs text-gray-400 text-center mb-3">你掌握得怎么样？</p>
                            <div className="grid grid-cols-4 gap-2">
                                {RATINGS.map(r => (
                                    <button
                                        key={r.value}
                                        onClick={() => onRate(r.value)}
                                        disabled={submitting}
                                        className={`py-2.5 text-sm font-medium rounded-xl border transition-colors disabled:opacity-50 ${r.color}`}
                                    >
                                        {r.label}
                                    </button>
                                ))}
                            </div>
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}

// ─── Done screen ──────────────────────────────────────────────────────────────

function DoneScreen({ total, onRestart }: { total: number; onRestart: () => void }) {
    return (
        <div className="flex flex-col items-center justify-center py-16 gap-6 text-center">
            <div className="w-20 h-20 bg-violet-100 rounded-full flex items-center justify-center">
                <Trophy className="w-10 h-10 text-violet-600" />
            </div>
            <div>
                <h2 className="text-xl font-bold text-gray-900">复习完成！</h2>
                <p className="text-sm text-gray-500 mt-1">共复习了 {total} 张闪卡</p>
            </div>
            <div className="flex gap-3">
                <button
                    onClick={onRestart}
                    className="px-4 py-2 text-sm text-gray-600 border border-gray-200 rounded-xl hover:bg-gray-50 flex items-center gap-2"
                >
                    <RotateCcw className="w-4 h-4" />
                    重新加载
                </button>
                <Link
                    to="/weben"
                    className="px-4 py-2 text-sm bg-violet-600 text-white rounded-xl hover:bg-violet-700 flex items-center gap-2"
                >
                    返回知识网络
                </Link>
            </div>
        </div>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function WebenReviewPage() {
    const { apiFetch } = useAppFetch();

    const [cards,       setCards]       = useState<WebenFlashcard[]>([]);
    const [names,       setNames]       = useState<Record<string, string>>({});
    const [loading,     setLoading]     = useState(true);
    const [current,     setCurrent]     = useState(0);
    const [revealed,    setRevealed]    = useState(false);
    const [submitting,  setSubmitting]  = useState(false);
    const [done,        setDone]        = useState(false);
    const [reviewed,    setReviewed]    = useState(0);

    const loadQueue = useCallback(async () => {
        setLoading(true);
        setDone(false);
        setCurrent(0);
        setRevealed(false);
        setReviewed(0);
        try {
            const { data } = await apiFetch(
                '/api/v1/WebenReviewQueueRoute?limit=50',
                { method: 'GET' },
                { silent: true },
            );
            const q = data as WebenReviewQueueResponse | null;
            if (q?.flashcards) {
                setCards(q.flashcards);
                setNames(q.concept_names ?? {});
            }
        } catch { /* silent */ } finally {
            setLoading(false);
        }
    }, [apiFetch]);

    useEffect(() => { loadQueue(); }, [loadQueue]);

    const submitRating = async (rating: number) => {
        if (submitting || current >= cards.length) return;
        setSubmitting(true);
        const card = cards[current];
        try {
            await apiFetch('/api/v1/WebenFlashcardReviewRoute', {
                method: 'POST',
                body: JSON.stringify({
                    flashcard_id: card.id,
                    rating,
                    review_type: 'quiz',
                }),
            }, { silent: true });
        } catch { /* silent */ } finally {
            setSubmitting(false);
        }
        const next = current + 1;
        setReviewed(r => r + 1);
        if (next >= cards.length) {
            setDone(true);
        } else {
            setCurrent(next);
            setRevealed(false);
        }
    };

    const card = cards[current];
    const conceptName = card ? (names[card.concept_id] ?? '未知概念') : '';

    return (
        <SidebarLayout>
            <div className="max-w-lg mx-auto p-4 sm:p-6 space-y-4">

                {/* Header */}
                <div className="flex items-center justify-between">
                    <Link
                        to="/weben"
                        className="inline-flex items-center gap-1.5 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                    >
                        <ArrowLeft className="w-3.5 h-3.5" />
                        知识网络
                    </Link>
                    <h1 className="text-sm font-semibold text-gray-700 flex items-center gap-2">
                        <BookOpen className="w-4 h-4 text-violet-500" />
                        间隔复习
                    </h1>
                    <div className="w-16" />
                </div>

                {loading ? (
                    <div className="space-y-4">
                        <div className="h-6 bg-gray-100 rounded-full animate-pulse" />
                        <div className="h-60 bg-gray-100 rounded-2xl animate-pulse" />
                    </div>
                ) : done ? (
                    <DoneScreen total={reviewed} onRestart={loadQueue} />
                ) : cards.length === 0 ? (
                    <div className="bg-white rounded-2xl border border-gray-200 py-20 text-center">
                        <Check className="w-12 h-12 text-green-400 mx-auto mb-3" />
                        <h2 className="text-base font-semibold text-gray-800">今天没有待复习的闪卡</h2>
                        <p className="text-sm text-gray-400 mt-1">所有卡片都已复习，继续保持！</p>
                        <Link
                            to="/weben"
                            className="mt-6 inline-block px-4 py-2 text-sm bg-violet-600 text-white rounded-xl hover:bg-violet-700"
                        >
                            返回知识网络
                        </Link>
                    </div>
                ) : (
                    <>
                        <ProgressBar current={reviewed} total={cards.length} />
                        <Flashcard
                            card={card}
                            conceptName={conceptName}
                            revealed={revealed}
                            onReveal={() => setRevealed(true)}
                            onRate={submitRating}
                            submitting={submitting}
                        />
                    </>
                )}

            </div>
        </SidebarLayout>
    );
}
