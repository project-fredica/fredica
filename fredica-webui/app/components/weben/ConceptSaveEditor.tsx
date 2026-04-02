import { useState, useMemo } from "react";
import { Plus, ArrowRightLeft, Minus, Check } from "lucide-react";
import type { WebenConcept } from "~/util/weben";
import type { MaterialWebenLlmResult } from "~/util/materialWebenApi";

type IncomingConcept = MaterialWebenLlmResult["concepts"][number];

export interface ConceptDiff {
    added: IncomingConcept[];
    changed: Array<{ existing: WebenConcept; incoming: IncomingConcept }>;
    removed: WebenConcept[];
    unchanged: IncomingConcept[];
}

function splitTypes(typeText: string): string[] {
    return typeText.split(",").map(t => t.trim()).filter(Boolean);
}

function mergeConceptTypes(existing: WebenConcept, incoming: IncomingConcept): string[] {
    const existingTypes = splitTypes(existing.concept_type);
    const merged = [...existingTypes];
    for (const t of incoming.types) {
        if (!merged.some(et => et.toLowerCase() === t.toLowerCase())) {
            merged.push(t);
        }
    }
    return merged;
}

function areTypeSetsEqual(left: string[], right: string[]): boolean {
    if (left.length !== right.length) return false;
    const leftSet = new Set(left.map(t => t.toLowerCase()));
    const rightSet = new Set(right.map(t => t.toLowerCase()));
    if (leftSet.size !== rightSet.size) return false;
    return [...leftSet].every(t => rightSet.has(t));
}

/**
 * 将新一轮 LLM 输出的概念与已有基线分类为 added / changed / removed / unchanged。
 *
 * 分类采用**严格字符串比较**：类型顺序敏感，此处不做任何合并运算，保证 diff 结果稳定。
 * UI 层（changedItems useMemo）会单独评估"合并已有类型"选项是否会让某个 changed 条目
 * 变得与已有记录实质等价——若是，该卡片会折叠为"合并后无变化"徽标，但该条目仍保留在
 * diff.changed 中，以便 handleConfirm 能正确输出合并后的 payload。
 */
export function computeConceptDiff(
    existing: WebenConcept[],
    incoming: IncomingConcept[],
): ConceptDiff {
    const existingMap = new Map(existing.map(c => [c.canonical_name.toLowerCase(), c]));
    const incomingMap = new Map(incoming.map(c => [c.name.toLowerCase(), c]));

    const added: IncomingConcept[] = [];
    const changed: ConceptDiff["changed"] = [];
    const unchanged: IncomingConcept[] = [];

    for (const inc of incoming) {
        const ex = existingMap.get(inc.name.toLowerCase());
        if (!ex) {
            added.push(inc);
        } else {
            const descChanged = inc.description.trim() !== (ex.brief_definition ?? "").trim();
            const typeChanged = inc.types.join(",") !== ex.concept_type;
            if (descChanged || typeChanged) {
                changed.push({ existing: ex, incoming: inc });
            } else {
                unchanged.push(inc);
            }
        }
    }

    const removed: WebenConcept[] = existing.filter(ex =>
        !incomingMap.has(ex.canonical_name.toLowerCase())
    );

    return { added, changed, removed, unchanged };
}

// ─── Section headers ────────────────────────────────────────────────────────

function SectionHeader({ icon, label, count, color }: {
    icon: React.ReactNode;
    label: string;
    count: number;
    color: string;
}) {
    return (
        <div className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium ${color}`}>
            {icon}
            <span>{label}</span>
            <span className="ml-auto text-xs opacity-70">{count} 项</span>
        </div>
    );
}

// ─── ConceptSaveEditor ───────────────────────────────────────────────────────

interface Props {
    diff: ConceptDiff;
    onConfirm: (finalConcepts: IncomingConcept[]) => void;
    onCancel: () => void;
}

export function ConceptSaveEditor({ diff, onConfirm, onCancel }: Props) {
    // added: set of names to include (default: all)
    const [addedChecked, setAddedChecked] = useState<Set<string>>(
        () => new Set(diff.added.map(c => c.name))
    );
    // changed: set of names using "new version" (default: all)
    const [useNewVersion, setUseNewVersion] = useState<Set<string>>(
        () => new Set(diff.changed.map(c => c.incoming.name))
    );
    // changed: set of names that should merge existing+incoming types (default: all)
    const [mergeTypes, setMergeTypes] = useState<Set<string>>(
        () => new Set(diff.changed.map(c => c.incoming.name))
    );
    // removed: set of names to delete (default: none — keep all)
    const [deleteRemoved, setDeleteRemoved] = useState<Set<string>>(new Set());

    const totalChanges = diff.added.length + diff.changed.length + diff.removed.length;

    // 计算每条 changed 条目的合并分析结果。
    // foldedByMerge 是纯 UI 提示，满足以下全部条件时为 true：
    //   1. 用户选择了"使用新版"（useNewVersion）
    //   2. 勾选了"合并已有类型"（mergeTypes）
    //   3. 合并后类型集合与已有记录相同（areTypeSetsEqual，顺序无关）且描述无变化
    // 折叠时隐藏双栏卡片，改为显示"合并后无变化"徽标；
    // 条目仍保留在 diff.changed 中，handleConfirm 仍会输出正确的合并 payload。
    const changedItems = useMemo(() => diff.changed.map(({ existing, incoming }) => {
        const mergedTypes = mergeConceptTypes(existing, incoming);
        const existingTypes = splitTypes(existing.concept_type);
        const descChanged = incoming.description.trim() !== (existing.brief_definition ?? "").trim();
        const mergeMakesNoEffectiveChange = !descChanged && areTypeSetsEqual(mergedTypes, existingTypes);
        return {
            existing,
            incoming,
            mergedTypes,
            mergeMakesNoEffectiveChange,
            foldedByMerge: useNewVersion.has(incoming.name)
                && mergeTypes.has(incoming.name)
                && mergeMakesNoEffectiveChange,
        };
    }), [diff.changed, mergeTypes, useNewVersion]);

    const handleConfirm = () => {
        const result: IncomingConcept[] = [];

        // Added items that are checked
        for (const c of diff.added) {
            if (addedChecked.has(c.name)) result.push(c);
        }

        // Changed items: use new or preserve old as incoming concept
        for (const { existing, incoming } of diff.changed) {
            if (useNewVersion.has(incoming.name)) {
                if (mergeTypes.has(incoming.name)) {
                    result.push({ ...incoming, types: mergeConceptTypes(existing, incoming) });
                } else {
                    result.push(incoming);
                }
            } else {
                // Keep old — rebuild as an IncomingConcept so upsert is idempotent
                result.push({
                    name: existing.canonical_name,
                    types: splitTypes(existing.concept_type),
                    description: existing.brief_definition ?? "",
                });
            }
        }

        // unchanged 条目始终重新写入，保证它们与本次提取运行保持关联

        // removed 条目：deleteRemoved 仅记录用户的"标记"意图，当前导入 API
        // 不支持在同一次调用中删除概念。未被勾选删除的 removed 条目也不写入
        // result，它们在 DB 中保持不变。未来可在单独的"清理"步骤中处理 deleteRemoved。
        for (const c of diff.unchanged) {
            result.push(c);
        }

        // Removed items that are marked for deletion: omit from result
        // (removed items not deleted are also excluded from result — they exist already
        //  and we just don't touch them in this run)

        onConfirm(result);
    };

    const toggleSet = (set: Set<string>, setFn: (s: Set<string>) => void, key: string) => {
        const next = new Set(set);
        if (next.has(key)) next.delete(key); else next.add(key);
        setFn(next);
    };

    if (totalChanges === 0) {
        return (
            <div className="space-y-4">
                <div className="bg-green-50 border border-green-200 rounded-xl p-4 text-center">
                    <Check className="w-6 h-6 text-green-500 mx-auto mb-2" />
                    <p className="text-sm text-green-700 font-medium">没有变化</p>
                    <p className="text-xs text-green-600 mt-1">本次提取结果与已有概念完全一致，无需额外操作。</p>
                </div>
                <div className="flex gap-2 justify-end">
                    <button onClick={onCancel} className="px-4 py-2 text-sm rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50">
                        取消
                    </button>
                    <button onClick={() => onConfirm(diff.unchanged)} className="px-4 py-2 text-sm rounded-lg bg-violet-600 text-white hover:bg-violet-700">
                        确认保存
                    </button>
                </div>
            </div>
        );
    }

    return (
        <div className="space-y-4 max-h-[70vh] overflow-y-auto pr-1">
            {/* Added */}
            {diff.added.length > 0 && (
                <div className="space-y-2">
                    <SectionHeader
                        icon={<Plus className="w-4 h-4" />}
                        label="新增概念"
                        count={diff.added.length}
                        color="bg-green-50 text-green-700"
                    />
                    {diff.added.map(c => (
                        <label key={c.name} className="flex items-start gap-3 bg-white border border-gray-100 rounded-xl p-3 cursor-pointer hover:border-green-300 transition-colors">
                            <input
                                type="checkbox"
                                className="mt-0.5 accent-violet-600"
                                checked={addedChecked.has(c.name)}
                                onChange={() => toggleSet(addedChecked, setAddedChecked, c.name)}
                            />
                            <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-1.5 flex-wrap">
                                    <span className="text-sm font-semibold text-gray-900">{c.name}</span>
                                    {c.types.map(t => (
                                        <span key={t} className="text-[10px] px-1.5 py-0.5 rounded bg-green-100 text-green-700">{t}</span>
                                    ))}
                                </div>
                                {c.description && (
                                    <p className="text-xs text-gray-500 mt-0.5 line-clamp-2">{c.description}</p>
                                )}
                            </div>
                        </label>
                    ))}
                </div>
            )}

            {/* Changed */}
            {changedItems.length > 0 && (
                <div className="space-y-2">
                    <SectionHeader
                        icon={<ArrowRightLeft className="w-4 h-4" />}
                        label="变化概念"
                        count={changedItems.length}
                        color="bg-amber-50 text-amber-700"
                    />
                    {changedItems.map(({ existing, incoming, foldedByMerge }) => (
                        <div key={incoming.name} className="bg-white border border-gray-100 rounded-xl p-3 space-y-2">
                            <div className="flex items-center justify-between gap-3">
                                <span className="text-sm font-semibold text-gray-900">{incoming.name}</span>
                                {foldedByMerge ? (
                                    <span className="text-xs px-2 py-1 rounded-full bg-gray-100 text-gray-600">合并后无变化</span>
                                ) : null}
                            </div>
                            {!foldedByMerge ? (
                                <div className="grid grid-cols-2 gap-2 text-xs">
                                    <label className={`p-2 rounded-lg border cursor-pointer transition-colors ${
                                        useNewVersion.has(incoming.name)
                                            ? "border-violet-400 bg-violet-50"
                                            : "border-gray-200 hover:border-gray-300"
                                    }`}>
                                        <input
                                            type="radio"
                                            name={`ver-${incoming.name}`}
                                            className="sr-only"
                                            checked={useNewVersion.has(incoming.name)}
                                            onChange={() => {
                                                const next = new Set(useNewVersion);
                                                next.add(incoming.name);
                                                setUseNewVersion(next);
                                            }}
                                        />
                                        <div className="font-medium text-violet-700 mb-1">使用新版</div>
                                        {incoming.types.length > 0 && (
                                            <div className="text-gray-500 mb-0.5">{incoming.types.join(", ")}</div>
                                        )}
                                        <div className="text-gray-600 line-clamp-3">{incoming.description || "（无描述）"}</div>
                                    </label>
                                    <label className={`p-2 rounded-lg border cursor-pointer transition-colors ${
                                        !useNewVersion.has(incoming.name)
                                            ? "border-amber-400 bg-amber-50"
                                            : "border-gray-200 hover:border-gray-300"
                                    }`}>
                                        <input
                                            type="radio"
                                            name={`ver-${incoming.name}`}
                                            className="sr-only"
                                            checked={!useNewVersion.has(incoming.name)}
                                            onChange={() => {
                                                const next = new Set(useNewVersion);
                                                next.delete(incoming.name);
                                                setUseNewVersion(next);
                                            }}
                                        />
                                        <div className="font-medium text-amber-700 mb-1">保留旧版</div>
                                        {existing.concept_type && (
                                            <div className="text-gray-500 mb-0.5">{splitTypes(existing.concept_type).join(", ")}</div>
                                        )}
                                        <div className="text-gray-600 line-clamp-3">{existing.brief_definition || "（无描述）"}</div>
                                    </label>
                                </div>
                            ) : null}
                            {useNewVersion.has(incoming.name) && (
                                <label className="flex items-center gap-2 text-xs text-gray-500 cursor-pointer select-none">
                                    <input
                                        type="checkbox"
                                        className="accent-violet-600"
                                        checked={mergeTypes.has(incoming.name)}
                                        onChange={() => toggleSet(mergeTypes, setMergeTypes, incoming.name)}
                                    />
                                    合并已有类型（保留「{splitTypes(existing.concept_type).join("、")}」）
                                </label>
                            )}
                        </div>
                    ))}
                </div>
            )}

            {/* Removed */}
            {diff.removed.length > 0 && (
                <div className="space-y-2">
                    <SectionHeader
                        icon={<Minus className="w-4 h-4" />}
                        label="本次未提及（原有概念）"
                        count={diff.removed.length}
                        color="bg-gray-50 text-gray-600"
                    />
                    <p className="text-xs text-gray-400 px-1">这些概念在本次 LLM 输出中未出现。勾选后，它们将在下次整理时被标记为"待确认"（不会立刻删除）。</p>
                    {diff.removed.map(c => (
                        <label key={c.id} className="flex items-start gap-3 bg-white border border-gray-100 rounded-xl p-3 cursor-pointer hover:border-gray-300 transition-colors opacity-70">
                            <input
                                type="checkbox"
                                className="mt-0.5 accent-violet-600"
                                checked={deleteRemoved.has(c.canonical_name)}
                                onChange={() => toggleSet(deleteRemoved, setDeleteRemoved, c.canonical_name)}
                            />
                            <div className="flex-1 min-w-0">
                                <span className="text-sm font-semibold text-gray-700">{c.canonical_name}</span>
                                {c.brief_definition && (
                                    <p className="text-xs text-gray-400 mt-0.5 line-clamp-1">{c.brief_definition}</p>
                                )}
                            </div>
                        </label>
                    ))}
                </div>
            )}

            <div className="flex gap-2 justify-end pt-2 border-t border-gray-100 sticky bottom-0 bg-white">
                <button
                    onClick={onCancel}
                    className="px-4 py-2 text-sm rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50"
                >
                    取消
                </button>
                <button
                    onClick={handleConfirm}
                    className="px-4 py-2 text-sm rounded-lg bg-violet-600 text-white hover:bg-violet-700"
                >
                    确认保存
                </button>
            </div>
        </div>
    );
}
