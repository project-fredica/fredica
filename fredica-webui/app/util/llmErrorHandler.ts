import { toast } from "react-toastify";
import type { LlmProviderError } from "./llm";

export function handleLlmProviderError(error: LlmProviderError) {
    const errorTypeMessages: Record<string, string> = {
        AUTH_ERROR: "API Key 无效，请检查模型配置",
        RATE_LIMIT: "请求过于频繁，请稍后重试",
        SERVER_ERROR: "LLM 服务异常（5xx）",
        CONTENT_FILTER: "内容被安全过滤，请修改输入",
        MODEL_NOT_FOUND: "模型不存在，请检查模型名称",
    };

    const message = errorTypeMessages[error.errorType] ?? error.message;
    toast.error(message);
}
