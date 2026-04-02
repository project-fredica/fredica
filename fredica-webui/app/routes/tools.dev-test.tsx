import { FlaskConical, ArrowLeft } from "lucide-react";
import { Link } from "react-router";
import { toast } from "react-toastify";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";

const TOAST_CASES = [
    {
        label: "success",
        desc: "操作成功",
        className: "bg-green-50 border-green-200 text-green-800 hover:bg-green-100",
        action: () => toast.success("操作成功！这是一条成功提示。"),
    },
    {
        label: "error",
        desc: "操作失败",
        className: "bg-red-50 border-red-200 text-red-800 hover:bg-red-100",
        action: () => toast.error("操作失败！这是一条错误提示。"),
    },
    {
        label: "warning",
        desc: "注意",
        className: "bg-yellow-50 border-yellow-200 text-yellow-800 hover:bg-yellow-100",
        action: () => toast.warning("请注意！这是一条警告提示。"),
    },
    {
        label: "info",
        desc: "信息",
        className: "bg-blue-50 border-blue-200 text-blue-800 hover:bg-blue-100",
        action: () => toast.info("这是一条信息提示。"),
    },
    {
        label: "default",
        desc: "普通",
        className: "bg-gray-50 border-gray-200 text-gray-800 hover:bg-gray-100",
        action: () => toast("这是一条普通提示。"),
    },
    {
        label: "promise",
        desc: "Promise（加载 → 成功）",
        className: "bg-purple-50 border-purple-200 text-purple-800 hover:bg-purple-100",
        action: () =>
            toast.promise(
                new Promise<string>((resolve) => setTimeout(() => resolve("完成"), 2000)),
                { pending: "处理中…", success: "完成！", error: "失败" }
            ),
    },
    {
        label: "long",
        desc: "长文本",
        className: "bg-gray-50 border-gray-200 text-gray-800 hover:bg-gray-100",
        action: () =>
            toast.info(
                "这是一条较长的提示文本，用于测试 toast 的换行与布局是否正常显示，不会出现样式破坏的情况。"
            ),
    },
    {
        label: "no-auto-close",
        desc: "不自动关闭",
        className: "bg-orange-50 border-orange-200 text-orange-800 hover:bg-orange-100",
        action: () => toast("需要手动关闭，autoClose: false", { autoClose: false }),
    },
];

export default function DevTestPage() {
    return (
        <SidebarLayout>
            <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-6">
                <div className="flex items-center gap-3">
                    <Link
                        to="/tools"
                        className="text-gray-400 hover:text-gray-600 transition-colors"
                    >
                        <ArrowLeft className="w-4 h-4" />
                    </Link>
                    <div>
                        <h1 className="text-xl font-semibold text-gray-900 flex items-center gap-2">
                            <FlaskConical className="w-5 h-5 text-purple-500" />
                            开发者测试
                        </h1>
                        <p className="text-sm text-gray-500 mt-0.5">UI 组件与行为的快速验证</p>
                    </div>
                </div>

                <section className="space-y-3">
                    <h2 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">
                        Toast 通知
                    </h2>
                    <div className="grid grid-cols-2 gap-2">
                        {TOAST_CASES.map(({ label, desc, className, action }) => (
                            <button
                                key={label}
                                onClick={action}
                                className={`rounded-lg border px-4 py-3 text-left transition-colors cursor-pointer ${className}`}
                            >
                                <p className="text-xs font-mono font-semibold">{label}</p>
                                <p className="text-xs mt-0.5 opacity-70">{desc}</p>
                            </button>
                        ))}
                    </div>
                    <button
                        onClick={() => toast.dismiss()}
                        className="w-full rounded-lg border border-gray-200 bg-white px-4 py-2 text-sm text-gray-500 hover:bg-gray-50 transition-colors cursor-pointer"
                    >
                        清除所有 toast
                    </button>
                </section>
            </div>
        </SidebarLayout>
    );
}
