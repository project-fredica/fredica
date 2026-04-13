import type { SrtSegment } from "./subtitleExport";

/** SRT 时间戳 HH:MM:SS,mmm → 秒数 */
export function srtTimeToSeconds(timeStr: string): number {
    const m = timeStr.trim().match(/^(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})$/);
    if (!m) return NaN;
    return Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) + Number(m[4]) / 1000;
}

/**
 * 解析 SRT 文本为 segments。
 *
 * - 自动提取 ```srt``` 或 ``` ``` 代码块内容（LLM 常包裹 markdown）
 * - 跳过格式错误的 block（容错）
 */
export function parseSrt(srtText: string): SrtSegment[] {
    let text = srtText;

    // 尝试提取 markdown 代码块
    const fenceMatch = text.match(/```(?:srt)?\s*\n([\s\S]*?)```/);
    if (fenceMatch) {
        text = fenceMatch[1];
    }

    const blocks = text.trim().split(/\n\s*\n/);
    const segments: SrtSegment[] = [];

    for (const block of blocks) {
        const lines = block.trim().split("\n").map(l => l.trim()).filter(Boolean);
        if (lines.length < 2) continue;

        // 找时间戳行（含 -->）
        const tsIdx = lines.findIndex(l => l.includes("-->"));
        if (tsIdx < 0) continue;

        const tsParts = lines[tsIdx].split("-->");
        if (tsParts.length !== 2) continue;

        const from = srtTimeToSeconds(tsParts[0]);
        const to = srtTimeToSeconds(tsParts[1]);
        if (isNaN(from) || isNaN(to)) continue;

        // 时间戳行之后的所有行为 content
        const content = lines.slice(tsIdx + 1).join("\n");
        if (!content) continue;

        segments.push({ from, to, content });
    }

    return segments;
}
