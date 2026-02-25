import { Loader } from "lucide-react";

const TASK_TYPE_LABEL: Record<string, string> = {
    download:             '下载',
    split_120s:           '切片',
    vocal_separation:     '人声分离',
    speaker_diarization:  '声纹识别',
    tongyi_tingwu:        '通义听悟',
    faster_whisper:       'Whisper',
};

const STATUS_STYLE: Record<string, { className: string; dot: string }> = {
    done:    { className: 'bg-green-50 text-green-700',  dot: 'bg-green-500' },
    running: { className: 'bg-blue-50 text-blue-700',   dot: 'bg-blue-500' },
    failed:  { className: 'bg-red-50 text-red-600',     dot: 'bg-red-500' },
    queued:  { className: 'bg-gray-100 text-gray-500',  dot: 'bg-gray-400' },
};

interface MaterialTaskBadgeProps {
    taskType: string;
    status: string;
}

export function MaterialTaskBadge({ taskType, status }: MaterialTaskBadgeProps) {
    const label = TASK_TYPE_LABEL[taskType] ?? taskType;
    const style = STATUS_STYLE[status] ?? STATUS_STYLE.queued;

    return (
        <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium ${style.className}`}>
            {status === 'running' ? (
                <Loader className="w-2.5 h-2.5 animate-spin flex-shrink-0" />
            ) : (
                <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${style.dot}`} />
            )}
            {label}
        </span>
    );
}
