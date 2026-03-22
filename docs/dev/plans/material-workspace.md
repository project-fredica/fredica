---
title: 素材工作区页面设计
order: 20
---

# 素材工作区页面设计

> **文档状态**：设计稿（Draft）
> **创建日期**：2026-03-21
> **适用模块**：`fredica-webui`

---

## 1. 背景与动机

### 现状问题

当前对单个素材的所有操作（下载、转码、知识提取）都集中在 `material-library` 页面的一个 `MaterialActionModal` 弹窗里。随着 `decentralized-task-management.md` 中规划的功能越来越多（字幕提取、内容总结、声纹分类、OCR、翻译、超分等），弹窗无法承载足够的信息密度和操作流。

### 目标

为每个素材设计一个**专属工作区页面**，作为该素材所有处理能力的统一入口：

- 固定顶部显示素材基本信息（标题、封面、时长、来源）
- 通过**标签页**分割不同处理维度，每个标签对应一类任务
- 各标签页内容完全独立，通过子路由懒加载，互不干扰
- 现阶段先做**前端 + mock 数据**，任务触发 API 待后期对接

---

## 2. 路由设计

### 2.1 路由层级

React Router 7 文件系统路由约定：

```
fredica-webui/app/routes/
├── material.$materialId.tsx          ← 父布局（加载素材信息 + SubNav + Outlet）
├── material.$materialId._index.tsx   ← 概览（默认子路由）
├── material.$materialId.subtitle.tsx ← 字幕提取
├── material.$materialId.summary.tsx  ← 内容总结
├── material.$materialId.diarize.tsx  ← 声纹分类
├── material.$materialId.frames.tsx   ← 帧分析（场景检测 / OCR / 封面）
├── material.$materialId.transcode.tsx← 转码与增强
└── material.$materialId.tasks.tsx    ← 任务历史
```

**对应 URL：**

| 路由文件 | URL | 说明 |
|---------|-----|------|
| `material.$materialId.tsx` | `/material/:id` | 父布局，本身不渲染内容 |
| `material.$materialId._index.tsx` | `/material/:id` | 概览 |
| `material.$materialId.subtitle.tsx` | `/material/:id/subtitle` | 字幕提取 |
| `material.$materialId.summary.tsx` | `/material/:id/summary` | 内容总结 |
| `material.$materialId.diarize.tsx` | `/material/:id/diarize` | 声纹分类 |
| `material.$materialId.frames.tsx` | `/material/:id/frames` | 帧分析 |
| `material.$materialId.transcode.tsx` | `/material/:id/transcode` | 转码与增强 |
| `material.$materialId.tasks.tsx` | `/material/:id/tasks` | 任务历史 |

### 2.2 路由命名原则

- 父路由 `material.$materialId` 与现有 `material-library` 命名风格一致
- 子路由用**功能名称**（subtitle / summary / diarize），不用任务类型名（DIARIZE_AUDIO）
- 一个标签可以对应多个底层任务类型（例如 `subtitle` 覆盖 DOWNLOAD_SUBTITLE / EXTRACT_SUBTITLE / TRANSCRIBE_CHUNK）

---

## 3. 父布局组件（`material.$materialId.tsx`）

### 3.1 职责

1. 通过 `materialId` 调用 `/api/v1/MaterialGetRoute` 加载素材基本信息
2. 渲染固定 Header（素材信息卡 + 返回按钮）
3. 渲染 SubNav 标签栏（链接到各子路由）
4. 渲染 `<Outlet />`（子路由内容区）

### 3.2 布局草图

```
┌─────────────────────────────────────────────────────────────┐
│ ← 返回    [封面缩略图]  标题（截断）                          │
│           来源类型 · 时长 · 添加时间      [状态徽章]          │
├──────────────────────────────────────────────────────────────┤
│ [概览] [字幕提取] [内容总结] [声纹分类] [帧分析] [转码] [任务] │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│                    <Outlet />                                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 共用状态/Context

父布局通过 React Context 向子路由共享：

```ts
interface MaterialWorkspaceContext {
    material: MaterialVideo;       // 素材基本信息
    refreshMaterial: () => void;   // 强制刷新素材信息
}
```

子路由通过 `useOutletContext<MaterialWorkspaceContext>()` 获取，无需重复请求。

### 3.4 SubNav 组件

```
tabs = [
  { label: '概览',    to: '',           icon: LayoutDashboard },
  { label: '字幕提取', to: 'subtitle',  icon: Subtitles },
  { label: '内容总结', to: 'summary',   icon: BrainCircuit },
  { label: '声纹分类', to: 'diarize',   icon: Users },
  { label: '帧分析',  to: 'frames',     icon: Film },
  { label: '转码',    to: 'transcode',  icon: Zap },
  { label: '任务',    to: 'tasks',      icon: ListChecks },
]
```

使用 `useResolvedPath` + `useMatch` 检测当前激活 tab（`end: false` 对非 index tab）。

---

## 4. 各子页面设计

### 4.1 概览（`_index`）

**目的：** 一眼看清素材的处理状态全景，提供快速进入各功能的入口。

**布局：**
- 上方：素材详细信息卡（分辨率、文件大小、编码格式、BVid 等）
- 中间：功能状态网格（每个功能一张卡，显示"未开始 / 进行中 / 已完成"）
- 底部：最近任务列表（最近 5 条，点击跳转到任务 tab）

**Mock 数据：**
```ts
const mockCapabilityStatus = {
  subtitle:   { status: 'completed', result: '中文字幕（平台）' },
  summary:    { status: 'in_progress', progress: 60 },
  diarize:    { status: 'idle' },
  frames:     { status: 'idle' },
  transcode:  { status: 'completed', result: 'MP4 已转码' },
}
```

---

### 4.2 字幕提取（`subtitle`）

**覆盖任务类型：**
- `DOWNLOAD_SUBTITLE`（平台 CC/AI 字幕，需登录凭据）
- `EXTRACT_SUBTITLE`（提取视频内嵌字幕轨）
- `TRANSCRIBE_CHUNK`（Whisper ASR 本地转录）
- `OCR_FRAME_BATCH` → `MERGE_OCR_RESULTS`（OCR 识别硬字幕）

**布局：**

```
┌─ 方案选择 ───────────────────────────────┐
│ ○ 平台字幕（Bilibili CC/AI）             │
│ ○ 内嵌字幕轨（从视频文件提取）            │
│ ○ 语音识别（Whisper ASR）                │
│ ○ OCR 字幕识别（适合硬字幕视频）          │
└───────────────────────────────────────────┘
┌─ 已提取字幕 ──────────────────────────────┐
│ 中文（zh-CN）  AI字幕  2024-03-10  [查看] │
│ 英文（en）     人工字幕 2024-03-10  [查看] │
└───────────────────────────────────────────┘
```

**方案子面板（选中后展开）：**
- **平台字幕**：自动检测可用轨道列表，点击下载
- **内嵌字幕轨**：自动扫描 + 一键提取
- **Whisper ASR**：选择模型（参考现有 weben-analyze 的 Step3ModelSelect）
- **OCR**：配置采样间隔 + 识别区域

**Mock 数据：** 预设 1 条"中文 AI 字幕已完成"的结果卡。

---

### 4.3 内容总结（`summary`）

**覆盖任务类型：**
- `AI_ANALYZE`（摘要、标签、关键要点）
- `TRANSLATE_TEXT`（字幕/摘要翻译）
- 现有 WebenSource 分析流程（知识提取）

**布局：**

```
┌─ 分析结果 ──────────────────────────────────┐
│ [视频摘要]  [关键词]  [内容标签]  [知识提取]  │
├───────────────────────────────────────────────┤
│ 摘要：本视频介绍了……（mock 3 段）             │
│ 关键词：[Python] [机器学习] [神经网络]        │
└───────────────────────────────────────────────┘
┌─ 翻译 ──────────────────────────────────────┐
│ 目标语言：[中文 ▾]   模型：[GPT-4o ▾]       │
│ [开始翻译]                                   │
└───────────────────────────────────────────────┘
```

**Mock 数据：** 预设摘要文本和关键词数组。

---

### 4.4 声纹分类（`diarize`）

**覆盖任务类型：**
- `ENHANCE_AUDIO`（降噪，可选前处理）
- `DIARIZE_AUDIO`（说话人分类，SPEAKER_DIARIZE 能力）
- `DETECT_EMOTION`（情感标签，可选）
- `FINGERPRINT_AUDIO`（声纹指纹）

**布局：**

```
┌─ 说话人分析 ────────────────────────────────────┐
│ [开始分析] · 预计耗时：~5 min（本地 GPU）        │
│                                                 │
│ 时间轴：                                        │
│  0:00 ──[Speaker A]── 1:30 ──[Speaker B]── 3:00│
│                                                 │
│ 说话人 A：总时长 4:20（57%）                    │
│   [00:00] 大家好，今天我们……                    │
│   [01:30] 所以这里的关键是……                    │
│ 说话人 B：总时长 3:10（43%）                    │
└─────────────────────────────────────────────────┘
```

**Mock 数据：**
```ts
const mockSpeakers = [
  { id: 'A', duration: 260, segments: [{ start: 0, end: 90, text: '大家好…' }] },
  { id: 'B', duration: 190, segments: [{ start: 90, end: 180, text: '对对对…' }] },
]
```

---

### 4.5 帧分析（`frames`）

**覆盖任务类型：**
- `DETECT_SCENES`（镜头切换点检测）
- `EXTRACT_FRAMES`（按间隔抽帧）
- `OCR_FRAME_BATCH` → `MERGE_OCR_RESULTS`（字幕 OCR，与 subtitle tab 共享入口）
- `DETECT_FRAME_BATCH` → `MERGE_DETECT_RESULTS`（目标检测）
- `GENERATE_THUMBNAIL`（封面/缩略图生成）
- `DETECT_HARDCODED_SUBTITLE`（硬字幕区域检测）

**布局：**

```
┌─ 场景检测 ──────────────────────────────────┐
│ 已检测到 12 个镜头切换点                      │
│ [▶ 00:00] [▶ 01:23] [▶ 02:47] ……          │
└───────────────────────────────────────────────┘
┌─ 封面生成 ──────────────────────────────────┐
│ [缩略图 1] [缩略图 2] [缩略图 3]  [生成更多]  │
└───────────────────────────────────────────────┘
┌─ 目标检测 ──────────────────────────────────┐
│ [未开始]  模型：YOLO v8  [开始分析]           │
└───────────────────────────────────────────────┘
```

**Mock 数据：** 预设 3 张封面占位图（用 `bg-gray-200` 矩形代替），12 个场景切换点。

---

### 4.6 转码与增强（`transcode`）

**覆盖任务类型：**
- `TRANSCODE`（视频格式转换）
- `ENHANCE_AUDIO`（语音增强/降噪）
- `UPSCALE_VIDEO`（超分）
- `BURN_SUBTITLE` / `BLUR_SUBTITLE_REGION`（字幕烧录/遮盖）
- `SYNC_SUBTITLE` / `FORMAT_SUBTITLE`（字幕时轴对齐）
- `GENERATE_TTS`（TTS 合成）

**布局：**

```
┌─ 视频转码 ────────────────────────────────────┐
│ 目标格式：[MP4 ▾]  编码：[H.264 ▾]            │
│ 分辨率：[保持原始 ▾]   [开始转码]              │
└───────────────────────────────────────────────┘
┌─ 音频增强 ────────────────────────────────────┐
│ 方案：[DeepFilterNet ▾]  [开始降噪]            │
└───────────────────────────────────────────────┘
┌─ 字幕操作 ────────────────────────────────────┐
│ [烧录字幕]  [遮盖硬字幕]  [时轴对齐]           │
└───────────────────────────────────────────────┘
```

---

### 4.7 任务历史（`tasks`）

**复用组件：** `WorkflowInfoPanel`（已有）

**布局：**

```
┌─ 全部任务 ──────────────────────┬── 筛选 ─┐
│ 类型             状态   时间     │ [全部▾] │
├───────────────────────────────────┤         │
│ TRANSCRIBE_CHUNK  已完成  2min前  │         │
│ DOWNLOAD_SUBTITLE 已完成  5min前  │         │
│ DIARIZE_AUDIO     进行中   --     │         │
└───────────────────────────────────┴─────────┘
```

---

## 5. 入口集成

### 5.1 从素材库进入

在 `material-library._index.tsx` 的 `MaterialVideoRow` 中，将"点击素材"或"详情"按钮改为 Link 跳转到 `/material/:id`。

现有 `MaterialActionModal` 可逐步降级为快捷操作弹窗（仅保留下载/删除），复杂操作迁移到工作区页面。

### 5.2 侧边栏

暂不在侧边栏添加独立入口（工作区通过素材库进入）。待功能稳定后评估是否需要"最近工作区"快捷入口。

### 5.3 `weben-analyze.$materialId.tsx` 的去向

- **近期**：保留现有 weben-analyze 路由，`summary` tab 用 Link 引导跳转到该路由（兼容现有流程）
- **中期**：将 weben-analyze 的 UI 迁移到 `material.$materialId.summary.tsx`，废弃独立路由

---

## 6. 共用组件规划

| 组件 | 位置 | 说明 |
|------|------|------|
| `MaterialWorkspaceHeader` | `components/workspace/MaterialWorkspaceHeader.tsx` | 封面 + 标题 + 来源 + 返回按钮 |
| `MaterialWorkspaceSubNav` | `components/workspace/MaterialWorkspaceSubNav.tsx` | 标签栏，激活状态跟随路由 |
| `TaskCapabilityCard` | `components/workspace/TaskCapabilityCard.tsx` | 概览网格中的单个功能卡 |
| `SpeakerTimeline` | `components/workspace/SpeakerTimeline.tsx` | 声纹分类时间轴可视化 |
| `SubtitleSchemePanel` | `components/workspace/SubtitleSchemePanel.tsx` | 字幕方案选择器（四种方案） |

---

## 7. Mock 数据策略

- 所有子页面初始状态使用 **静态 mock 常量**（文件顶部 `const MOCK_XXX = ...`）
- 组件接受可选 prop `useMock?: boolean`，方便后续替换真实数据而不改结构
- API 调用函数抽取为独立 `async function fetch*(...)`，mock 阶段直接 return mock 数据

---

## 8. 实施顺序

1. **父布局** `material.$materialId.tsx`：加载素材信息 + Header + SubNav + Outlet
2. **概览子页面** `_index.tsx`：功能状态网格（全 mock）
3. **字幕提取子页面** `subtitle.tsx`：方案选择器 + 结果列表（mock）
4. **任务历史子页面** `tasks.tsx`：复用 `WorkflowInfoPanel`，接 mock 任务列表
5. **内容总结子页面** `summary.tsx`：mock 摘要 + 翻译配置 UI
6. **声纹分类子页面** `diarize.tsx`：mock 时间轴
7. **帧分析子页面** `frames.tsx`：mock 场景 + 封面占位图
8. **转码子页面** `transcode.tsx`：配置表单（全 mock）
9. **侧边栏 / 素材库入口集成**（最后，避免影响现有流程）

---

## 9. 已确认设计决策（2026-03-21）

- [x] **入口方式**：两种都使用——MaterialVideoRow 标题/封面直接 Link 跳转，同时保留 Modal 里"进入工作区"按钮。
- [x] **素材切换**：加。以 fixed overlay drawer 实现（点击按钮弹出，显示全部素材列表，高亮当前，点击切换路由）。
- [x] **weben-analyze 合并**：立即合并，`weben-analyze.$materialId.tsx` 和 `WebenSourceAnalysisModal.tsx` 一并删除；知识提取功能迁移到 `material.$materialId.summary.tsx`。
- [x] **自适应**：SubNav 横向滚动（不折叠），整体页面全自适应，Header/SubNav 使用 `sticky top-0`。
