import type { PromptResolver, VariableResolution } from "./types";

interface CacheEntry {
    expiresAt: number;
    value: VariableResolution;
}

export class VariableResolverCache {
    private readonly ttlMs: number;
    private readonly resolver: PromptResolver;
    private readonly cache = new Map<string, CacheEntry>();
    private readonly inflight = new Map<string, Promise<VariableResolution>>();

    constructor(resolver: PromptResolver, options?: { ttlMs?: number }) {
        this.resolver = resolver;
        this.ttlMs = options?.ttlMs ?? 30_000;
    }

    async resolve(key: string, signal?: AbortSignal): Promise<VariableResolution> {
        const now = Date.now();
        const cached = this.cache.get(key);
        if (cached && cached.expiresAt > now) return cached.value;

        const running = this.inflight.get(key);
        if (running) return running;

        const promise = this.resolver(key, { signal })
            .then(result => {
                this.cache.set(key, {
                    value: result,
                    expiresAt: Date.now() + this.ttlMs,
                });
                return result;
            })
            .finally(() => {
                this.inflight.delete(key);
            });

        this.inflight.set(key, promise);
        return promise;
    }

    invalidate(key?: string) {
        if (key) {
            this.cache.delete(key);
            this.inflight.delete(key);
            return;
        }
        this.cache.clear();
        this.inflight.clear();
    }
}
