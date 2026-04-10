/**
 * asrConfig.ts
 *
 * Whisper ASR 配置相关常量，供 subtitle.tsx 及其他页面复用。
 */

// ─── Constants ────────────────────────────────────────────────────────────────

export const WHISPER_LANGUAGES = [
    { value: "zh", label: "中文（zh）" },
    { value: "en", label: "English（en）" },
    { value: "ja", label: "日本語（ja）" },
    { value: "ko", label: "한국어（ko）" },
    { value: "fr", label: "Français（fr）" },
    { value: "de", label: "Deutsch（de）" },
    { value: "es", label: "Español（es）" },
    { value: "ru", label: "Русский（ru）" },
    { value: "pt", label: "Português（pt）" },
    { value: "ar", label: "العربية（ar）" },
    { value: "it", label: "Italiano（it）" },
    { value: "nl", label: "Nederlands（nl）" },
    { value: "pl", label: "Polski（pl）" },
    { value: "tr", label: "Türkçe（tr）" },
    { value: "vi", label: "Tiếng Việt（vi）" },
    { value: "th", label: "ภาษาไทย（th）" },
    { value: "id", label: "Bahasa Indonesia（id）" },
    { value: "auto", label: "自动检测" },
];

/** 完整模型列表，按显存从小到大排序（排除别名：large=large-v3, large-v3-turbo=turbo） */
export const ALL_WHISPER_MODELS = [
    "tiny.en",
    "tiny",
    "base.en",
    "base",
    "distil-small.en",
    "small.en",
    "small",
    "distil-medium.en",
    "distil-large-v2",
    "distil-large-v3",
    "distil-large-v3.5",
    "turbo",
    "medium.en",
    "medium",
    "large-v1",
    "large-v2",
    "large-v3",
];

/** 静态显存预估（GB），基于 faster-whisper 官方 benchmark 及社区数据 */
export const WHISPER_MODEL_VRAM_HINT: Record<string, number> = {
    "tiny.en": 1,
    "tiny": 1,
    "base.en": 1,
    "base": 1,
    "distil-small.en": 1,
    "small.en": 2,
    "small": 2,
    "distil-medium.en": 2,
    "distil-large-v2": 3,
    "distil-large-v3": 3,
    "distil-large-v3.5": 3,
    "turbo": 3,
    "medium.en": 5,
    "medium": 5,
    "large-v1": 5,
    "large-v2": 5,
    "large-v3": 5,
};

/** 判断语言代码是否为英语 */
export function isEnglishLang(lang: string): boolean {
    return lang === "en";
}

/**
 * 根据语言选择默认模型（纯静态，不依赖 compat 信息）。
 * - 英语 → distil-large-v2（性价比最高的英语专用模型）
 * - 非英语 → medium（多语言模型中显存需求适中）
 */
export function pickDefaultModel(lang: string): string {
    return isEnglishLang(lang) ? "distil-large-v2" : "medium";
}
