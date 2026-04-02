// tests/setup.ts
// Vitest 全局测试初始化

import "@testing-library/react";

// BroadcastChannel mock（JSDOM 不原生支持）
import { MockBroadcastChannel } from "./mocks/broadcastChannel";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
(globalThis as any).BroadcastChannel = MockBroadcastChannel;

// scrollIntoView mock（JSDOM 不原生支持）
window.HTMLElement.prototype.scrollIntoView = function () { /* no-op in test env */ };
