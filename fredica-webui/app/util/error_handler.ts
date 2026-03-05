/**
 * 统一前端错误上报工具。
 *
 * 同时向控制台输出带上下文的详细日志，并通过 Toast 向用户发出通知，
 * 避免在业务代码中分散地重复写 `console.error` + `toast.error` 的双重调用。
 *
 * ## 使用场景
 *
 * ### 命令式操作返回 HTTP 错误（推荐配合 `reportHttpError` 简写）
 * ```tsx
 * const { resp } = await apiFetch('/api/v1/SomeRoute', { method: 'POST', ... });
 * if (!resp.ok) { reportHttpError('提交失败', resp); return; }
 * ```
 *
 * ### catch 块中捕获到网络 / 运行时异常
 * ```tsx
 * try {
 *   ...
 * } catch (err) {
 *   print_error({ reason: '网络错误，请检查服务器连接', err });
 * }
 * ```
 *
 * ### 携带调试上下文
 * ```tsx
 * print_error({ reason: '解析响应失败', err, variables: { bvid, page } });
 * ```
 */

/**
 * 统一错误上报：同时向控制台写入详细日志并弹出 Toast 通知。
 *
 * @param reason    - 面向用户的简短错误描述，显示在 Toast 中
 * @param err       - 捕获到的原始异常（可省略）；用于丰富控制台日志
 * @param variables - 调试用的上下文键值对（仅输出到控制台，不展示给用户）
 */
export function print_error({
    reason,
    err = undefined,
    variables = null,
}: {
    reason: string;
    err?: unknown;
    variables?: Record<string, unknown> | null;
}) {
    if (err !== null && typeof err !== "undefined") {
        console.error(`[error] ${reason} : ${err}`, { variables, err });
    } else {
        console.error(`[error] ${reason}`, { variables });
    }
    import("react-toastify")
        .then((m) =>
            m.toast.error(`[error] ${reason}`, {
                position: "top-right",
                autoClose: 3000,
                closeOnClick: true,
                pauseOnHover: true,
                hideProgressBar: false,
            })
        )
        .catch((err) => {
            console.error("import react-toastify failed !", err);
        });
}

/**
 * `resp.ok` 为 false 时的快捷上报，自动将 HTTP 状态码附加到错误描述中。
 *
 * ```tsx
 * const { resp } = await apiFetch('/api/v1/SomeRoute', ...);
 * if (!resp.ok) { reportHttpError('提交失败', resp); return; }
 * ```
 */
export function reportHttpError(reason: string, resp: Response) {
    print_error({ reason: `${reason} (HTTP ${resp.status}) ` });
}
