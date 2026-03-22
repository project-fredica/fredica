/**
 * MockBroadcastChannel
 *
 * 内存实现，同进程内以 channel name 路由消息。
 * JSDOM 不原生支持 BroadcastChannel，测试时用此 mock 替代。
 *
 * 用法：在 tests/setup.ts 中全局替换 globalThis.BroadcastChannel
 */
export class MockBroadcastChannel {
    static buses = new Map<string, Set<MockBroadcastChannel>>();

    onmessage: ((e: MessageEvent) => void) | null = null;
    private listeners = new Map<string, Set<(e: MessageEvent) => void>>();

    constructor(public readonly name: string) {
        if (!MockBroadcastChannel.buses.has(name)) {
            MockBroadcastChannel.buses.set(name, new Set());
        }
        MockBroadcastChannel.buses.get(name)!.add(this);
    }

    postMessage(data: unknown): void {
        const peers = MockBroadcastChannel.buses.get(this.name);
        if (!peers) return;
        for (const ch of peers) {
            if (ch === this) continue; // BroadcastChannel 不回发给自身
            const event = new MessageEvent("message", { data });
            ch.onmessage?.(event);
            const set = ch.listeners.get("message");
            if (set) for (const fn of set) fn(event);
        }
    }

    addEventListener(type: string, listener: (e: MessageEvent) => void): void {
        if (!this.listeners.has(type)) this.listeners.set(type, new Set());
        this.listeners.get(type)!.add(listener);
    }

    removeEventListener(type: string, listener: (e: MessageEvent) => void): void {
        this.listeners.get(type)?.delete(listener);
    }

    close(): void {
        MockBroadcastChannel.buses.get(this.name)?.delete(this);
    }

    /** 在每个测试 afterEach 中调用，清除所有 channel 状态 */
    static reset(): void {
        MockBroadcastChannel.buses.clear();
    }
}
