import { describe, expect, it } from "vitest";
import { parseJsonFromText } from "../../app/util/llm";

// ─── 基础：直接 JSON ──────────────────────────────────────────────────────────

describe("parseJsonFromText – plain JSON", () => {
    it("parses a plain object", () =>
        expect(parseJsonFromText('{"a":1}')).toEqual({ a: 1 }));

    it("parses a plain array", () =>
        expect(parseJsonFromText("[1,2,3]")).toEqual([1, 2, 3]));

    it("parses a JSON string primitive", () =>
        expect(parseJsonFromText('"hello"')).toBe("hello"));

    it("parses a JSON number primitive", () =>
        expect(parseJsonFromText("42")).toBe(42));

    it("parses a JSON boolean", () =>
        expect(parseJsonFromText("true")).toBe(true));

    it("parses JSON null", () =>
        expect(parseJsonFromText("null")).toBe(null));

    it("parses nested object", () =>
        expect(parseJsonFromText('{"a":{"b":[1,null,true]}}')).toEqual({
            a: { b: [1, null, true] },
        }));

    it("handles leading/trailing whitespace", () =>
        expect(parseJsonFromText('  \n  {"x":1}  \n  ')).toEqual({ x: 1 }));
});

// ─── Markdown 代码块 ──────────────────────────────────────────────────────────

describe("parseJsonFromText – fenced code blocks", () => {
    it("extracts from ```json block", () =>
        expect(parseJsonFromText('```json\n{"context_window":128000}\n```')).toEqual({
            context_window: 128000,
        }));

    it("extracts from plain ``` block", () =>
        expect(parseJsonFromText('```\n{"a":1}\n```')).toEqual({ a: 1 }));

    it("extracts array from code block", () =>
        expect(parseJsonFromText("```json\n[1,2,3]\n```")).toEqual([1, 2, 3]));

    it("ignores prose before and after code block", () =>
        expect(
            parseJsonFromText('Here is the result:\n```json\n{"ok":true}\n```\nDone.'),
        ).toEqual({ ok: true }));

    it("handles code block with extra whitespace inside", () =>
        expect(parseJsonFromText("```json\n\n  { \"k\": 99 }  \n\n```")).toEqual({
            k: 99,
        }));
});

// ─── 多个代码块 ───────────────────────────────────────────────────────────────

describe("parseJsonFromText – multiple fenced blocks", () => {
    it("returns first valid block when first is valid", () =>
        expect(
            parseJsonFromText('```json\n{"first":1}\n```\n```json\n{"second":2}\n```'),
        ).toEqual({ first: 1 }));

    it("skips invalid first block and returns second", () =>
        expect(
            parseJsonFromText('```\n{bad json}\n```\n```json\n{"ok":1}\n```'),
        ).toEqual({ ok: 1 }));

    it("skips multiple invalid blocks and returns first valid", () =>
        expect(
            parseJsonFromText(
                '```\nnot json\n```\n```\nalso bad\n```\n```json\n{"found":true}\n```',
            ),
        ).toEqual({ found: true }));
});

// ─── 裸 JSON（前后有多余文字）────────────────────────────────────────────────

describe("parseJsonFromText – embedded JSON in prose", () => {
    it("extracts object from surrounding text", () =>
        expect(
            parseJsonFromText('Here is the result: {"key":"val"} done'),
        ).toEqual({ key: "val" }));

    it("extracts array from surrounding text", () =>
        expect(parseJsonFromText("Result: [1,2,3] end")).toEqual([1, 2, 3]));

    it("extracts object when array appears later", () =>
        expect(
            parseJsonFromText('prefix {"a":1} then [2,3] suffix'),
        ).toEqual({ a: 1 }));

    it("extracts array when it appears before object", () =>
        expect(
            parseJsonFromText("prefix [1,2] then {\"a\":1} suffix"),
        ).toEqual([1, 2]));

    it("handles nested braces in embedded JSON", () =>
        expect(
            parseJsonFromText('Answer: {"outer":{"inner":42}} end'),
        ).toEqual({ outer: { inner: 42 } }));

    it("handles nested brackets in embedded JSON", () =>
        expect(parseJsonFromText("data: [[1,2],[3,4]] end")).toEqual([
            [1, 2],
            [3, 4],
        ]));
});

// ─── JSON5 宽松解析 ───────────────────────────────────────────────────────────

describe("parseJsonFromText – JSON5 relaxed parsing", () => {
    it("strips single-line comments", () =>
        expect(parseJsonFromText('{\n  // comment\n  "a": 1\n}')).toEqual({ a: 1 }));

    it("strips block comments", () =>
        expect(parseJsonFromText('{"a": /* comment */ 1}')).toEqual({ a: 1 }));

    it("handles trailing comma in object", () =>
        expect(parseJsonFromText('{"a":1,"b":2,}')).toEqual({ a: 1, b: 2 }));

    it("handles trailing comma in array", () =>
        expect(parseJsonFromText("[1,2,3,]")).toEqual([1, 2, 3]));

    it("handles unquoted keys", () =>
        expect(parseJsonFromText("{a: 1, b: 2}")).toEqual({ a: 1, b: 2 }));

    it("handles single-quoted string values", () =>
        expect(parseJsonFromText("{'key': 'value'}")).toEqual({ key: "value" }));

    it("handles combination: unquoted key + trailing comma + comment", () =>
        expect(
            parseJsonFromText("{\n  // title\n  name: 'Alice',\n  age: 30,\n}"),
        ).toEqual({ name: "Alice", age: 30 }));

    it("handles JSON5 inside a code block", () =>
        expect(
            parseJsonFromText("```json\n{\n  // note\n  result: true,\n}\n```"),
        ).toEqual({ result: true }));
});

// ─── JSONL（JSON Lines）→ 数组 ────────────────────────────────────────────────

describe("parseJsonFromText – JSONL", () => {
    it("parses two-line JSONL into array", () =>
        expect(parseJsonFromText('{"a":1}\n{"b":2}')).toEqual([
            { a: 1 },
            { b: 2 },
        ]));

    it("parses multi-line JSONL", () =>
        expect(
            parseJsonFromText('{"id":1}\n{"id":2}\n{"id":3}'),
        ).toEqual([{ id: 1 }, { id: 2 }, { id: 3 }]));

    it("skips blank lines in JSONL", () =>
        expect(parseJsonFromText('{"a":1}\n\n{"b":2}')).toEqual([
            { a: 1 },
            { b: 2 },
        ]));

    it("parses JSONL of arrays", () =>
        expect(parseJsonFromText("[1,2]\n[3,4]")).toEqual([[1, 2], [3, 4]]));

    it("does NOT trigger JSONL for single line", () =>
        // single line → parsed as plain JSON, not wrapped in array
        expect(parseJsonFromText('{"a":1}')).toEqual({ a: 1 }));

    it("skips invalid lines and still returns valid ones (≥2)", () =>
        expect(
            parseJsonFromText('{"a":1}\nnot json\n{"b":2}'),
        ).toEqual([{ a: 1 }, { b: 2 }]));
});

// ─── 失败 / 边界情况 ──────────────────────────────────────────────────────────

describe("parseJsonFromText – failure cases", () => {
    it("returns null for empty string", () =>
        expect(parseJsonFromText("")).toBeNull());

    it("returns null for whitespace-only string", () =>
        expect(parseJsonFromText("   \n  ")).toBeNull());

    it("returns null for plain prose", () =>
        expect(parseJsonFromText("This is just text.")).toBeNull());

    it("returns null for unclosed object", () =>
        expect(parseJsonFromText('{"a":1')).toBeNull());

    it("returns null for unclosed array", () =>
        expect(parseJsonFromText("[1,2,3")).toBeNull());

    it("returns null for empty code block", () =>
        expect(parseJsonFromText("```\n\n```")).toBeNull());

    it("returns null for code block with only prose", () =>
        expect(parseJsonFromText("```\nno json here\n```")).toBeNull());

    it("returns null for single invalid JSONL line", () =>
        expect(parseJsonFromText("not json at all")).toBeNull());

    it("returns null for non-string input (number)", () =>
        // @ts-expect-error intentional bad input
        expect(parseJsonFromText(42)).toBeNull());

    it("returns null for non-string input (null)", () =>
        // @ts-expect-error intentional bad input
        expect(parseJsonFromText(null)).toBeNull());

    it("returns null for non-string input (object)", () =>
        // @ts-expect-error intentional bad input
        expect(parseJsonFromText({ a: 1 })).toBeNull());
});

// ─── 真实 LLM 输出场景 ────────────────────────────────────────────────────────

describe("parseJsonFromText – realistic LLM output", () => {
    it("handles LLM preamble + fenced block", () =>
        expect(
            parseJsonFromText(
                "Sure! Here is the JSON you requested:\n\n```json\n{\"name\":\"Alice\",\"age\":30}\n```\n\nLet me know if you need anything else.",
            ),
        ).toEqual({ name: "Alice", age: 30 }));

    it("handles LLM output with inline JSON and no code block", () =>
        expect(
            parseJsonFromText(
                'The answer is {"status":"ok","count":5} as requested.',
            ),
        ).toEqual({ status: "ok", count: 5 }));

    it("handles LLM JSONL output for batch results", () =>
        expect(
            parseJsonFromText(
                '{"item":"apple","score":0.9}\n{"item":"banana","score":0.7}\n{"item":"cherry","score":0.5}',
            ),
        ).toEqual([
            { item: "apple", score: 0.9 },
            { item: "banana", score: 0.7 },
            { item: "cherry", score: 0.5 },
        ]));

    it("handles LLM JSON5 output with comments explaining fields", () =>
        expect(
            parseJsonFromText(
                "```json\n{\n  // model name\n  model: 'gpt-4o',\n  // max tokens\n  max_tokens: 4096,\n}\n```",
            ),
        ).toEqual({ model: "gpt-4o", max_tokens: 4096 }));

    it("handles deeply nested LLM response", () =>
        expect(
            parseJsonFromText(
                '```json\n{"result":{"items":[{"id":1,"tags":["a","b"]},{"id":2,"tags":[]}],"total":2}}\n```',
            ),
        ).toEqual({
            result: {
                items: [
                    { id: 1, tags: ["a", "b"] },
                    { id: 2, tags: [] },
                ],
                total: 2,
            },
        }));
});
