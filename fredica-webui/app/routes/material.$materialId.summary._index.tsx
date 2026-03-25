import { useState } from "react";
import {
    BrainCircuit, CheckCircle, Loader2, Tags, Lightbulb,
    BookOpen, Languages, ChevronDown, Sparkles,
} from "lucide-react";
import { useWorkspaceContext } from "~/routes/material.$materialId";

const MOCK_SUMMARY = `本视频深入讲解了 Transformer 架构中注意力机制的工作原理，从 Query/Key/Value 的数学推导出发，
逐步演示了多头注意力如何让模型同时关注序列中不同位置的信息。
作者还对比了 RNN 的串行依赖缺陷，说明了 Transformer 并行化优势的根本原因。`;

const MOCK_KEYWORDS = ["Transformer", "注意力机制", "Multi-Head Attention", "Self-Attention", "Query/Key/Value", "BERT", "GPT", "并行化"];

const MOCK_CONCEPTS = [
    { id: "1", name: "Scaled Dot-Product Attention", desc: "通过 Q·K^T / √d_k 计算注意力分数，避免梯度消失" },
    { id: "2", name: "Multi-Head Attention", desc: "将 d_model 维度切分为 h 个头，并行学习不同子空间的注意力" },
    { id: "3", name: "Positional Encoding", desc: "用正弦 / 余弦函数注入位置信息，弥补自注意力的位置无感性" },
];

const MOCK_ANALYSIS_STATUS: "idle" | "in_progress" | "completed" = "completed";

function SectionCard({ title, icon, children, defaultOpen = true }: {
    title: string;
    icon: React.ReactNode;
    children: React.ReactNode;
    defaultOpen?: boolean;
}) {
    const [open, setOpen] = useState(defaultOpen);

    return (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <button
                onClick={() => setOpen(v => !v)}
                className="w-full flex items-center gap-2 px-4 py-3 border-b border-gray-100 hover:bg-gray-50 transition-colors"
            >
                <span className="text-gray-500">{icon}</span>
                <span className="text-sm font-semibold text-gray-700 flex-1 text-left">{title}</span>
                <ChevronDown className={`w-3.5 h-3.5 text-gray-400 transition-transform ${open ? "" : "-rotate-90"}`} />
            </button>
            {open && <div className="p-4">{children}</div>}
        </div>
    );
}

function StartAnalysis() {
    const [step, setStep] = useState<"idle" | "model_select">("idle");
    const [selectedModel, setSelectedModel] = useState("claude-3-5");
    const models = [
        { id: "gpt-4o", label: "GPT-4o", desc: "速度快，中英双语" },
        { id: "claude-3-5", label: "Claude 3.5 Sonnet", desc: "推理强，长文档", recommended: true },
        { id: "deepseek-r1", label: "DeepSeek R1", desc: "低成本，中文优秀" },
    ];

    if (step === "idle") {
        return (
            <div className="flex flex-col items-center gap-4 py-6 text-center">
                <div className="p-3 bg-violet-50 rounded-full">
                    <BrainCircuit className="w-7 h-7 text-violet-500" />
                </div>
                <div>
                    <p className="text-sm font-medium text-gray-700">尚未进行内容分析</p>
                    <p className="text-xs text-gray-400 mt-1">需先完成字幕提取，再运行 AI 分析</p>
                </div>
                <button
                    onClick={() => setStep("model_select")}
                    className="flex items-center gap-2 px-5 py-2.5 bg-violet-600 hover:bg-violet-700 text-white text-sm font-medium rounded-lg transition-colors"
                >
                    <Sparkles className="w-4 h-4" />
                    配置并开始分析
                </button>
            </div>
        );
    }

    return (
        <div className="space-y-4">
            <p className="text-xs font-medium text-gray-500 uppercase tracking-wide">选择 LLM 模型</p>
            {models.map(model => (
                <label
                    key={model.id}
                    className={`flex items-start gap-3 p-3 rounded-lg border cursor-pointer transition-colors ${
                        selectedModel === model.id ? "border-violet-400 bg-violet-50" : "border-gray-200 hover:border-gray-300 bg-white"
                    }`}
                >
                    <input
                        type="radio"
                        name="llm-model"
                        value={model.id}
                        checked={selectedModel === model.id}
                        onChange={() => setSelectedModel(model.id)}
                        className="accent-violet-600 mt-0.5"
                    />
                    <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                            <span className="text-sm font-medium text-gray-800">{model.label}</span>
                            {model.recommended && (
                                <span className="text-[10px] px-1.5 py-0.5 bg-violet-100 text-violet-700 rounded-full font-medium">推荐</span>
                            )}
                        </div>
                        <p className="text-xs text-gray-500 mt-0.5">{model.desc}</p>
                    </div>
                </label>
            ))}
            <div className="flex gap-2 pt-1">
                <button
                    onClick={() => setStep("idle")}
                    className="px-4 py-2 text-sm text-gray-500 bg-white border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors"
                >
                    取消
                </button>
                <button className="flex-1 flex items-center justify-center gap-2 px-4 py-2 bg-violet-600 hover:bg-violet-700 text-white text-sm font-medium rounded-lg transition-colors">
                    <BrainCircuit className="w-4 h-4" />
                    开始分析
                </button>
            </div>
        </div>
    );
}

export default function SummaryOverviewPage() {
    useWorkspaceContext();
    const isCompleted = MOCK_ANALYSIS_STATUS === "completed";
    const isInProgress = MOCK_ANALYSIS_STATUS === "in_progress";

    return (
        <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-4">
            <div className={`flex items-center gap-2 px-4 py-3 rounded-xl border text-sm ${
                isCompleted
                    ? "bg-green-50 border-green-200 text-green-700"
                    : isInProgress
                        ? "bg-blue-50 border-blue-200 text-blue-700"
                        : "bg-gray-50 border-gray-200 text-gray-500"
            }`}>
                {isCompleted && <CheckCircle className="w-4 h-4 flex-shrink-0" />}
                {isInProgress && <Loader2 className="w-4 h-4 animate-spin flex-shrink-0" />}
                {!isCompleted && !isInProgress && <BrainCircuit className="w-4 h-4 flex-shrink-0" />}
                <span className="font-medium">
                    {isCompleted ? "分析已完成" : isInProgress ? "分析进行中…" : "尚未分析"}
                </span>
            </div>

            {MOCK_ANALYSIS_STATUS === "idle" && (
                <div className="bg-white rounded-xl border border-gray-200 p-4">
                    <StartAnalysis />
                </div>
            )}

            {isCompleted && (
                <>
                    <SectionCard title="内容摘要" icon={<BookOpen className="w-4 h-4" />}>
                        <p className="text-sm text-gray-700 leading-relaxed whitespace-pre-line">{MOCK_SUMMARY}</p>
                    </SectionCard>

                    <SectionCard title="关键词" icon={<Tags className="w-4 h-4" />}>
                        <div className="flex flex-wrap gap-2">
                            {MOCK_KEYWORDS.map(keyword => (
                                <span key={keyword} className="text-xs px-2.5 py-1 bg-violet-50 text-violet-700 rounded-full font-medium">
                                    {keyword}
                                </span>
                            ))}
                        </div>
                    </SectionCard>

                    <SectionCard title="核心概念" icon={<Lightbulb className="w-4 h-4" />}>
                        <div className="space-y-3">
                            {MOCK_CONCEPTS.map(concept => (
                                <div key={concept.id} className="flex gap-3">
                                    <div className="w-1.5 h-1.5 rounded-full bg-violet-400 mt-1.5 flex-shrink-0" />
                                    <div>
                                        <p className="text-sm font-semibold text-gray-800">{concept.name}</p>
                                        <p className="text-xs text-gray-500 mt-0.5 leading-relaxed">{concept.desc}</p>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </SectionCard>

                    <SectionCard title="翻译" icon={<Languages className="w-4 h-4" />} defaultOpen={false}>
                        <div className="space-y-3">
                            <div className="flex items-center gap-3">
                                <label className="text-xs text-gray-500 w-16 flex-shrink-0">目标语言</label>
                                <select className="flex-1 text-sm border border-gray-200 rounded-lg px-3 py-1.5 bg-white focus:ring-1 focus:ring-violet-400 outline-none">
                                    <option>英文（English）</option>
                                    <option>日文（日本語）</option>
                                    <option>韩文（한국어）</option>
                                </select>
                            </div>
                            <button className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-white border border-gray-200 hover:bg-gray-50 text-gray-700 text-sm font-medium rounded-lg transition-colors">
                                <Languages className="w-4 h-4" />
                                翻译摘要
                            </button>
                        </div>
                    </SectionCard>
                </>
            )}
        </div>
    );
}
