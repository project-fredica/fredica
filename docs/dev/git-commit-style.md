---
title: Git Commit 风格指南
order: 140
---

# Git Commit 风格指南

本文档描述 Fredica 项目的 git commit 消息约定。

---

## 1. 基本格式

```
<type>(<scope>): <subject>

<body>
```

- **首行**：`type(scope): subject`，不超过 80 字符
- **正文**：按模块分组，每条变更一行，以 `- ` 开头

---

## 2. Type 类型

| type | 用途 |
|------|------|
| `feat` | 新功能、新模块 |
| `fix` | Bug 修复 |
| `refactor` | 重构（不改变外部行为） |
| `docs` | 文档变更 |
| `test` | 测试相关 |
| `chore` | 构建、依赖、配置等杂项 |
| `wip` | 进行中的工作（见下文） |

---

## 3. wip 提交

**`wip` 是本项目最常用的 type 之一。** 开发过程中随时用 `wip` 暂存进度，不必等到功能完整才提交。

```
wip: 移除 PromptGraph 引擎，新增 Torch 版本管理模块
wip: WebenSource 分析进度同步（进行中）
wip: 前端任务中心重构草稿
```

`wip` 提交的正文格式与普通提交相同，列出已完成的部分即可，未完成的不必提及。

---

## 4. Scope 作用域

scope 用于快速定位变更范围，可以是模块名、技术层或跨层组合：

```
feat(worker): ...
fix(python/bilibili): ...
refactor(webui): ...
feat(executor/kotlin): ...
feat(asr+webui): ...          # 跨层用 + 连接
refactor(json+error): ...     # 跨关注点用 + 连接
docs(dev): ...
docs(claude): ...
```

---

## 5. Subject 主题行

- 用中文，简洁描述"做了什么"
- 多个并列变更用 `+` 或 `·` 连接，不超过 80 字符
- 不加句号

```
# ✅ 好的例子
feat(worker): DAG 依赖失败级联取消 + MaterialGetRoute
fix(python/bilibili): 修复凭据校验逻辑（check_valid 取反）
refactor(webui): 抽取 WorkflowInfoPanel 组件，重构任务状态展示
feat(asr+webui): 集成 faster-whisper ASR 支持，重构素材库组件

# ❌ 避免
feat: update code
feat(worker): 修改了一些东西
```

---

## 6. 正文格式

正文按**模块**分组，每组一个二级标题（`type(scope):`），组内每条变更一行：

```
feat(worker):
- Task: 新增 resetStaleTasks()、failOrphanedTasks()
- WorkerEngine: start() 改为 suspend，新增 runStartupRecovery()
- 新增测试：TaskReconcileTest、WorkflowRunReconcileTest

feat(weben):
- WebenSource: 新增 progress 字段（0-100），DB schema 迁移
- WebenSourceAnalyzeRoute: Phase C 完整实现——创建 WorkflowRun + 任务链

fix(bridge):
- MyJsMessageHandler: 修复 kmpJsBridge 回调中单引号导致 JS 语法错误
```

变更描述格式：`- <类名/文件名>: <做了什么>`，说明具体改动而非泛泛描述。

---

## 7. 大型提交示例

跨多个模块的大型提交，首行用 `·` 列出主要模块，正文按模块展开：

```
feat: 启动恢复系统 + WebenSource 分析流水线 Phase C + Executor 链 + B站凭据 + 知识网络前端

fix(bridge):
- MyJsMessageHandler: 修复 kmpJsBridge 回调中单引号导致 JS 语法错误

feat(worker):
- Task: 新增 resetStaleTasks()、failOrphanedTasks()、snapshotNonTerminalTasks()
- WorkerEngine: start() 改为 suspend，新增 runStartupRecovery() 五步恢复序列
- RestartTaskLog: 新增数据模型 + DB 实现，记录每次启动恢复日志（可审计）
- 新增测试：RestartTaskLogDbTest、TaskReconcileTest、WorkflowRunReconcileTest

feat(executor/kotlin):
- FetchSubtitleExecutor: Bilibili 字幕轨优先，SHA-256 缓存，Whisper ASR 兜底
- WebenConceptExtractExecutor: 切块 → PromptGraph → 写 WebenConcept/Relation/Flashcard

refactor(startup):
- main.kt: 修正 runBlocking(Dispatchers.IO) 误用
- FredicaApi.jvm.kt: 新增 engineScope 持久化；新增 onAppReady() 启动后台对账
```

---

## 8. 提交流程

### 提交前：git diff 确认变更

提交前先用 `git diff` 全面了解本次改动，再据此撰写 commit 消息：

```shell
# 查看所有未暂存的变更
git diff

# 查看已暂存的变更
git diff --cached

# 查看变更文件列表（快速概览）
git diff --stat
git status --short
```

通过 diff 确认：变更范围是否与预期一致、有无意外修改、scope 和 subject 是否准确覆盖所有改动。

### 一次提交整个暂存区

**优先将所有相关变更放在同一个 commit 中提交**，而不是拆成多个小 commit。这样每个 commit 都是一个完整的逻辑单元，便于 `git log` 追踪和 `git revert`。

```shell
# 暂存所有变更后一次提交
git add <file1> <file2> ...
git commit -m "..."

# wip 场景：快速暂存全部
git add -A && git commit -m "wip: ..."
```

只有在变更明确属于不同关注点（如同时有功能开发和文档更新）且希望分开追踪时，才考虑拆分提交。

---

## 9. Co-Author 行

AI 辅助开发的提交在正文末尾加 Co-Author 行：

```
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
```
