import { describe, expect, it } from "vitest";
import { convertToSrt, secondsToSrtTime, type SrtSegment } from "~/util/subtitleExport";

describe("secondsToSrtTime", () => {
    it("E1 - 0 秒转为 00:00:00,000", () => {
        expect(secondsToSrtTime(0)).toBe("00:00:00,000");
    });

    it("E2 - 超过 1 小时（3661.5 秒）", () => {
        expect(secondsToSrtTime(3661.5)).toBe("01:01:01,500");
    });

    it("E3 - 毫秒精度：1.001 秒 → ,001", () => {
        expect(secondsToSrtTime(1.001)).toBe("00:00:01,001");
    });

    it("E4 - 负数秒数钳制到 0", () => {
        expect(secondsToSrtTime(-1)).toBe("00:00:00,000");
    });
});

describe("convertToSrt", () => {
    it("E5 - 空数组返回只含换行的字符串", () => {
        expect(convertToSrt([])).toBe("\n");
    });

    it("E6 - 单条字幕格式正确", () => {
        const segs: SrtSegment[] = [{ from: 1.23, to: 4.56, content: "Hello" }];
        expect(convertToSrt(segs)).toBe("1\n00:00:01,230 --> 00:00:04,560\nHello\n");
    });

    it("E7 - 多条字幕序号从 1 递增", () => {
        const segs: SrtSegment[] = [
            { from: 0, to: 1, content: "A" },
            { from: 2, to: 3, content: "B" },
            { from: 4, to: 5, content: "C" },
        ];
        expect(convertToSrt(segs)).toContain("1\n00:00:00,000 --> 00:00:01,000\nA\n\n2\n00:00:02,000 --> 00:00:03,000\nB\n\n3\n00:00:04,000 --> 00:00:05,000\nC\n");
    });

    it("E8 - content 中包含换行时保留原文本", () => {
        const segs: SrtSegment[] = [{ from: 0, to: 1, content: "Line1\nLine2" }];
        expect(convertToSrt(segs)).toBe("1\n00:00:00,000 --> 00:00:01,000\nLine1\nLine2\n");
    });
});
