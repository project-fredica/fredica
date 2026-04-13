import type { ReactNode } from "react";

export function PromptPaneShell({
    title,
    actions,
    children,
}: {
    title: string;
    actions?: ReactNode;
    children: ReactNode;
}) {
    return (
        <div className="h-full flex flex-col bg-white rounded-xl border border-gray-200 overflow-hidden">
            <div className="flex items-center justify-between gap-3 px-4 py-3 border-b border-gray-100 bg-gray-50/80">
                <h3 className="text-sm font-semibold text-gray-800">{title}</h3>
                {actions ? <div className="flex items-center gap-2">{actions}</div> : null}
            </div>
            <div className="flex-1 min-h-0 flex flex-col">{children}</div>
        </div>
    );
}
