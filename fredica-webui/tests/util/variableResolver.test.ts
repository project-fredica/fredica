import { describe, expect, it, vi } from "vitest";
import { createVariableResolver } from "~/util/prompt-builder/createVariableResolver";
import { VariableResolverCache } from "~/util/prompt-builder/VariableResolverCache";

describe("createVariableResolver", () => {
    it("returns unavailable for unknown variable", async () => {
        const resolver = createVariableResolver({
            variables: [],
            resolve: vi.fn(),
        });

        await expect(resolver("missing.key")).resolves.toMatchObject({
            status: "unavailable",
            unavailableReason: "未知变量: missing.key",
        });
    });

    it("maps thrown resolver errors into unavailable state", async () => {
        const resolver = createVariableResolver({
            variables: [{ key: "subtitle", label: "字幕", description: "字幕", kind: "text" }],
            resolve: async () => {
                throw new Error("请求失败");
            },
        });

        await expect(resolver("subtitle")).resolves.toMatchObject({
            status: "unavailable",
            unavailableReason: "请求失败",
        });
    });
});

describe("VariableResolverCache", () => {
    it("deduplicates inflight requests for same key", async () => {
        const inner = vi.fn(async () => {
            await Promise.resolve();
            return { kind: "text" as const, status: "ok" as const, value: "hello" };
        });
        const cache = new VariableResolverCache(inner, { ttlMs: 60_000 });

        const [a, b] = await Promise.all([cache.resolve("subtitle"), cache.resolve("subtitle")]);

        expect(a.value).toBe("hello");
        expect(b.value).toBe("hello");
        expect(inner).toHaveBeenCalledTimes(1);
    });

    it("invalidates cached result", async () => {
        let counter = 0;
        const inner = vi.fn(async () => ({ kind: "text" as const, status: "ok" as const, value: String(++counter) }));
        const cache = new VariableResolverCache(inner, { ttlMs: 60_000 });

        const first = await cache.resolve("subtitle");
        cache.invalidate("subtitle");
        const second = await cache.resolve("subtitle");

        expect(first.value).toBe("1");
        expect(second.value).toBe("2");
        expect(inner).toHaveBeenCalledTimes(2);
    });
});
