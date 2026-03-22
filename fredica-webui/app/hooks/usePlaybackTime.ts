/**
 * usePlaybackTime.ts
 *
 * 订阅 BroadcastChannel，监听指定素材的播放进度。
 * 收到 seek-passive（每 100ms）或 playing（开始播放时）消息时更新 currentTime。
 */

import { useEffect, useRef, useState } from "react";
import type { VideoPlayerMessage } from "~/util/videoPlayerChannel";

export function usePlaybackTime(materialId: string | null | undefined): number {
    const [currentTime, setCurrentTime] = useState(0);
    const prevRef = useRef(-1);

    useEffect(() => {
        if (!materialId || typeof BroadcastChannel === "undefined") return;

        const ch = new BroadcastChannel(`fredica-video-player-${materialId}`);

        ch.onmessage = (event: MessageEvent<VideoPlayerMessage>) => {
            const msg = event.data;
            if (!msg || typeof msg !== "object") return;

            let t: number | undefined;
            if (msg.type === "seek-passive") t = msg.seconds;
            else if (msg.type === "playing") t = msg.currentTime;

            if (t !== undefined && t !== prevRef.current) {
                prevRef.current = t;
                setCurrentTime(t);
            }
        };

        return () => { ch.close(); };
    }, [materialId]);

    return currentTime;
}
