export interface SrtSegment {
    from: number;
    to: number;
    content: string;
}

/** 秒数 → SRT 时间戳 HH:MM:SS,mmm */
export function secondsToSrtTime(sec: number): string {
    const ms = Math.max(0, Math.round(sec * 1000));
    const h = Math.floor(ms / 3_600_000);
    const m = Math.floor((ms % 3_600_000) / 60_000);
    const s = Math.floor((ms % 60_000) / 1_000);
    const msPart = ms % 1_000;
    return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")},${String(msPart).padStart(3, "0")}`;
}

/** 分段字幕 → SRT 字符串 */
export function convertToSrt(segments: SrtSegment[]): string {
    if (segments.length === 0) return "\n";
    return `${segments.map((seg, index) => {
        return [
            String(index + 1),
            `${secondsToSrtTime(seg.from)} --> ${secondsToSrtTime(seg.to)}`,
            seg.content,
        ].join("\n");
    }).join("\n\n")}\n`;
}

/** 触发浏览器下载 SRT 文件 */
export function downloadSrt(content: string, filename: string): void {
    const blob = new Blob([content], { type: "application/x-subrip;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
}
