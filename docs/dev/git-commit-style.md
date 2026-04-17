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

<intro>

<body>
```

- **首行**：`type(scope): subject`，不超过 80 字符
- **intro**：大型提交必须有 2-5 行的背景说明段落，解释"为什么做这件事"、"之前的状态"、"这次改变了什么"
- **正文**：按模块分组，每条变更一行，以 `- ` 开头。请勿遗漏任何重大修改（尤其是工具模块的修改），你可以分很多很多模块。

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
- 大型提交可在括号内标注 Phase 编号，方便追溯

```
# ✅ 好的例子
feat(worker): DAG 依赖失败级联取消 + MaterialGetRoute
fix(python/bilibili): 修复凭据校验逻辑（check_valid 取反）
refactor(webui): 抽取 WorkflowInfoPanel 组件，重构任务状态展示
feat(asr+webui): 集成 faster-whisper ASR 支持，重构素材库组件
feat(auth+routes): 完整认证系统（Phase 15）+ RouteContext 迁移（Phase 16.1）

# ❌ 避免
feat: update code
feat(worker): 修改了一些东西
```

---

## 6. Intro 段落（大型提交必须）

大型提交（跨 3 个以上模块，或引入重要架构决策）在正文模块列表之前，先写 **2-5 行背景说明**，回答三个问题：

1. **之前的状态**：改动前系统是什么样的？存在什么问题或缺失？
2. **这次做了什么**：核心变更是什么，一句话概括？
3. **为什么这样设计**：关键架构决策的理由（可用编号列表展开）

如果变更量大，可在 intro 后附一行 `N files changed, M insertions(+)` 作为规模提示。

```
本次提交是 Fredica 项目认证体系从零到完整的里程碑。在此之前，所有 API
路由均无鉴权，任何人都可以访问任意接口。本次提交引入了完整的多角色认证
系统（GUEST / TENANT / ROOT），并同步完成了路由框架的 handler 签名统一
（Phase 16.1），为后续路由归一化（Phase 16.2）打下基础。

设计核心思路：
  1. AuthIdentity sealed interface 作为"身份令牌"在整个请求生命周期流转，
     dispatcher 层统一做 minRole 检查，路由 handler 无需重复鉴权。
  2. AuthServiceApi 定义在 commonMain，实现在 jvmMain，原因是 Argon2 依赖
     Python CryptoService，commonMain 无法直接调用 JVM 专属库。
  3. Token 双模式：session token 对应登录用户，webserver_auth_token 对应游客，
     resolveIdentity() 统一解析，上层路由无需感知区别。

174 files changed, 19468 insertions(+), 162 deletions(-)
```

小型提交（1-2 个模块，改动直观）可省略 intro，直接写正文。

---

## 7. 正文格式

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

**描述粒度要求：**
- 说明**关键参数、常量、策略**（如"双重过期策略：expires_at 绝对 7 天 + last_accessed_at 活跃 24 小时"）
- 说明**设计原因**（如"IP 上限更高因 NAT 环境多用户共享 IP"）
- 说明**边界条件**（如"有注册记录的链接禁止删除，保护审计追踪"）
- 不要只写"新增 XXX"，要写"新增 XXX，做了什么，为什么"

---

## 8. 大型提交示例

参考提交 `68cc91e`（`feat(auth+routes): 完整认证系统（Phase 15）+ RouteContext 迁移（Phase 16.1）`）：

```
feat(auth+routes): 完整认证系统（Phase 15）+ RouteContext 迁移（Phase 16.1）

本次提交是 Fredica 项目认证体系从零到完整的里程碑。在此之前，所有 API
路由均无鉴权，任何人都可以访问任意接口。本次提交引入了完整的多角色认证
系统（GUEST / TENANT / ROOT），并同步完成了路由框架的 handler 签名统一
（Phase 16.1），为后续路由归一化（Phase 16.2）打下基础。

设计核心思路：
  1. AuthIdentity sealed interface 作为"身份令牌"在整个请求生命周期流转，
     dispatcher 层（FredicaApi.jvm.kt）统一做 minRole 检查，路由 handler
     无需重复鉴权，职责边界清晰。
  2. AuthServiceApi 定义在 commonMain，实现在 jvmMain，原因是 Argon2 密码
     哈希和 ECDSA 密钥对生成均依赖 Python CryptoService，commonMain 无法
     直接调用 JVM 专属库。
  3. Token 双模式：session token（fredica_session:<base64>）对应登录用户，
     webserver_auth_token（UUID）对应游客，resolveIdentity() 统一解析两种
     格式，上层路由无需感知区别。
  4. RouteContext 替代原来的协程上下文注入（RouteAuthContext + RouteRequestContext），
     改为显式 data class 参数传递，测试时直接构造，无需 withContext 包裹。

174 files changed, 19468 insertions(+), 162 deletions(-)

feat(auth/models):
- AuthModels.kt: AuthRole 枚举（GUEST=0 < TENANT=1 < ROOT=2，ordinal 直接用于 minRole 比较）
- AuthModels.kt: AuthIdentity sealed interface（RootUser / TenantUser / Guest），每次请求由 resolveIdentity() 解析
- AuthSessionDb.kt: 双重过期策略（expires_at 绝对 7 天 + last_accessed_at 活跃 24 小时）；并发 session 上限 5，FIFO 淘汰最旧
- LoginRateLimiter.kt: 内存滑动窗口，IP 维度（上限 10）× 用户名维度（上限 5）；IP 上限更高因 NAT 环境多用户共享 IP

refactor(routes/context):
- RouteContext.kt: 合并 RouteAuthContext + RouteRequestContext 为单一 data class；提供 authenticatedUser / userId 便捷属性
- FredicaApi.jvm.kt: minRole 在 dispatcher 层统一强制，路由 handler 无需重复检查
- 78 个路由 handler 签名统一为 handler(param, context: RouteContext)

test(auth):
- InviteIntegrationTest: 端到端邀请流程（创建链接 → 访问 → 注册 → 验证 max_uses 限制）
- 合计约 690 个测试

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
```

**此提交的优点总结（写提交信息时参考）：**
- intro 段落交代了"之前无鉴权"的背景，让读者立刻理解变更的重要性
- "设计核心思路"编号列表解释了关键架构决策的原因，而不只是罗列做了什么
- 正文每条描述都包含关键参数和设计理由（如"IP 上限更高因 NAT 环境"）
- 模块分组细化到子层（`auth/models`、`auth/service`、`auth/db`），而非笼统的 `auth`
- 测试条目说明了测试覆盖的场景，而不只是文件名

---

## 9. 提交流程

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

## 10. Co-Author 行

AI 辅助开发的提交在正文末尾加 Co-Author 行：

```
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
```
