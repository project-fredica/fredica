/**
 * asrConfig.ts
 *
 * Whisper ASR 配置相关常量与工具函数，供 subtitle.tsx 及其他页面复用。
 */

// ─── Types ────────────────────────────────────────────────────────────────────

export interface WhisperModelCompat {
    name: string;
    vram_required: number;  // GB（静态预估或 torch 实测）
    ok: boolean;            // false = likely OOM
    error?: string;
    isEnOnly?: boolean;     // 仅支持英语（.en 后缀模型）
}

export interface WhisperCompatParsed {
    recommended_model?: string;
    vram_gb?: number;
    models: WhisperModelCompat[];
    torch_missing?: boolean;
}

// ─── Constants ────────────────────────────────────────────────────────────────

export const WHISPER_LANGUAGES = [
    { value: "zh",   label: "中文（zh）" },
    { value: "en",   label: "English（en）" },
    { value: "ja",   label: "日本語（ja）" },
    { value: "ko",   label: "한국어（ko）" },
    { value: "fr",   label: "Français（fr）" },
    { value: "de",   label: "Deutsch（de）" },
    { value: "es",   label: "Español（es）" },
    { value: "ru",   label: "Русский（ru）" },
    { value: "pt",   label: "Português（pt）" },
    { value: "ar",   label: "العربية（ar）" },
    { value: "it",   label: "Italiano（it）" },
    { value: "nl",   label: "Nederlands（nl）" },
    { value: "pl",   label: "Polski（pl）" },
    { value: "tr",   label: "Türkçe（tr）" },
    { value: "vi",   label: "Tiếng Việt（vi）" },
    { value: "th",   label: "ภาษาไทย（th）" },
    { value: "id",   label: "Bahasa Indonesia（id）" },
    { value: "auto", label: "自动检测" },
];

/** 完整模型列表，按显存从小到大排序（排除别名：large=large-v3, large-v3-turbo=turbo） */
export const ALL_WHISPER_MODELS = [
    "tiny.en", "tiny",
    "base.en", "base",
    "distil-small.en",
    "small.en", "small",
    "distil-medium.en",
    "distil-large-v2", "distil-large-v3", "distil-large-v3.5",
    "turbo",
    "medium.en", "medium",
    "large-v1", "large-v2", "large-v3",
];

/** 静态显存预估（GB），基于 faster-whisper 官方 benchmark 及社区数据 */
export const WHISPER_MODEL_VRAM_HINT: Record<string, number> = {
    "tiny.en":           1,
    "tiny":              1,
    "base.en":           1,
    "base":              1,
    "distil-small.en":   1,
    "small.en":          2,
    "small":             2,
    "distil-medium.en":  2,
    "distil-large-v2":   3,
    "distil-large-v3":   3,
    "distil-large-v3.5": 3,
    "turbo":             3,
    "medium.en":         5,
    "medium":            5,
    "large-v1":          5,
    "large-v2":          5,
    "large-v3":          5,
};

// ─── Functions ────────────────────────────────────────────────────────────────

/** 判断语言代码是否为英语 */
export function isEnglishLang(lang: string): boolean {
    return lang === "en";
}

/**
 * 解析 compat_json 字段。
 * 实际结构：{"type":"done","result":{"local_models":[...],"model_support":{"large-v3":{"supported":bool,"vram_mb":int,"error":str}}}}
 * 转换为前端统一格式。
 * torch_missing 时 recommended_model 为 undefined（不自动推荐）。
 */
export function parseCompatJson(compatJson: string): WhisperCompatParsed {
    try {
        const raw = JSON.parse(compatJson);
        const result = raw?.result ?? raw;
        const modelSupport: Record<string, { supported: boolean; vram_mb: number; error?: string }> =
            result?.model_support ?? {};

        // 检测是否所有模型都因 torch 缺失而失败
        const allErrors = Object.values(modelSupport).map(ms => ms?.error ?? "");
        const torchMissing = allErrors.length > 0 && allErrors.every(e => e.includes("No module named 'torch'"));

        const models: WhisperModelCompat[] = ALL_WHISPER_MODELS.map(name => {
            const ms = modelSupport[name];
            const isEnOnly = name.endsWith(".en") || name.startsWith("distil-");
            return {
                name,
                isEnOnly,
                ok: torchMissing ? true : (ms?.supported ?? true),
                vram_required: torchMissing
                    ? (WHISPER_MODEL_VRAM_HINT[name] ?? 0)
                    : ms ? Math.round((ms.vram_mb ?? 0) / 1024 * 10) / 10 : 0,
                error: ms?.error,
            };
        });

        // torch 缺失时不推荐任何模型（让用户自行选择）；否则取最大的可用模型
        const recommended = torchMissing ? undefined : models.filter(m => m.ok).at(-1)?.name;

        return { models, recommended_model: recommended, torch_missing: torchMissing };
    } catch {
        return {
            models: ALL_WHISPER_MODELS.map(name => ({
                name,
                ok: true,
                vram_required: WHISPER_MODEL_VRAM_HINT[name] ?? 0,
                isEnOnly: name.endsWith(".en") || name.startsWith("distil-"),
            })),
        };
    }
}

/**
 * 根据语言和 compat 信息选择默认模型。
 * - torch_missing：非英语 → large-v2；英语 → distil-large-v2
 * - torch 正常：取最大的可用且语言匹配的模型，兜底同上
 */
export function pickDefaultModel(lang: string, compat: WhisperCompatParsed): string | null {
    const isEn = isEnglishLang(lang);
    if (compat.torch_missing) {
        return isEn ? "distil-large-v2" : "large-v2";
    }
    if (isEn) {
        return compat.models.filter(m => m.ok).at(-1)?.name ?? "distil-large-v2";
    }
    return compat.models.filter(m => m.ok && !m.isEnOnly).at(-1)?.name ?? "large-v2";
}
