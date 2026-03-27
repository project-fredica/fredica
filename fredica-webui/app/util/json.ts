// =============================================================================
// JSON 类型系统与运行时守卫
//
// 提供与 JSON 规范严格对齐的类型定义和运行时类型守卫函数。
// 所有守卫函数均为纯函数，无副作用，可安全用于任意输入。
//
// ── 设计原则 ──────────────────────────────────────────────────────────────────
//
// • 原型链检查：isJsonObject 通过 Object.getPrototypeOf 拒绝 Date、RegExp、Map
//   等内置对象，确保只有纯朴素对象（plain object）才被认定为 JsonObject。
// • 严格数值检查：isJsonPrimitive 拒绝 NaN 和 Infinity，因为它们无法被 JSON
//   序列化（JSON.stringify(NaN) === "null"，会静默丢失信息）。
// • 递归验证：所有守卫递归验证子节点，保证整棵树都符合 JSON 规范。
// • 循环引用检测：内部递归通过 seen: Set<object> 追踪已访问节点，遇到循环引用
//   时立即返回 false，避免无限递归导致栈溢出。
//
// ── 包装对象（Boxed Primitives）问题 ─────────────────────────────────────────
//
// JavaScript 有三种原始类型对应的包装类：String、Number、Boolean。
// 这三个类既可以当构造函数（产生对象），也可以当普通函数（做类型转换）——
// 这是 JS 里出了名的一个陷阱。
//
// 产生包装对象的方式（均应被守卫拒绝）：
//
//   方式 1：new 关键字 —— 最常见，显式装箱
//     new String("x")   // typeof === "object", proto === String.prototype
//     new Number(42)    // typeof === "object", proto === Number.prototype
//     new Boolean(true) // typeof === "object", proto === Boolean.prototype
//
//   方式 2：Object() 构造函数 —— 隐式装箱，容易被忽视
//     Object("x")       // 等价于 new String("x")
//     Object(42)        // 等价于 new Number(42)
//     Object(true)      // 等价于 new Boolean(true)
//
// 包装对象不能被任何守卫接受，原因：
//   1. typeof new String("x") === "object"，不满足 isJsonPrimitive 的原始类型检查。
//   2. 原型链为 String.prototype（非 Object.prototype/null），isJsonObject 拒绝。
//   3. 无法无损往返：JSON.stringify(new String("x")) 产生 '"x"'，
//      但 JSON.parse('"x"') 返回原始字符串而非包装对象，类型信息丢失。
//
// Object(null) / Object(undefined) 的特殊陷阱：
//   Object(null)      // 返回 {}（空的朴素对象！不是 null！）
//   Object(undefined) // 返回 {}（同上）
//   这两种调用不会装箱，而是静默地产生一个空的朴素对象，原型为 Object.prototype。
//   因此 isJsonObject(Object(null)) 返回 true——但这个"空对象"已经把 null 的
//   语义完全丢掉了。调用者不应该依赖此行为；这里记录是为了防止被意外踩坑。
//
// 不加 new 的类型转换函数（返回原始值，应被接受）：
//   String("x")   // typeof === "string"  ← 合法 JsonPrimitive ✓
//   Number(42)    // typeof === "number"  ← 合法 JsonPrimitive ✓
//   Boolean(true) // typeof === "boolean" ← 合法 JsonPrimitive ✓
//   String(x)（不加 new）只是类型转换，返回原始字符串，完全合法。
//   这与 new String(x) 天壤之别——同一个函数名，加不加 new 语义截然不同。
//
// ── U+2028 / U+2029 问题 ──────────────────────────────────────────────────────
//
// Unicode LINE SEPARATOR（U+2028）和 PARAGRAPH SEPARATOR（U+2029）在 JSON 规范中
// 是合法的字符串内容，但在 ES2019 之前的 JS 引擎中被视为行终止符，若出现在
// <script> 内嵌的 JSON 字面量中会引发语法错误。json_parse 在调用 JSON.parse
// 之前统一将这两个字符替换为对应的 \uXXXX 转义序列，从根源上规避此问题。
// =============================================================================

/** JSON 可序列化的原始类型：string、number（有限值）、boolean、null。 */
export type JsonPrimitive =
    | string
    | number
    | boolean
    | null;

/** JSON 中任意合法的值：原始类型、对象或数组，可递归嵌套。 */
export type JsonValue = JsonPrimitive | JsonObject | JsonArray;

/** JSON 对象：键为 string，值为任意 JsonValue 的纯朴素对象。 */
export type JsonObject = {
    [key: string]: JsonValue;
};

/** JSON 数组：元素均为 JsonValue 的数组。 */
export type JsonArray = Array<JsonValue>;

// ─── U+2028 / U+2029 净化 ─────────────────────────────────────────────────────

/**
 * 在交给 JSON.parse 之前，将 U+2028（LINE SEPARATOR）和 U+2029（PARAGRAPH SEPARATOR）
 * 替换为对应的 JSON 转义序列。
 *
 * 背景：JSON 规范允许这两个字符出现在字符串值内，但 ES2019 之前的 JS 引擎
 * 将它们视为行终止符，嵌入 `<script>` 标签的 JSON 内联文本会因此崩溃。
 * 现代引擎（ES2019+）已修复此问题，但保留此净化步骤可提升在旧环境的兼容性。
 */
function sanitizeForJsonParse(raw: string): string {
    // \u2028 → \\u2028, \u2029 → \\u2029
    return raw.replace(/\u2028/g, "\\u2028").replace(/\u2029/g, "\\u2029");
}

// ─── json_parse ───────────────────────────────────────────────────────────────

/**
 * 安全的 `JSON.parse` 封装。
 *
 * 与直接调用 `JSON.parse` 相比，额外提供：
 * 1. **U+2028/U+2029 净化**：解析前替换行分隔符转义，避免旧引擎语法错误。
 * 2. **抛异常而非返回 null**：解析失败时 throw，调用方按需 try/catch。
 * 3. **类型安全（无 typeCheck 时）**：返回值类型为 `JsonValue`，无需额外断言。
 * 4. **运行时类型收窄（有 typeCheck 时）**：typeCheck 不通过则 throw，返回 `R`。
 *    `R` 无 `extends JsonValue` 约束，可直接传入 domain 接口的类型守卫。
 *
 * ## 使用模式
 *
 * ```ts
 * // 无 typeCheck → 返回 JsonValue，无需强制转换
 * const v: JsonValue = json_parse(raw);
 *
 * // 有 typeCheck → 返回 R（domain 类型），无需强制转换
 * const cfg = json_parse(raw, isAppConfig); // AppConfig
 * ```
 *
 * @param raw 待解析的 JSON 字符串
 * @returns 解析成功时返回 `R`（有 typeCheck）或 `JsonValue`（无 typeCheck）
 */
export function json_parse<R>(raw: string, typeCheck: (data: unknown) => data is R): R;
export function json_parse<R = JsonValue>(raw: string): R;
export function json_parse<R>(
    raw: string,
    typeCheck?: (data: unknown) => data is R,
): R {
    const data = JSON.parse(sanitizeForJsonParse(raw));
    if (!isJsonValue(data)) {
        throw new Error(`why JSON.parse not isJsonValue ? raw is ${raw}`);
    }
    if (typeof typeCheck === "undefined") {
        return data as R;
    }
    if (!typeCheck(data)) {
        throw new Error(
            `typeCheck failed after json_parse success , data is ${data} , raw is ${raw}`,
        );
    }
    return data as R;
}

// ─── 类型守卫 ─────────────────────────────────────────────────────────────────

/**
 * 判断值是否为合法的 JSON 原始类型。
 *
 * **拒绝包装对象**：`new String("x")`、`new Number(1)`、`new Boolean(true)` 的
 * `typeof` 为 `"object"`，而非对应的原始类型。它们不能安全地往返于 JSON
 * 序列化——`JSON.stringify(new String("x"))` 会产生 `'"x"'`，但 `isJsonPrimitive`
 * 需要保证 `isJsonValue(JSON.parse(JSON.stringify(v))) === true`，包装对象做不到。
 * 因此包装对象在此处返回 false，也无法通过 `isJsonObject`（原型链不符），
 * 最终被 `isJsonValue` 整体拒绝。
 *
 * **拒绝 NaN / Infinity**：它们是 JS number 但不是合法 JSON，JSON.stringify 会
 * 将其序列化为 `null`，造成信息丢失。
 */
export function isJsonPrimitive(obj: unknown): obj is JsonPrimitive {
    if (typeof obj === "boolean" || typeof obj === "string" || obj === null) {
        return true;
    }
    if (typeof obj === "number" && !isNaN(obj) && isFinite(obj)) {
        return true;
    }
    return false;
}

export function isJsonValue(obj: unknown): obj is JsonValue {
    return _isJsonValue(obj, new Set());
}

/**
 * 判断值是否为合法的 JSON 对象（plain object）。
 *
 * 通过原型链检查确保只接受纯朴素对象，拒绝 Date、RegExp、Map、Set、Error
 * 等内置对象——它们虽然 `typeof` 为 `"object"`，但原型不是 `Object.prototype`
 * 或 `null`，不能安全地序列化为 JSON 对象。
 *
 * 同时递归验证所有值均为合法 JsonValue，并通过 seen Set 检测循环引用。
 */
export function isJsonObject(obj: unknown): obj is JsonObject {
    return _isJsonObject(obj, new Set());
}

export function isJsonArray(obj: unknown): obj is JsonArray {
    return _isJsonArray(obj, new Set());
}

// ─── 内部递归实现（携带 seen Set 防止循环引用）────────────────────────────────

function _isJsonValue(obj: unknown, seen: Set<object>): boolean {
    return isJsonPrimitive(obj) || _isJsonObject(obj, seen) ||
        _isJsonArray(obj, seen);
}

function _isJsonObject(obj: unknown, seen: Set<object>): boolean {
    if (typeof obj !== "object" || obj === null) return false;
    if (Array.isArray(obj)) return false;

    // 原型链检查：只允许 Object.prototype 或 null 原型（Object.create(null)）
    const proto = Object.getPrototypeOf(obj);
    if (proto !== Object.prototype && proto !== null) return false;

    // 循环引用检测
    if (seen.has(obj)) return false;
    seen.add(obj);

    for (const [key, value] of Object.entries(obj)) {
        if (typeof key !== "string") {
            seen.delete(obj);
            return false;
        }
        if (!_isJsonValue(value, seen)) {
            seen.delete(obj);
            return false;
        }
    }

    seen.delete(obj);
    return true;
}

function _isJsonArray(obj: unknown, seen: Set<object>): boolean {
    if (typeof obj !== "object" || obj === null) return false;
    if (!Array.isArray(obj)) return false;

    // 循环引用检测
    if (seen.has(obj)) return false;
    seen.add(obj);

    for (const item of obj) {
        if (!_isJsonValue(item, seen)) {
            seen.delete(obj);
            return false;
        }
    }

    seen.delete(obj);
    return true;
}
