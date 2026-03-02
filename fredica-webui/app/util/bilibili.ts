/**
 * B 站相关的纯工具函数集合。
 * 这些函数不依赖任何 React 状态或 Context，可在组件外部独立调用。
 */

/**
 * 将秒数格式化为人类可读的时长字符串。
 *
 * - 不足 1 小时：输出 `M:SS`（分钟不补零）
 * - 1 小时及以上：输出 `H:MM:SS`
 *
 * @example
 * formatDuration(125)    // → "2:05"
 * formatDuration(3665)   // → "1:01:05"
 * formatDuration(0)      // → "0:00"
 */
export function formatDuration(seconds: number): string {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    if (h > 0) {
        return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    }
    return `${m}:${String(s).padStart(2, '0')}`;
}

/**
 * 将整数格式化为带"万"后缀的字符串，适用于 B 站播放量、收藏数等计数展示。
 *
 * - 小于 10000：直接返回原数字字符串
 * - 大于等于 10000：除以 10000 并保留 1 位小数，加"万"后缀
 *
 * @example
 * formatCount(500)    // → "500"
 * formatCount(15000)  // → "1.5万"
 * formatCount(100000) // → "10.0万"
 */
export function formatCount(n: number): string {
    if (n >= 10000) return `${(n / 10000).toFixed(1)}万`;
    return String(n);
}

/**
 * 将 Unix 时间戳（秒）格式化为 `YYYY-MM-DD` 日期字符串，
 * 用于展示收藏时间等日期信息。
 *
 * @param ts Unix 时间戳（秒），非毫秒
 * @example
 * formatFavDate(1704067200)  // → "2024-01-01"
 */
export function formatFavDate(ts: number): string {
    const d = new Date(ts * 1000);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * 生成分页 UI 所需的页码数组，实现"两端固定 + 当前页附近滑动窗口"算法。
 *
 * 规则：
 * - 始终显示第 1、2 页和最后 2 页
 * - 在当前页（`loadedPage`）前后各显示 2 页（共 5 页的滑动窗口）
 * - 当相邻显示的页码之间存在间隙时，插入 `0` 作为省略号占位符
 *
 * @param totalPages 总页数
 * @param loadedPage 当前高亮的页码（1-indexed）
 * @returns 页码数组，其中 `0` 表示省略号位置
 *
 * @example
 * buildPageWindows(10, 5)
 * // → [1, 2, 0, 3, 4, 5, 6, 7, 0, 9, 10]
 *
 * buildPageWindows(5, 3)
 * // → [1, 2, 3, 4, 5]  （页数少，无省略号）
 */
export function buildPageWindows(totalPages: number, loadedPage: number): number[] {
    const show = new Set<number>();
    show.add(1);
    show.add(2);
    show.add(totalPages - 1);
    show.add(totalPages);
    for (let d = -2; d <= 2; d++) {
        const p = loadedPage + d;
        if (p >= 1 && p <= totalPages) show.add(p);
    }
    const sorted = Array.from(show).sort((a, b) => a - b);
    const result: number[] = [];
    let prev = 0;
    for (const p of sorted) {
        if (prev > 0 && p > prev + 1) result.push(0);
        result.push(p);
        prev = p;
    }
    return result;
}
