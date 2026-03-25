import { describe, expect, it } from "vitest";
import { validateWebenResult } from "~/util/materialWebenGuards";

describe("validateWebenResult", () => {
    it("blocks invalid concept types and dependent entries", () => {
        const result = validateWebenResult({
            concepts: [
                { name: "GPIO", type: "非法类型", description: "通用输入输出" },
            ],
            relations: [
                { subject: "GPIO", predicate: "用于", object: "单片机" },
            ],
            flashcards: [
                { question: "GPIO 是什么？", answer: "一种接口", concept: "GPIO" },
            ],
        });

        expect(result.sanitizedResult.concepts).toEqual([]);
        expect(result.sanitizedResult.relations).toEqual([]);
        expect(result.sanitizedResult.flashcards).toEqual([]);
        expect(result.blockingErrors.map(item => item.message)).toEqual(expect.arrayContaining([
            "概念“GPIO”的类型“非法类型”不在允许列表中",
            "关系“GPIO 用于 单片机”引用了不存在的概念",
            "闪卡“GPIO 是什么？”引用了不存在的概念“GPIO”",
        ]));
        expect(result.warnings.map(item => item.message)).toContain("当前结果没有可导入的有效概念，相关关系和闪卡也会被阻止保存。");
    });

    it("blocks invalid predicates and missing concept references", () => {
        const result = validateWebenResult({
            concepts: [
                { name: "GPIO", type: "术语", description: "通用输入输出" },
                { name: "开漏输出", type: "术语", description: "一种模式" },
            ],
            relations: [
                { subject: "GPIO", predicate: "连接", object: "开漏输出" },
                { subject: "GPIO", predicate: "用于", object: "不存在概念" },
            ],
            flashcards: [],
        });

        expect(result.sanitizedResult.concepts).toHaveLength(2);
        expect(result.sanitizedResult.relations).toEqual([]);
        expect(result.blockingErrors.map(item => item.message)).toEqual(expect.arrayContaining([
            "关系“GPIO 连接 开漏输出”的谓词不在允许列表中",
            "关系“GPIO 用于 不存在概念”引用了不存在的概念",
        ]));
    });

    it("keeps valid entries for import payloads", () => {
        const result = validateWebenResult({
            concepts: [
                { name: "GPIO", type: "术语", description: "通用输入输出", aliases: ["General Purpose IO"] },
                { name: "开漏输出", type: "术语", description: "一种输出模式" },
            ],
            relations: [
                { subject: "GPIO", predicate: "用于", object: "开漏输出", excerpt: "GPIO 常用于开漏输出" },
            ],
            flashcards: [
                { question: "GPIO 常见用途是什么？", answer: "可用于开漏输出", concept: "GPIO" },
            ],
        });

        expect(result.blockingErrors).toEqual([]);
        expect(result.warnings).toEqual([]);
        expect(result.sanitizedResult).toEqual({
            concepts: [
                { name: "GPIO", type: "术语", description: "通用输入输出", aliases: ["General Purpose IO"] },
                { name: "开漏输出", type: "术语", description: "一种输出模式" },
            ],
            relations: [
                { subject: "GPIO", predicate: "用于", object: "开漏输出", excerpt: "GPIO 常用于开漏输出" },
            ],
            flashcards: [
                { question: "GPIO 常见用途是什么？", answer: "可用于开漏输出", concept: "GPIO" },
            ],
        });
    });
});
