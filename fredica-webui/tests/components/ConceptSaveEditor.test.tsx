import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ConceptSaveEditor, computeConceptDiff } from "~/components/weben/ConceptSaveEditor";
import type { ConceptDiff } from "~/components/weben/ConceptSaveEditor";
import type { WebenConcept } from "~/util/weben";

function makeExisting(overrides: Partial<WebenConcept> = {}): WebenConcept {
    return {
        id: "concept-1",
        material_id: null,
        canonical_name: "GPIO",
        concept_type: "术语",
        brief_definition: "通用输入输出",
        metadata_json: "{}",
        confidence: 1.0,
        first_seen_at: 0,
        last_seen_at: 0,
        created_at: 0,
        updated_at: 0,
        ...overrides,
    };
}

function changedDiff(existingType: string, incomingTypes: string[], overrides?: { existingDescription?: string; incomingDescription?: string }): ConceptDiff {
    return {
        added: [],
        changed: [{
            existing: makeExisting({
                concept_type: existingType,
                brief_definition: overrides?.existingDescription ?? "通用输入输出",
            }),
            incoming: {
                name: "GPIO",
                types: incomingTypes,
                description: overrides?.incomingDescription ?? "新描述",
            },
        }],
        removed: [],
        unchanged: [],
    };
}

// ─── computeConceptDiff ──────────────────────────────────────────────────────

describe("computeConceptDiff", () => {
    it("unchanged when name, types, and description match exactly", () => {
        const existing = [makeExisting({ concept_type: "术语", brief_definition: "通用输入输出" })];
        const incoming = [{ name: "GPIO", types: ["术语"], description: "通用输入输出" }];
        const diff = computeConceptDiff(existing, incoming);
        expect(diff.unchanged).toHaveLength(1);
        expect(diff.changed).toHaveLength(0);
    });

    it("changed when description differs", () => {
        const existing = [makeExisting({ concept_type: "术语", brief_definition: "旧描述" })];
        const incoming = [{ name: "GPIO", types: ["术语"], description: "新描述" }];
        const diff = computeConceptDiff(existing, incoming);
        expect(diff.changed).toHaveLength(1);
    });

    it("changed when types differ", () => {
        const existing = [makeExisting({ concept_type: "术语,定理", brief_definition: "描述" })];
        const incoming = [{ name: "GPIO", types: ["算法"], description: "描述" }];
        const diff = computeConceptDiff(existing, incoming);
        expect(diff.changed).toHaveLength(1);
    });

    it("added for concepts not in existing", () => {
        const diff = computeConceptDiff([], [{ name: "GPIO", types: ["术语"], description: "desc" }]);
        expect(diff.added).toHaveLength(1);
        expect(diff.added[0].name).toBe("GPIO");
    });

    it("removed contains existing concepts absent from incoming", () => {
        const existing = [
            makeExisting({ canonical_name: "GPIO", concept_type: "术语", brief_definition: "接口" }),
            makeExisting({ id: "2", canonical_name: "链表", concept_type: "数据结构", brief_definition: "线性" }),
        ];
        const incoming = [{ name: "GPIO", types: ["术语"], description: "接口" }];
        const diff = computeConceptDiff(existing, incoming);
        expect(diff.removed).toHaveLength(1);
        expect(diff.removed[0].canonical_name).toBe("链表");
    });

    it("name matching is case-insensitive", () => {
        const existing = [makeExisting({ canonical_name: "GPIO", concept_type: "术语", brief_definition: "desc" })];
        const incoming = [{ name: "gpio", types: ["术语"], description: "desc" }];
        const diff = computeConceptDiff(existing, incoming);
        expect(diff.unchanged).toHaveLength(1);
        expect(diff.removed).toHaveLength(0);
    });
});

// ─── ConceptSaveEditor — types merge ────────────────────────────────────────

describe("ConceptSaveEditor — types merge", () => {
    it("merge checkbox is present and checked by default when 使用新版 is selected", () => {
        render(<ConceptSaveEditor diff={changedDiff("术语", ["术语", "算法"])} onConfirm={vi.fn()} onCancel={vi.fn()} />);
        const checkbox = screen.getByRole("checkbox", { name: /合并已有类型/ });
        expect((checkbox as HTMLInputElement).checked).toBe(true);
    });

    it("confirm with merge checked produces union of existing and incoming types", async () => {
        const user = userEvent.setup();
        const onConfirm = vi.fn();
        // existing: "术语,定理"  incoming: ["术语", "算法"]  → merged: ["术语", "定理", "算法"]
        render(<ConceptSaveEditor diff={changedDiff("术语,定理", ["术语", "算法"])} onConfirm={onConfirm} onCancel={vi.fn()} />);

        await user.click(screen.getByRole("button", { name: "确认保存" }));

        expect(onConfirm).toHaveBeenCalledOnce();
        const result = onConfirm.mock.calls[0][0] as Array<{ name: string; types: string[] }>;
        expect(result[0].types).toEqual(["术语", "定理", "算法"]);
    });

    it("merge does not duplicate existing types already present in incoming", async () => {
        const user = userEvent.setup();
        const onConfirm = vi.fn();
        // existing: "术语,定理"  incoming: ["术语", "算法"]  → "术语" must appear exactly once
        render(<ConceptSaveEditor diff={changedDiff("术语,定理", ["术语", "算法"])} onConfirm={onConfirm} onCancel={vi.fn()} />);

        await user.click(screen.getByRole("button", { name: "确认保存" }));

        const result = onConfirm.mock.calls[0][0] as Array<{ types: string[] }>;
        expect(result[0].types.filter((t: string) => t === "术语")).toHaveLength(1);
    });

    it("confirm with merge unchecked uses only incoming types", async () => {
        const user = userEvent.setup();
        const onConfirm = vi.fn();
        render(<ConceptSaveEditor diff={changedDiff("术语,定理", ["算法"])} onConfirm={onConfirm} onCancel={vi.fn()} />);

        await user.click(screen.getByRole("checkbox", { name: /合并已有类型/ }));
        await user.click(screen.getByRole("button", { name: "确认保存" }));

        const result = onConfirm.mock.calls[0][0] as Array<{ types: string[] }>;
        expect(result[0].types).toEqual(["算法"]);
    });

    it("merge checkbox disappears when switching to 保留旧版", async () => {
        const user = userEvent.setup();
        render(<ConceptSaveEditor diff={changedDiff("术语", ["算法"])} onConfirm={vi.fn()} onCancel={vi.fn()} />);

        expect(screen.getByRole("checkbox", { name: /合并已有类型/ })).toBeTruthy();

        await user.click(screen.getByText("保留旧版"));
        expect(screen.queryByRole("checkbox", { name: /合并已有类型/ })).toBeNull();
    });

    it("confirm with 保留旧版 selected uses existing types regardless of merge state", async () => {
        const user = userEvent.setup();
        const onConfirm = vi.fn();
        render(<ConceptSaveEditor diff={changedDiff("术语,定理", ["算法"])} onConfirm={onConfirm} onCancel={vi.fn()} />);

        await user.click(screen.getByText("保留旧版"));
        await user.click(screen.getByRole("button", { name: "确认保存" }));

        const result = onConfirm.mock.calls[0][0] as Array<{ types: string[] }>;
        expect(result[0].types).toEqual(["术语", "定理"]);
    });

    it("shows folded 合并后无变化 state when merge removes the only type difference", () => {
        render(<ConceptSaveEditor diff={changedDiff("术语,定理", ["术语"], {
            existingDescription: "通用输入输出",
            incomingDescription: "通用输入输出",
        })} onConfirm={vi.fn()} onCancel={vi.fn()} />);

        expect(screen.getByText("合并后无变化")).toBeTruthy();
        expect(screen.getByText("GPIO")).toBeTruthy();
    });

    it("treats type order as unchanged when merged result matches existing as a set", () => {
        render(<ConceptSaveEditor diff={changedDiff("术语,定理", ["定理", "术语"], {
            existingDescription: "通用输入输出",
            incomingDescription: "通用输入输出",
        })} onConfirm={vi.fn()} onCancel={vi.fn()} />);

        expect(screen.getByText("合并后无变化")).toBeTruthy();
    });

    it("expands changed card again after unchecking merge types", async () => {
        const user = userEvent.setup();
        render(<ConceptSaveEditor diff={changedDiff("术语,定理", ["术语"], {
            existingDescription: "通用输入输出",
            incomingDescription: "通用输入输出",
        })} onConfirm={vi.fn()} onCancel={vi.fn()} />);

        expect(screen.getByText("合并后无变化")).toBeTruthy();
        await user.click(screen.getByRole("checkbox", { name: /合并已有类型/ }));

        expect(screen.queryByText("合并后无变化")).toBeNull();
        expect(screen.getByText("使用新版")).toBeTruthy();
        expect(screen.getByText("保留旧版")).toBeTruthy();
    });

    it("does not fold when description still changes even if merged types match existing", () => {
        render(<ConceptSaveEditor diff={changedDiff("术语,定理", ["术语", "定理"])} onConfirm={vi.fn()} onCancel={vi.fn()} />);

        expect(screen.queryByText("合并后无变化")).toBeNull();
        expect(screen.getByText("使用新版")).toBeTruthy();
        expect(screen.getByText("保留旧版")).toBeTruthy();
    });

    it("shows no changes view and confirm works when diff is empty", async () => {
        const user = userEvent.setup();
        const onConfirm = vi.fn();
        const emptyDiff: ConceptDiff = { added: [], changed: [], removed: [], unchanged: [{ name: "GPIO", types: ["术语"], description: "desc" }] };
        render(<ConceptSaveEditor diff={emptyDiff} onConfirm={onConfirm} onCancel={vi.fn()} />);

        expect(screen.getByText("没有变化")).toBeTruthy();
        await user.click(screen.getByRole("button", { name: "确认保存" }));
        expect(onConfirm).toHaveBeenCalledOnce();
    });
});
