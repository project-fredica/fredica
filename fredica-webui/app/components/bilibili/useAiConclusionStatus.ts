import { useEffect, useRef, useState } from "react";
import { useAppFetch } from "~/util/app_fetch";

/**
 * 视口进入时自动查询 B 站 AI 总结缓存（is_update=false）。
 * 仅当后端返回 code=0 且 model_result 非 null 时，hasSuccess=true。
 *
 * 防抖：进入视口后 600ms 才发请求。
 * 冷却：同一 bvid+pageIndex 30s 内不重复请求。
 */

// 模块级缓存 Map，跨组件实例共享：存储最近一次查询时间戳及成功结果
const cacheMap = new Map<string, { ts: number; success: boolean }>();
const COOLDOWN_MS = 30_000;
const DEBOUNCE_MS = 600;

export function useAiConclusionStatus(bvid: string, pageIndex: number) {
    const { apiFetch } = useAppFetch();
    const cacheKey = `${bvid}__${pageIndex}`;
    // 若缓存中已知有成功结果，直接初始化为 true（导航返回后立即恢复显示）
    const [hasSuccess, setHasSuccess] = useState(() => cacheMap.get(cacheKey)?.success ?? false);
    const ref = useRef<HTMLElement | null>(null);
    const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const queriedRef = useRef(false);

    const query = () => {
        const now = Date.now();
        const last = cacheMap.get(cacheKey)?.ts ?? 0;
        if (now - last < COOLDOWN_MS) return;
        cacheMap.set(cacheKey, { ts: now, success: false });

        apiFetch("/api/v1/BilibiliVideoAiConclusionRoute", {
            method: "POST",
            body: JSON.stringify({
                bvid,
                page_index: pageIndex,
                is_update: false,
            }),
        }, {
            // 后端有令牌桶限速，这里无所谓了
            timeout: 3 * 60 * 1000,
            silent: true,
        }).then(({ data }) => {
            const d = data as { code: number; model_result: unknown };
            const success = d?.code === 0 && d?.model_result != null;
            cacheMap.set(cacheKey, { ts: now, success });
            if (success) setHasSuccess(true);
        }).catch(() => {/* 静默失败 */});
    };

    const setRef = (el: HTMLElement | null) => {
        if (ref.current === el) return;
        ref.current = el;
    };

    useEffect(() => {
        if (queriedRef.current) return;
        const el = ref.current;
        if (!el) return;

        const observer = new IntersectionObserver(([entry]) => {
            if (!entry.isIntersecting) return;
            observer.disconnect();
            queriedRef.current = true;
            timerRef.current = setTimeout(query, DEBOUNCE_MS);
        }, { threshold: 0.1 });

        observer.observe(el);
        return () => {
            observer.disconnect();
            if (timerRef.current) clearTimeout(timerRef.current);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [bvid, pageIndex]);

    return { hasSuccess, setRef };
}
