import { describe, expect, it } from "vitest";
import {
    isJsonArray,
    isJsonObject,
    isJsonPrimitive,
    isJsonValue,
    json_parse,
} from "../../app/util/json";

// ─── isJsonPrimitive ──────────────────────────────────────────────────────────

describe("isJsonPrimitive", () => {
    it("accepts string", () => expect(isJsonPrimitive("hello")).toBe(true));
    it("accepts empty string", () => expect(isJsonPrimitive("")).toBe(true));
    it("accepts true", () => expect(isJsonPrimitive(true)).toBe(true));
    it("accepts false", () => expect(isJsonPrimitive(false)).toBe(true));
    it("accepts null", () => expect(isJsonPrimitive(null)).toBe(true));
    it("accepts 0", () => expect(isJsonPrimitive(0)).toBe(true));
    it("accepts negative number", () =>
        expect(isJsonPrimitive(-42)).toBe(true));
    it("accepts float", () => expect(isJsonPrimitive(3.14)).toBe(true));

    it("rejects NaN", () => expect(isJsonPrimitive(NaN)).toBe(false));
    it("rejects Infinity", () => expect(isJsonPrimitive(Infinity)).toBe(false));
    it("rejects -Infinity", () =>
        expect(isJsonPrimitive(-Infinity)).toBe(false));
    it("rejects undefined", () =>
        expect(isJsonPrimitive(undefined)).toBe(false));
    it("rejects object", () => expect(isJsonPrimitive({})).toBe(false));
    it("rejects array", () => expect(isJsonPrimitive([])).toBe(false));
    it("rejects function", () => expect(isJsonPrimitive(() => {})).toBe(false));
    it("rejects symbol", () => expect(isJsonPrimitive(Symbol())).toBe(false));
    it("rejects bigint", () => expect(isJsonPrimitive(1n)).toBe(false));
});

// ─── isJsonObject ─────────────────────────────────────────────────────────────

describe("isJsonObject", () => {
    it("accepts empty plain object", () => expect(isJsonObject({})).toBe(true));
    it("accepts flat object", () =>
        expect(isJsonObject({ a: 1, b: "x" })).toBe(true));
    it("accepts nested object", () =>
        expect(isJsonObject({ a: { b: { c: null } } })).toBe(true));
    it("accepts object with array value", () =>
        expect(isJsonObject({ arr: [1, 2, 3] })).toBe(true));
    it("accepts null-prototype object (Object.create(null))", () => {
        const o = Object.create(null) as Record<string, unknown>;
        o.key = "val";
        expect(isJsonObject(o)).toBe(true);
    });

    // ── 原型链拒绝 ──────────────────────────────────────────────────────────
    it("rejects Date", () => expect(isJsonObject(new Date())).toBe(false));
    it("rejects RegExp", () => expect(isJsonObject(/abc/)).toBe(false));
    it("rejects Map", () => expect(isJsonObject(new Map())).toBe(false));
    it("rejects Set", () => expect(isJsonObject(new Set())).toBe(false));
    it("rejects Error", () => expect(isJsonObject(new Error("e"))).toBe(false));
    it("rejects class instance", () => {
        class Foo {
            x = 1;
        }
        expect(isJsonObject(new Foo())).toBe(false);
    });
    it("rejects Promise", () =>
        expect(isJsonObject(Promise.resolve())).toBe(false));
    it("rejects WeakMap", () =>
        expect(isJsonObject(new WeakMap())).toBe(false));

    // ── 非对象类型 ──────────────────────────────────────────────────────────
    it("rejects null", () => expect(isJsonObject(null)).toBe(false));
    it("rejects array", () => expect(isJsonObject([1, 2])).toBe(false));
    it("rejects string", () => expect(isJsonObject("str")).toBe(false));
    it("rejects number", () => expect(isJsonObject(42)).toBe(false));
    it("rejects boolean", () => expect(isJsonObject(true)).toBe(false));
    it("rejects undefined", () => expect(isJsonObject(undefined)).toBe(false));
    it("rejects function", () => expect(isJsonObject(() => {})).toBe(false));

    // ── 非法值递归拒绝 ──────────────────────────────────────────────────────
    it("rejects object with undefined value", () =>
        expect(isJsonObject({ a: undefined })).toBe(false));
    it("rejects object with NaN value", () =>
        expect(isJsonObject({ a: NaN })).toBe(false));
    it("rejects object with function value", () =>
        expect(isJsonObject({ fn: () => {} })).toBe(false));
    it("rejects object with Date value", () =>
        expect(isJsonObject({ d: new Date() })).toBe(false));
    it("rejects object with nested invalid value", () =>
        expect(isJsonObject({ a: { b: undefined } })).toBe(false));
    it("rejects object with symbol value", () =>
        expect(isJsonObject({ s: Symbol() })).toBe(false));
});

// ─── isJsonArray ──────────────────────────────────────────────────────────────

describe("isJsonArray", () => {
    it("accepts empty array", () => expect(isJsonArray([])).toBe(true));
    it("accepts array of primitives", () =>
        expect(isJsonArray([1, "a", true, null])).toBe(true));
    it("accepts nested array", () =>
        expect(isJsonArray([[1, 2], [3, 4]])).toBe(true));
    it("accepts array of objects", () =>
        expect(isJsonArray([{ a: 1 }, { b: 2 }])).toBe(true));
    it("accepts mixed array", () =>
        expect(isJsonArray([1, "x", null, { k: "v" }, [2]])).toBe(true));

    it("rejects non-array object", () => expect(isJsonArray({})).toBe(false));
    it("rejects null", () => expect(isJsonArray(null)).toBe(false));
    it("rejects string", () => expect(isJsonArray("abc")).toBe(false));
    it("rejects number", () => expect(isJsonArray(42)).toBe(false));
    it("rejects undefined", () => expect(isJsonArray(undefined)).toBe(false));

    it("rejects array with undefined element", () =>
        expect(isJsonArray([1, undefined])).toBe(false));
    it("rejects array with NaN element", () =>
        expect(isJsonArray([NaN])).toBe(false));
    it("rejects array with function element", () =>
        expect(isJsonArray([() => {}])).toBe(false));
    it("rejects array with Date element", () =>
        expect(isJsonArray([new Date()])).toBe(false));
    it("rejects array with nested invalid element", () =>
        expect(isJsonArray([[undefined]])).toBe(false));
});

// ─── isJsonValue ──────────────────────────────────────────────────────────────

describe("isJsonValue", () => {
    it("accepts all primitives", () => {
        expect(isJsonValue(null)).toBe(true);
        expect(isJsonValue(true)).toBe(true);
        expect(isJsonValue(0)).toBe(true);
        expect(isJsonValue("")).toBe(true);
    });
    it("accepts plain object", () => expect(isJsonValue({ a: 1 })).toBe(true));
    it("accepts array", () => expect(isJsonValue([1, 2])).toBe(true));
    it("accepts deeply nested structure", () =>
        expect(isJsonValue({ a: [{ b: [null, true, 1] }] })).toBe(true));

    it("rejects undefined", () => expect(isJsonValue(undefined)).toBe(false));
    it("rejects NaN", () => expect(isJsonValue(NaN)).toBe(false));
    it("rejects Date", () => expect(isJsonValue(new Date())).toBe(false));
    it("rejects function", () => expect(isJsonValue(() => {})).toBe(false));
    it("rejects object with invalid nested value", () =>
        expect(isJsonValue({ x: new Date() })).toBe(false));
});

// ─── 循环引用 ─────────────────────────────────────────────────────────────────

describe("circular reference detection", () => {
    it("isJsonObject rejects self-referencing object", () => {
        const o: Record<string, unknown> = { a: 1 };
        o.self = o;
        expect(isJsonObject(o)).toBe(false);
    });

    it("isJsonArray rejects self-referencing array", () => {
        const a: unknown[] = [1, 2];
        a.push(a);
        expect(isJsonArray(a)).toBe(false);
    });

    it("isJsonObject rejects mutually referencing objects", () => {
        const a: Record<string, unknown> = { x: 1 };
        const b: Record<string, unknown> = { y: 2 };
        a.b = b;
        b.a = a;
        expect(isJsonObject(a)).toBe(false);
    });

    it("isJsonArray rejects mutually referencing arrays", () => {
        const a: unknown[] = [1];
        const b: unknown[] = [2];
        a.push(b);
        b.push(a);
        expect(isJsonArray(a)).toBe(false);
    });

    it("isJsonObject rejects deep cycle (a→b→c→a)", () => {
        const a: Record<string, unknown> = {};
        const b: Record<string, unknown> = {};
        const c: Record<string, unknown> = {};
        a.b = b;
        b.c = c;
        c.a = a;
        expect(isJsonObject(a)).toBe(false);
    });

    it("isJsonArray rejects array containing object that refs back to array", () => {
        const arr: unknown[] = [];
        const obj: Record<string, unknown> = { arr };
        arr.push(obj);
        expect(isJsonArray(arr)).toBe(false);
    });

    it("isJsonValue rejects circular object", () => {
        const o: Record<string, unknown> = {};
        o.o = o;
        expect(isJsonValue(o)).toBe(false);
    });

    it("isJsonValue rejects circular array", () => {
        const a: unknown[] = [];
        a.push(a);
        expect(isJsonValue(a)).toBe(false);
    });

    it("does NOT false-positive on diamond-shaped (shared reference, no cycle)", () => {
        // shared leaf is fine — it's not a cycle
        const shared = { x: 1 };
        const o = { a: shared, b: shared };
        expect(isJsonObject(o)).toBe(true);
    });

    it("does NOT false-positive on shared array leaf", () => {
        const leaf = [1, 2, 3];
        const o = { a: leaf, b: leaf };
        expect(isJsonObject(o)).toBe(true);
    });
});

// ─── 极端 / 邪恶输入 ──────────────────────────────────────────────────────────

describe("evil edge cases – isJsonObject", () => {
    it("rejects object with __proto__ pollution attempt", () => {
        // JSON.parse 可以产生带 __proto__ 键的对象，但值若非 JsonValue 应拒绝
        const o = JSON.parse('{"__proto__":{"evil":true}}') as unknown;
        // __proto__ 键本身是字符串，值是对象，应通过（JSON.parse 产生的是安全的）
        expect(isJsonObject(o)).toBe(true);
    });

    it("rejects object whose value is a function", () =>
        expect(isJsonObject({ fn: () => 42 })).toBe(false));

    it("rejects object whose value is undefined", () =>
        expect(isJsonObject({ u: undefined })).toBe(false));

    it("rejects object whose value is a symbol", () =>
        expect(isJsonObject({ s: Symbol("x") })).toBe(false));

    it("rejects object whose value is bigint", () =>
        expect(isJsonObject({ n: 9007199254740993n })).toBe(false));

    it("rejects object whose value is Infinity", () =>
        expect(isJsonObject({ n: Infinity })).toBe(false));

    it("rejects object whose value is NaN", () =>
        expect(isJsonObject({ n: NaN })).toBe(false));

    it("rejects object with deeply nested Date", () =>
        expect(isJsonObject({ a: { b: { c: new Date() } } })).toBe(false));

    it("rejects object with deeply nested undefined", () =>
        expect(isJsonObject({ a: { b: { c: undefined } } })).toBe(false));

    it("accepts very large flat object", () => {
        const o: Record<string, number> = {};
        for (let i = 0; i < 10000; i++) o[`k${i}`] = i;
        expect(isJsonObject(o)).toBe(true);
    });

    it("accepts deeply nested valid object (100 levels)", () => {
        let o: unknown = { leaf: true };
        for (let i = 0; i < 100; i++) o = { child: o };
        expect(isJsonObject(o)).toBe(true);
    });

    it("rejects subclass of Object", () => {
        class MyObj {}
        expect(isJsonObject(new MyObj())).toBe(false);
    });

    it("rejects boxed String object", () => {
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonObject(new String("hi"))).toBe(false);
    });

    it("rejects boxed Number object", () => {
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonObject(new Number(1))).toBe(false);
    });

    it("rejects boxed Boolean object", () => {
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonObject(new Boolean(true))).toBe(false);
    });
});

describe("evil edge cases – isJsonArray", () => {
    it("rejects array-like object (not a real array)", () => {
        const arrayLike = { 0: "a", 1: "b", length: 2 };
        expect(isJsonArray(arrayLike)).toBe(false);
    });

    it("rejects sparse array with holes", () => {
        // eslint-disable-next-line no-sparse-arrays
        const sparse = [1, , 3] as unknown[];
        expect(isJsonArray(sparse)).toBe(false);
    });

    it("rejects array with undefined element", () =>
        expect(isJsonArray([1, undefined, 3])).toBe(false));

    it("rejects array with function element", () =>
        expect(isJsonArray([() => {}])).toBe(false));

    it("rejects array with symbol element", () =>
        expect(isJsonArray([Symbol()])).toBe(false));

    it("rejects array with bigint element", () =>
        expect(isJsonArray([1n])).toBe(false));

    it("rejects array with Date element", () =>
        expect(isJsonArray([new Date()])).toBe(false));

    it("rejects array with deeply nested invalid element", () =>
        expect(isJsonArray([[[new Date()]]])).toBe(false));

    it("accepts very large flat array", () => {
        const a = Array.from({ length: 10000 }, (_, i) => i);
        expect(isJsonArray(a)).toBe(true);
    });

    it("accepts deeply nested valid array (100 levels)", () => {
        let a: unknown = [42];
        for (let i = 0; i < 100; i++) a = [a];
        expect(isJsonArray(a)).toBe(true);
    });
});

describe("evil edge cases – isJsonPrimitive", () => {
    it("rejects Number.POSITIVE_INFINITY", () =>
        expect(isJsonPrimitive(Number.POSITIVE_INFINITY)).toBe(false));

    it("rejects Number.NEGATIVE_INFINITY", () =>
        expect(isJsonPrimitive(Number.NEGATIVE_INFINITY)).toBe(false));

    it("rejects Number.NaN", () =>
        expect(isJsonPrimitive(Number.NaN)).toBe(false));

    it("accepts Number.MAX_SAFE_INTEGER", () =>
        expect(isJsonPrimitive(Number.MAX_SAFE_INTEGER)).toBe(true));

    it("accepts Number.MIN_SAFE_INTEGER", () =>
        expect(isJsonPrimitive(Number.MIN_SAFE_INTEGER)).toBe(true));

    it("accepts Number.EPSILON", () =>
        expect(isJsonPrimitive(Number.EPSILON)).toBe(true));

    it("rejects bigint", () => expect(isJsonPrimitive(42n)).toBe(false));

    it("rejects symbol", () =>
        expect(isJsonPrimitive(Symbol("x"))).toBe(false));

    it("rejects object", () => expect(isJsonPrimitive({})).toBe(false));

    it("rejects array", () => expect(isJsonPrimitive([])).toBe(false));

    it("accepts empty string", () => expect(isJsonPrimitive("")).toBe(true));

    it("accepts string with special chars", () =>
        expect(isJsonPrimitive("\u0000\n\t\r")).toBe(true));
});

// ─── 包装对象（Boxed Primitives）────────────────────────────────────────────
//
// 设计决策：new String() / new Number() / new Boolean() 不应被任何守卫接受。
//
// 理由：
//   1. typeof new String("x") === "object"，不满足 isJsonPrimitive 的 typeof 检查。
//   2. 原型链为 String.prototype，不满足 isJsonObject 的原型链检查。
//   3. 因此 isJsonValue 整体拒绝——这与 JSON 序列化行为一致：
//      JSON.stringify(new String("x")) === '"x"'，
//      但 JSON.parse('"x"') 返回的是原始字符串，而非包装对象，
//      无法做到无损往返。

describe("boxed primitives – should be rejected by all guards", () => {
    // isJsonPrimitive 只检查原始类型，包装对象的 typeof 为 "object" → 直接拒绝
    it("isJsonPrimitive rejects new String()", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonPrimitive(new String("hello"))).toBe(false));

    it("isJsonPrimitive rejects new Number()", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonPrimitive(new Number(42))).toBe(false));

    it("isJsonPrimitive rejects new Boolean()", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonPrimitive(new Boolean(true))).toBe(false));

    it("isJsonPrimitive rejects new Boolean(false)", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonPrimitive(new Boolean(false))).toBe(false));

    // isJsonObject 通过原型链拒绝：String.prototype / Number.prototype / Boolean.prototype
    it("isJsonObject rejects new String()", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonObject(new String("hello"))).toBe(false));

    it("isJsonObject rejects new Number()", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonObject(new Number(42))).toBe(false));

    it("isJsonObject rejects new Boolean()", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonObject(new Boolean(true))).toBe(false));

    // isJsonValue 整体拒绝
    it("isJsonValue rejects new String()", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonValue(new String("hello"))).toBe(false));

    it("isJsonValue rejects new Number()", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonValue(new Number(42))).toBe(false));

    it("isJsonValue rejects new Boolean()", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonValue(new Boolean(true))).toBe(false));

    // 对象内部包含包装值同样拒绝
    it("isJsonObject rejects object with boxed String value", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonObject({ key: new String("x") })).toBe(false));

    it("isJsonArray rejects array with boxed Number element", () =>
        // eslint-disable-next-line no-new-wrappers
        expect(isJsonArray([new Number(1)])).toBe(false));

    // ── Object() 构造函数装箱（不使用 new 关键字）──────────────────────────
    // Object("x") 与 new String("x") 效果相同，都产生包装对象。
    // 这是 JS 中"隐式装箱"的另一种常见来源，同样应被拒绝。

    it("isJsonPrimitive rejects Object('x') [boxed string via Object()]", () =>
        expect(isJsonPrimitive(Object("x"))).toBe(false));

    it("isJsonPrimitive rejects Object(42) [boxed number via Object()]", () =>
        expect(isJsonPrimitive(Object(42))).toBe(false));

    it("isJsonPrimitive rejects Object(true) [boxed boolean via Object()]", () =>
        expect(isJsonPrimitive(Object(true))).toBe(false));

    it("isJsonObject rejects Object('x')", () =>
        expect(isJsonObject(Object("x"))).toBe(false));

    it("isJsonObject rejects Object(42)", () =>
        expect(isJsonObject(Object(42))).toBe(false));

    it("isJsonObject rejects Object(true)", () =>
        expect(isJsonObject(Object(true))).toBe(false));

    it("isJsonValue rejects Object('x')", () =>
        expect(isJsonValue(Object("x"))).toBe(false));

    it("isJsonValue rejects Object(42)", () =>
        expect(isJsonValue(Object(42))).toBe(false));

    it("isJsonValue rejects Object(true)", () =>
        expect(isJsonValue(Object(true))).toBe(false));

    // ── Object(null) / Object(undefined) 的特殊行为 ──────────────────────
    // Object(null) 和 Object(undefined) 不装箱——它们返回一个全新的空对象 {}，
    // 原型为 Object.prototype。这个空对象本身通过 isJsonObject（因为它是合法空对象），
    // 但要注意：这是一个隐式的"值丢失"——null/undefined 被静默转成了 {}。
    // 调用者不应依赖此行为；此处仅验证守卫的实际表现以供参考。

    it("Object(null) produces a plain empty object — accepted by isJsonObject (value is lost!)", () =>
        // Object(null) === {} (plain object, not null)
        expect(isJsonObject(Object(null))).toBe(true));

    it("Object(undefined) produces a plain empty object — accepted by isJsonObject (value is lost!)", () =>
        expect(isJsonObject(Object(undefined))).toBe(true));

    // ── String()/Number()/Boolean() 不加 new：返回原始值，应被接受 ─────────
    // 注意：String("x")（无 new）是类型转换，返回原始 string，不是包装对象。
    // 这与 new String("x") 完全不同，应被 isJsonPrimitive 正常接受。

    it("String('x') without new returns primitive string — accepted", () =>
        expect(isJsonPrimitive(String("x"))).toBe(true));

    it("Number(42) without new returns primitive number — accepted", () =>
        expect(isJsonPrimitive(Number(42))).toBe(true));

    it("Boolean(true) without new returns primitive boolean — accepted", () =>
        expect(isJsonPrimitive(Boolean(true))).toBe(true));

    it("Boolean(false) without new returns primitive boolean — accepted", () =>
        expect(isJsonPrimitive(Boolean(false))).toBe(true));
});

// ─── json_parse ───────────────────────────────────────────────────────────────

describe("json_parse", () => {
    it("parses a plain object", () =>
        expect(json_parse('{"a":1}')).toEqual({ a: 1 }));

    it("parses an array", () =>
        expect(json_parse("[1,2,3]")).toEqual([1, 2, 3]));

    it("parses a string primitive", () =>
        expect(json_parse('"hello"')).toBe("hello"));

    it("parses a number", () =>
        expect(json_parse("42")).toBe(42));

    it("parses true / false / null", () => {
        expect(json_parse("true")).toBe(true);
        expect(json_parse("false")).toBe(false);
        expect(json_parse("null")).toBe(null);
    });

    it("throws on invalid JSON", () =>
        expect(() => json_parse("{bad}")).toThrow());

    it("throws on empty string", () =>
        expect(() => json_parse("")).toThrow());

    it("throws on whitespace-only string", () =>
        expect(() => json_parse("   ")).toThrow());

    // ── U+2028 / U+2029 ────────────────────────────────────────────────────
    // JSON 规范允许这两个字符出现在字符串值内，但旧 JS 引擎将其视为行终止符。
    // json_parse 在解析前将其替换为 \uXXXX 转义，确保解析成功。

    it("parses JSON string containing U+2028 (LINE SEPARATOR)", () => {
        // 构造包含原始 U+2028 的 JSON 字符串（而非转义序列）
        const raw = '{"text":"hello\u2028world"}';
        const result = json_parse(raw);
        expect(result).toEqual({ text: "hello\u2028world" });
    });

    it("parses JSON string containing U+2029 (PARAGRAPH SEPARATOR)", () => {
        const raw = '{"text":"foo\u2029bar"}';
        const result = json_parse(raw);
        expect(result).toEqual({ text: "foo\u2029bar" });
    });

    it("parses JSON string containing both U+2028 and U+2029", () => {
        const raw = `{"a":"x\u2028y\u2029z"}`;
        const result = json_parse(raw);
        expect(result).toEqual({ a: "x\u2028y\u2029z" });
    });

    it("parses array with U+2028 in string elements", () => {
        const raw = `["line1\u2028line2","line3\u2029line4"]`;
        const result = json_parse(raw);
        expect(result).toEqual(["line1\u2028line2", "line3\u2029line4"]);
    });

    it("handles U+2028 appearing multiple times", () => {
        const raw = `{"v":"a\u2028b\u2028c"}`;
        expect(json_parse(raw)).toEqual({ v: "a\u2028b\u2028c" });
    });

    it("normal JSON without special chars still works", () => {
        expect(json_parse('{"n":1,"arr":[true,null]}')).toEqual({
            n: 1,
            arr: [true, null],
        });
    });
});
