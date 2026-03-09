---
title: 《织》功能开发备忘录
---

# 《织》功能开发备忘录

> **文档状态**：草案 v2
> **创建日期**：2026-03-09
> **关联文档**：`weben-the-ai-learning-assistant.md`、`prompt-graph.md`、`workflow-design.md`

---

## 1. 现有基础盘点

### 1.1 可直接复用

| 已有能力 | 位置 | 用途 |
|---------|------|------|
| Bilibili 视频下载（WebSocket 任务框架） | `DownloadBilibiliVideoExecutor` | 获取视频文件 |
| Bilibili 字幕 API | `routes/bilibili_subtitle.py` | 直接获取平台字幕（优先路径） |
| Bilibili AI 总结缓存 | `BilibiliVideoAiConclusionRoute` + `BilibiliAiConclusionCacheDb` | 辅助摘要初筛 |
| Whisper 转录子进程 | `subprocess/transcribe.py` | 无字幕时 ASR 兜底 |
| LLM SSE 流式客户端 | `LlmSseClient.kt` | 概念抽取 LLM 调用 |
| Worker Engine + DAG 调度 | `WorkerEngine` + `DagEngine` | 串联视频处理流水线 |
| Prompt Graph 设计 | `prompt-graph.md`（设计完成，待实现） | 多步 LLM 链（概念抽取） |
| SQLite + Ktorm | `jvmMain` | 知识图谱数据存储 |

### 1.2 需新建

- 知识图谱 DB 表层（11 张表，见 §3）
- Prompt Graph Engine 实现（`prompt-graph.md` 设计的首次落地）
- 视频分析 Executor 链（FetchSubtitle → ExtractAudio → TranscribeChunk → WebenAnalyze）
- 前端：概念瀑布流、概念详情页、知识图谱可视化、复习流

---

## 2. 架构决策

### 2.1 图数据库：SQLite 优先，不引入 neo4j

- neo4j embedded 版本需要企业许可证；社区版只有服务模式，引入成本高
- 知识图谱的核心查询（邻居节点、路径检索、按掌握度排序）在 SQLite 上用关系查询可全覆盖
- SQLite 是整个项目的统一存储，保持技术栈一致
- Phase 2+ 再评估（届时节点/边数量才会暴露关系查询瓶颈）

### 2.2 向量检索：MVP 不引入

MVP 阶段用**规范化字符串匹配**做概念去重（小写 + 去标点 + 别名表辅助）。
Phase 2+ 叠加 `sentence-transformers`（via Python 服务），届时同时考虑 Android 兼容性。

### 2.3 概念抽取流程

```
视频来源（B站 BV 号 / 本地文件）
    │
    ▼
① 获取字幕         ← 优先路径：Bilibili 字幕 API（bilibili_subtitle.py）
    │（无字幕时走 ②）
    ▼
② ASR 转录         ← ExtractAudio → TranscribeChunk（Whisper）
    │
    ▼
③ 分段写库         ← WebenConceptExtractExecutor 第一步：
    │                  按语义切块，写入 weben_segment（Kotlin 内联，不经过 PromptGraph）
    ▼
④ 概念抽取 LLM    ← PromptGraphEngine 运行"视频概念提取图"
    ├─ LLM_CALL：概念 + 关系提取（structured output）
    └─ LLM_CALL：闪卡生成（Q/A 对）
    │
    ▼
⑤ 写入知识图谱 DB  ← 概念去重合并 → weben_concept / weben_relation /
    │                  weben_flashcard / weben_segment_concept / weben_concept_source
    ▼
⑥ 概念瀑布流展示
```

### 2.4 掌握度：附着在闪卡上，聚合到概念

复习的最小单元是**闪卡（Q/A 对）**，不是概念本身。
SM-2 的状态（`ease_factor`、`interval_days`）存在 `weben_flashcard` 上，每次评分更新对应的闪卡。
`weben_concept.mastery` 是只读缓存——由 Db 层在每次 flashcard 更新后自动重算（该概念所有闪卡的加权平均），前端读概念列表时无需额外 JOIN。

### 2.5 PromptGraph 的 MVP 边界

Phase B 的 PromptGraphEngine **只运行 `LLM_CALL` 节点**。
切块（chunk_segment）和去重合并（dedup_merge）两步逻辑在 Kotlin 侧内联，引擎在遇到 `TRANSFORM` 节点时调用预注册的 Kotlin 处理函数（而非执行 JS 表达式字符串）。
这个妥协需要在引擎代码注释中明确标注，避免后续误解。

### 2.6 系统内置 PromptGraphDef 的初始化

`FredicaApi.jvm.kt` 启动时调用 `PromptGraphDb.upsertSystemGraphs()`，将内置 Graph 的 JSON 定义（硬编码为资源文件 `resources/prompt_graphs/weben_video_concept_extract.json`）以 `INSERT OR REPLACE WHERE source_type='system'` 写入 DB。`source_type='system'` 的 Graph 在 UI 层不可删除。

---

## 3. 数据模型

### 3.1 完整表清单

```
weben_source              ← 视频/文档来源
weben_concept             ← 概念节点（mastery 为聚合缓存）
weben_concept_alias       ← 概念别名
weben_concept_source      ← 概念-来源时间点关联（M:N 关联表）
weben_relation            ← 概念关系边（概念自关联的 M:N 关联表）
weben_relation_source     ← 关系-来源关联（M:N 关联表）
weben_segment             ← 视频时间段（摘要 + 播放器导航数据基础）
weben_segment_concept     ← 段-概念多对多（M:N 关联表）
weben_flashcard           ← 闪卡（SM-2 复习单元，含 ease_factor / interval）
weben_mastery_record      ← 每次复习历史快照
weben_note                ← 用户笔记
```

共 **11 张表**，Phase A 全部建好，避免后续 ALTER TABLE。

> **原则**：所有表均不使用数据库外键约束（REFERENCES / ON DELETE CASCADE），引用完整性和级联清理由业务层负责，保持 DDL 简洁易维护。

---

### 3.2 实体关系

#### 一对多（1:N）

| 一端 | 多端 | 关联字段 | 说明 |
|------|------|---------|------|
| `weben_concept` | `weben_concept_alias` | `concept_id` | 一个概念有多个别名 |
| `weben_concept` | `weben_flashcard` | `concept_id` | 一个概念生成多张闪卡 |
| `weben_concept` | `weben_note` | `concept_id` | 一个概念有多条笔记 |
| `weben_source` | `weben_segment` | `source_id` | 一个视频来源切成多个时间段 |
| `weben_flashcard` | `weben_mastery_record` | `flashcard_id` | 一张闪卡有多条复习历史 |
| `weben_source` | `weben_flashcard` | `source_id` | 一个来源衍生多张闪卡（**nullable**，可选关联） |

#### 多对多（M:N，通过关联表）

| 左端 | 右端 | 关联表 | 关联表上的额外字段 |
|------|------|--------|-----------------|
| `weben_concept` | `weben_source` | `weben_concept_source` | `timestamp_sec`（概念出现的精确时间点）、`excerpt`（原文摘录） |
| `weben_relation` | `weben_source` | `weben_relation_source` | `timestamp_sec`、`excerpt` |
| `weben_segment` | `weben_concept` | `weben_segment_concept` | `is_primary`（是否为该段核心概念） |

#### 自引用（weben_concept → weben_concept）

`weben_relation` 的 `subject_id` 和 `object_id` 都指向 `weben_concept.id`，
表达概念之间的有向语义关系（主体 → 谓语 → 客体）。
这是一个**概念自关联的 M:N**，`weben_relation` 本身就是这个自关联的关联表。

#### 跨系统关联

`weben_source.material_id` 关联现有素材库的 `material.id`，nullable（外部导入的来源无素材关联）。

#### 冗余缓存字段（需业务层维护一致性）

| 表 | 字段 | 真相来源 | 维护时机 |
|----|------|---------|---------|
| `weben_concept` | `mastery` | `weben_flashcard` 所有关联卡的 ease_factor 聚合 | 每次 flashcard 评分后由 `WebenFlashcardDb` 重算 |
| `weben_flashcard` | `review_count` | `weben_mastery_record` 的行数 | 每次写入 mastery_record 时 +1 |
| `weben_mastery_record` | `concept_id` | `weben_flashcard.concept_id` | 写入 mastery_record 时从 flashcard 读取并冗余写入 |

#### 关系全图

```
material ─────(material_id, nullable)─────────────────────────────────────┐
                                                                           │
                                                                     weben_source
                                                                    /      │
                             (M:N via weben_concept_source)        /    (1:N)
                                                                  /        │
weben_concept ◄───────────────────────────────────────────────── ┘    weben_segment
    │   ▲                                                                   │
    │   │ (自关联 M:N via weben_relation)                  (M:N via weben_segment_concept)
    │   │                                                                   │
    │   └──────── weben_relation ──── (M:N via weben_relation_source) ─── weben_source
    │
    ├──(1:N)──→ weben_concept_alias
    ├──(1:N)──→ weben_flashcard ──(1:N)──→ weben_mastery_record
    └──(1:N)──→ weben_note
```

---

### 3.3 DDL

> **索引设计原则**：
> - 外键列（`concept_id`、`source_id` 等）一律建索引，支持反向查询
> - 高频排序列（`mastery`、`next_review_at`、`reviewed_at`）建索引
> - 能用复合索引覆盖常见查询 pattern 的，不建多个单列索引
> - 关联表的 `PRIMARY KEY (a, b)` 已覆盖按 `a` 查询；按 `b` 反查需额外建索引

#### `weben_source`（来源）

```sql
CREATE TABLE IF NOT EXISTS weben_source (
    id              TEXT    PRIMARY KEY,                -- UUID，来源唯一标识
    material_id     TEXT,                              -- 关联素材库（material.id），外部导入时可为 null
    url             TEXT    NOT NULL,                  -- 完整资源地址：视频页面 URL 或本地文件绝对路径
    title           TEXT    NOT NULL,                  -- 来源标题（视频标题或文章标题）
    source_type     TEXT    NOT NULL,                  -- 来源类型：'bilibili_video' | 'local_file' | 'web_article'
    bvid            TEXT,                              -- Bilibili 视频 BV 号，bilibili_video 专属，其余为 null
    duration_sec    REAL,                              -- 视频总时长（秒），非视频来源为 null
    quality_score   REAL    NOT NULL DEFAULT 0.5,      -- 来源质量分（0-1），用于图谱置信度加权，默认中等
    analysis_status TEXT    NOT NULL DEFAULT 'pending',-- 分析流水线状态：'pending' | 'analyzing' | 'completed' | 'failed'
    created_at      INTEGER NOT NULL                   -- 记录创建时间，Unix 秒
);
CREATE INDEX IF NOT EXISTS idx_ws_material        ON weben_source(material_id);
CREATE INDEX IF NOT EXISTS idx_ws_bvid            ON weben_source(bvid);
CREATE INDEX IF NOT EXISTS idx_ws_analysis_status ON weben_source(analysis_status);
```

#### `weben_concept`（概念节点）

```sql
CREATE TABLE IF NOT EXISTS weben_concept (
    id               TEXT    PRIMARY KEY,              -- UUID，概念唯一标识
    canonical_name   TEXT    NOT NULL UNIQUE,          -- 规范化名称（去标点小写后的去重键，同时作为展示名）
    concept_type     TEXT    NOT NULL,                 -- 概念类型：'理论'|'术语'|'硬件经验'|'开发经验'|'方法技巧'|'工具软件'|'器件芯片'|'协议'|'公式'|'设计模式'
    brief_definition TEXT,                             -- AI 生成的简短定义，用户可手动修正；null 表示尚未生成
    metadata_json    TEXT    NOT NULL DEFAULT '{}',    -- 类型特定结构化元数据（公式变量说明、器件厂商参数等）
    confidence       REAL    NOT NULL DEFAULT 1.0,     -- AI 提取置信度（0-1），用户手动添加的概念固定为 1.0
    mastery          REAL    NOT NULL DEFAULT 0.0,     -- 【只读缓存】该概念所有闪卡 ease_factor 的归一化均值（0-1），禁止应用层直接写入
    first_seen_at    INTEGER NOT NULL,                 -- 首次在来源中出现的时间，Unix 秒；一旦写入不可更改
    last_seen_at     INTEGER NOT NULL,                 -- 最近一次新增来源关联的时间，Unix 秒；每次关联新来源时更新
    created_at       INTEGER NOT NULL,                 -- 记录创建时间，Unix 秒
    updated_at       INTEGER NOT NULL                  -- 记录最后修改时间（用户编辑定义/元数据时更新），Unix 秒
);
-- 复合索引：覆盖瀑布流"按类型过滤 + 按掌握度排序"的典型查询
-- 替代原来 idx_wc_type + idx_wc_mastery 两个单列索引
CREATE INDEX IF NOT EXISTS idx_wc_type_mastery ON weben_concept(concept_type, mastery);
```

> `mastery` 由 `WebenFlashcardDb` 在每次闪卡评分后重算并写入，计算公式：
> `avg((ease_factor - 1.3) / (5.0 - 1.3))` 对该概念所有闪卡取平均，结果归一到 [0, 1]。

#### `weben_concept_alias`（别名）

```sql
CREATE TABLE IF NOT EXISTS weben_concept_alias (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,    -- 自增主键，供 API 按 id 删除单条别名
    concept_id   TEXT    NOT NULL,                     -- 所属概念（weben_concept.id）
    alias        TEXT    NOT NULL,                     -- 别名文本（如缩写、英文原名、常见误拼）
    alias_source TEXT,                                 -- 别名来源描述：'LLM提取' | '用户添加' | 来源标题等
    UNIQUE (concept_id, alias)
);
CREATE INDEX IF NOT EXISTS idx_wca_alias ON weben_concept_alias(alias); -- 按别名反查概念，用于去重合并时的模糊匹配
```

#### `weben_concept_source`（概念-来源时间点关联）

```sql
CREATE TABLE IF NOT EXISTS weben_concept_source (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,   -- 自增主键
    concept_id    TEXT    NOT NULL,                    -- 所属概念（weben_concept.id）
    source_id     TEXT    NOT NULL,                    -- 来源（weben_source.id）
    timestamp_sec REAL,                                -- 概念在视频中出现的精确时间点（秒），文章/文件来源为 null
    excerpt       TEXT                                 -- 来源原文摘录，供图谱追溯时展示
    -- 注：timestamp_sec 含 null 时 UNIQUE 约束不可靠（SQLite 中 NULL != NULL），
    --     去重逻辑在业务层处理：插入前按 (concept_id, source_id, timestamp_sec) 查重
);
CREATE INDEX IF NOT EXISTS idx_wcs_concept_source ON weben_concept_source(concept_id, source_id); -- 覆盖"某概念来自哪些来源"及去重查询
CREATE INDEX IF NOT EXISTS idx_wcs_source         ON weben_concept_source(source_id);             -- 支持"某来源包含哪些概念"的反向查询
```

#### `weben_relation`（关系边）

```sql
-- weben_concept 自关联的 M:N 关联表
-- subject_id 和 object_id 均引用 weben_concept.id
CREATE TABLE IF NOT EXISTS weben_relation (
    id          TEXT    PRIMARY KEY,                   -- UUID
    subject_id  TEXT    NOT NULL,                      -- 主体概念（weben_concept.id）
    predicate   TEXT    NOT NULL,                      -- 关系谓语：'包含'|'依赖'|'用于'|'对比'|'是...的实例'|'实现'|'扩展'
    object_id   TEXT    NOT NULL,                      -- 客体概念（weben_concept.id）
    confidence  REAL    NOT NULL DEFAULT 1.0,          -- 关系置信度（0-1），多来源支撑时可累积更新
    is_manual   INTEGER NOT NULL DEFAULT 0,            -- 来源标记：0=AI 推导，1=用户手动添加
    created_at  INTEGER NOT NULL,                      -- 记录创建时间，Unix 秒
    updated_at  INTEGER NOT NULL,                      -- 最后更新时间（confidence 被新来源更新时），Unix 秒
    UNIQUE (subject_id, predicate, object_id)
);
CREATE INDEX IF NOT EXISTS idx_wr_subject ON weben_relation(subject_id); -- 查"某概念出发的所有关系"（正向图遍历）
CREATE INDEX IF NOT EXISTS idx_wr_object  ON weben_relation(object_id);  -- 查"指向某概念的所有关系"（反向图遍历）
```

#### `weben_relation_source`（关系-来源关联）

```sql
-- 产品文档 §3.1："关系边可附带来源"
CREATE TABLE IF NOT EXISTS weben_relation_source (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,   -- 自增主键
    relation_id   TEXT    NOT NULL,                    -- 所属关系（weben_relation.id）
    source_id     TEXT    NOT NULL,                    -- 来源（weben_source.id）
    timestamp_sec REAL,                                -- 该关系被提及的视频时间点（秒），非视频来源为 null
    excerpt       TEXT                                 -- 来源原文摘录
    -- 注：去重逻辑同 weben_concept_source，在业务层处理
);
CREATE INDEX IF NOT EXISTS idx_wrs_relation ON weben_relation_source(relation_id);
CREATE INDEX IF NOT EXISTS idx_wrs_source   ON weben_relation_source(source_id);
```

#### `weben_segment`（视频时间段）

```sql
-- 视频播放器上下文保持（产品文档 §4.4）的数据基础：
-- 进度条知识点标记、顶部横幅（当前/前后概念）、迷你时间轴、60秒预览窗口
CREATE TABLE IF NOT EXISTS weben_segment (
    id          TEXT    PRIMARY KEY,                   -- UUID
    source_id   TEXT    NOT NULL,                      -- 所属视频来源（weben_source.id）
    seq         INTEGER NOT NULL,                      -- 段序号（0-based），同一来源内按 seq 排序即为时间顺序
    start_sec   REAL    NOT NULL,                      -- 本段起始时间（秒）
    end_sec     REAL    NOT NULL,                      -- 本段结束时间（秒）
    summary     TEXT,                                  -- AI 生成的分段摘要，用于预览窗口要点列表；null 表示尚未 LLM 分析
    headline    TEXT,                                  -- 一句话标题，用于播放器顶部横幅；null 表示尚未 LLM 分析
    created_at  INTEGER NOT NULL,                      -- 切块写入时间，Unix 秒
    UNIQUE (source_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_wseg_source ON weben_segment(source_id);
```

#### `weben_segment_concept`（段-概念多对多）

```sql
CREATE TABLE IF NOT EXISTS weben_segment_concept (
    segment_id  TEXT    NOT NULL,                      -- 所属段（weben_segment.id）
    concept_id  TEXT    NOT NULL,                      -- 关联概念（weben_concept.id）
    is_primary  INTEGER NOT NULL DEFAULT 0,            -- 是否为该段的核心概念（1=是），用于播放器进度条高亮和顶部横幅展示
    PRIMARY KEY (segment_id, concept_id)
    -- PRIMARY KEY 已覆盖按 segment_id 的正向查询，无需额外索引
);
CREATE INDEX IF NOT EXISTS idx_wsc_concept ON weben_segment_concept(concept_id); -- 按概念反查"出现在哪些段"，支持概念详情页的来源时间线
```

#### `weben_flashcard`（闪卡 / SM-2 复习单元）

```sql
-- SM-2 状态附着在闪卡上，而非概念上
-- weben_concept.mastery 是本表 ease_factor 的聚合缓存
CREATE TABLE IF NOT EXISTS weben_flashcard (
    id             TEXT    PRIMARY KEY,                -- UUID
    concept_id     TEXT    NOT NULL,                   -- 所属概念（weben_concept.id）
    source_id      TEXT,                               -- 闪卡来源（weben_source.id）；AI 生成时记录来源视频，用户手动创建时可为 null
    question       TEXT    NOT NULL,                   -- 题面（正面），如"GPIO 的两种输出模式是什么？"
    answer         TEXT    NOT NULL,                   -- 答案（背面）
    card_type      TEXT    NOT NULL DEFAULT 'qa',      -- 卡片类型：'qa'（问答翻转）| 'cloze'（填空，answer 中 {{c1::...}} 标记挖空处）
    is_system      INTEGER NOT NULL DEFAULT 1,         -- 生成方式：1=AI 自动生成，0=用户手动创建
    ease_factor    REAL    NOT NULL DEFAULT 2.5,       -- SM-2 难易系数（范围 1.3-5.0）；值越低代表越难记，每次评分后动态调整
    interval_days  REAL    NOT NULL DEFAULT 1.0,       -- SM-2 当前复习间隔（天）；下次复习前的等待天数
    review_count   INTEGER NOT NULL DEFAULT 0,         -- 【缓存】累计复习次数，每次写入 mastery_record 时 +1，避免频繁 COUNT 查询
    next_review_at INTEGER NOT NULL,                   -- 下次复习的预定时间（Unix 秒），由上次评分后 reviewed_at + interval_days 计算
    created_at     INTEGER NOT NULL                    -- 记录创建时间，Unix 秒
);
CREATE INDEX IF NOT EXISTS idx_wfl_concept  ON weben_flashcard(concept_id);
CREATE INDEX IF NOT EXISTS idx_wfl_next_rev ON weben_flashcard(next_review_at); -- 复习队列查询：WHERE next_review_at <= ? ORDER BY next_review_at
```

#### `weben_mastery_record`（每次复习历史）

```sql
-- 每次复习都追加一条，用于绘制掌握度曲线和历史回溯
-- 不是状态主表；SM-2 的当前状态以 weben_flashcard 为准
CREATE TABLE IF NOT EXISTS weben_mastery_record (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT, -- 自增主键
    flashcard_id        TEXT    NOT NULL,                  -- 被复习的闪卡（weben_flashcard.id）
    concept_id          TEXT    NOT NULL,                  -- 【冗余】所属概念，从 flashcard.concept_id 复制，方便按概念聚合；无约束，由业务层保证一致
    review_type         TEXT    NOT NULL,                  -- 复习触发方式：'view'（浏览概念卡片）| 'quiz'（主动测验）| 'manual'（用户手动调分）
    rating              INTEGER,                           -- SM-2 质量评分（0-5）；quiz/manual 类型有值，view 类型为 null
    ease_factor_after   REAL    NOT NULL,                  -- 本次评分后的新 ease_factor 快照
    interval_after      REAL    NOT NULL,                  -- 本次评分后的新 interval_days 快照
    mastery_level_after REAL    NOT NULL,                  -- 本次评分后该概念的 mastery 值快照，用于绘制掌握度曲线
    reviewed_at         INTEGER NOT NULL                   -- 复习发生的时间，Unix 秒
);
CREATE INDEX IF NOT EXISTS idx_wmr_flashcard        ON weben_mastery_record(flashcard_id);
CREATE INDEX IF NOT EXISTS idx_wmr_concept_reviewed ON weben_mastery_record(concept_id, reviewed_at DESC); -- 按概念查历史并按时间倒序（掌握度曲线），替代两个单列索引
```

#### `weben_note`（用户笔记）

```sql
-- 概念详情页 §4.2 "我的笔记"；Phase 2+ 可升级为支持 Markdown 或语音
CREATE TABLE IF NOT EXISTS weben_note (
    id          TEXT    PRIMARY KEY,                   -- UUID
    concept_id  TEXT    NOT NULL,                      -- 所属概念（weben_concept.id）
    content     TEXT    NOT NULL,                      -- 笔记正文（当前为纯文本）
    created_at  INTEGER NOT NULL,                      -- 创建时间，Unix 秒
    updated_at  INTEGER NOT NULL                       -- 最后修改时间，Unix 秒
);
CREATE INDEX IF NOT EXISTS idx_wn_concept ON weben_note(concept_id);
```

---

### 3.5 Kotlin 数据类（commonMain）

新建目录 `shared/src/commonMain/kotlin/.../weben/`：

| 文件 | 内容 |
|------|------|
| `WebenSource.kt` | 数据类 + Repo 接口 + Service |
| `WebenConcept.kt` | 数据类 + Repo 接口 + Service |
| `WebenRelation.kt` | 数据类 + Repo 接口 + Service |
| `WebenSegment.kt` | 数据类 + Repo 接口 + Service |
| `WebenFlashcard.kt` | 数据类 + Repo 接口 + Service + `SM2Algorithm` object |
| `WebenNote.kt` | 数据类 + Repo 接口 + Service |

jvmMain 对应实现（`shared/src/jvmMain/kotlin/.../weben/`）：

`WebenSourceDb.kt` · `WebenConceptDb.kt` · `WebenRelationDb.kt` · `WebenSegmentDb.kt` · `WebenFlashcardDb.kt` · `WebenNoteDb.kt`

---

## 4. API 路由（commonMain）

### 4.1 概念相关

| 方法 | 路由名 | 说明 |
|------|--------|------|
| `GET` | `WebenConceptListRoute` | 瀑布流分页（按 type / mastery / next_review 过滤排序） |
| `GET` | `WebenConceptGetRoute` | 概念详情（含来源列表、邻居概念、关联闪卡数） |
| `POST` | `WebenConceptUpdateRoute` | 更新定义/元数据（用户手动修正） |

### 4.2 关系相关

| 方法 | 路由名 | 说明 |
|------|--------|------|
| `GET` | `WebenRelationListRoute` | 邻居查询（给定 concept_id，返回所有入/出边） |
| `POST` | `WebenRelationCreateRoute` | 手动添加关联 |
| `POST` | `WebenRelationDeleteRoute` | 删除关联 |

### 4.3 段落相关

| 方法 | 路由名 | 说明 |
|------|--------|------|
| `GET` | `WebenSegmentListRoute` | 查询某来源的全部段落列表（播放器时间轴数据） |

### 4.4 闪卡与复习

| 方法 | 路由名 | 说明 |
|------|--------|------|
| `GET` | `WebenReviewQueueRoute` | 今日待复习闪卡（按 next_review_at，含所属概念信息） |
| `POST` | `WebenFlashcardReviewRoute` | 提交单张闪卡评分（SM-2 更新 + 同步 concept.mastery 缓存） |
| `GET` | `WebenFlashcardListRoute` | 查询某概念的全部闪卡 |
| `POST` | `WebenFlashcardCreateRoute` | 用户手动创建闪卡 |

### 4.5 笔记

| 方法 | 路由名 | 说明 |
|------|--------|------|
| `GET` | `WebenNoteListRoute` | 查询某概念的笔记列表 |
| `POST` | `WebenNoteSaveRoute` | 创建或更新笔记（按 id 幂等） |
| `POST` | `WebenNoteDeleteRoute` | 删除笔记 |

### 4.6 来源与分析

| 方法 | 路由名 | 说明 |
|------|--------|------|
| `GET` | `WebenSourceListRoute` | 来源库列表（可按 material_id 过滤） |
| `POST` | `WebenSourceAnalyzeRoute` | 提交素材进行分析，创建 Executor 任务链 WorkflowRun |

### 4.7 学习路径

| 方法 | 路由名 | 说明 |
|------|--------|------|
| `POST` | `WebenLearningPathRoute` | 给定目标概念 id，返回推荐学习路径（概念序列 + 每步掌握度） |

---

## 5. 视频分析 Executor 链

### 5.1 任务链

```
WorkflowRun: "weben_analyze_{source_id}"
    │
    ├─ Task[1]: DOWNLOAD_BILIBILI_VIDEO     ← 已实现，直接复用
    │
    ├─ Task[2]: FETCH_SUBTITLE              ← 新：调 Python 字幕接口，结果存临时文件
    │              depends_on: [1]
    │
    ├─ Task[3]: EXTRACT_AUDIO              ← 新：ffmpeg 提取音轨（无字幕时才需要）
    │              depends_on: [1]
    │
    ├─ Task[4]: TRANSCRIBE_CHUNK           ← 新：Whisper 转录（depends on [3]）
    │              depends_on: [3]
    │
    └─ Task[5]: WEBEN_CONCEPT_EXTRACT      ← 新：核心分析
                   depends_on: [2, 4]      （读字幕或转录，优先字幕）
```

> Task[3] 和 Task[4] 仅在 Task[2] 无字幕时有意义，当前用简单策略：两条链都跑，Task[5] 优先读字幕结果，字幕为空才用 ASR 结果。CONDITION 节点留待 Phase B PromptGraph 完善后替代此策略。

### 5.2 WebenConceptExtractExecutor 执行逻辑

位置：`shared/src/jvmMain/.../worker/executors/WebenConceptExtractExecutor.kt`

```
1. 读取 transcript（字幕 or Whisper 结果，含时间戳段列表）
2. 按语义切块（~1000字/块，保留时间戳）
3. 逐块写入 weben_segment（headline 暂为空，由后续 LLM 填充）
4. 调用 PromptGraphEngine.run("system:weben_video_concept_extract", inputContext)
5. 解析输出：
   a. concepts → INSERT OR IGNORE weben_concept（规范化去重）
               → INSERT weben_concept_alias
               → INSERT weben_concept_source（timestamp 来自 LLM 的 timestamp_hints）
   b. relations → INSERT OR IGNORE weben_relation + weben_relation_source
   c. flashcards → INSERT weben_flashcard（初始 next_review_at = now + 1 day）
   d. segments → UPDATE weben_segment.headline / INSERT weben_segment_concept
6. 更新 weben_source 状态（分析完成）
7. 重算所有新增概念的 concept.mastery 缓存
```

### 5.3 系统内置 PromptGraphDef："视频概念提取图"

**ID**：`system:weben_video_concept_extract`

节点结构：

```
[Node1: concept_extract]  (LLM_CALL)
  输入：chunk_text（分块文本）、video_context（标题、简介）
  输出 schema：concept_list
        │
        ▼
[Node2: relation_extract]  (LLM_CALL)
  输入：concepts（来自 Node1 输出）
  输出 schema：relation_list
        │
        ▼
[Node3: flashcard_gen]  (LLM_CALL)
  输入：concepts（来自 Node1 输出）
  输出 schema：flashcard_list
```

> 切块（chunk_segment）和去重合并（dedup_merge）在 Kotlin 侧内联，不经过 PromptGraphEngine。PromptGraph 只处理需要 LLM 参与的三个节点。

**schema 定义：**

```json
concept_list: {
  "concepts": [{
    "name": "string",
    "type": "enum[理论|术语|硬件经验|开发经验|方法技巧|工具软件|器件芯片|协议|公式|设计模式]",
    "brief_definition": "string",
    "aliases": ["string"],
    "timestamp_hints": [number]
  }]
}

relation_list: {
  "relations": [{
    "subject": "string（概念 canonical_name）",
    "predicate": "enum[包含|依赖|用于|对比|是...的实例|实现|扩展]",
    "object": "string",
    "confidence": "number[0,1]"
  }]
}

flashcard_list: {
  "flashcards": [{
    "concept_name": "string",
    "question": "string",
    "answer": "string",
    "card_type": "enum[qa|cloze]"
  }]
}
```

---

## 6. SM-2 算法（commonMain）

```kotlin
object SM2Algorithm {
    /**
     * 更新单张闪卡的 SM-2 状态。
     * @param currentEF   当前 ease factor（初始 2.5，最低 1.3）
     * @param currentInterval 当前复习间隔（天）
     * @param rating      本次评分：0-5（<3 视为遗忘，需重来）
     * @return Pair(新 ease_factor, 新 interval_days)
     */
    fun update(currentEF: Double, currentInterval: Double, rating: Int): Pair<Double, Double> {
        val newEF = (currentEF + 0.1 - (5 - rating) * (0.08 + (5 - rating) * 0.02))
            .coerceAtLeast(1.3)
        val newInterval = when {
            rating < 3       -> 1.0               // 遗忘，重置
            currentInterval <= 1.0 -> 6.0         // 第一次复习
            else             -> currentInterval * newEF
        }
        return Pair(newEF, newInterval)
    }
}
```

**掌握度缓存重算（WebenFlashcardDb 内部调用）：**

```kotlin
// 该概念所有闪卡中，ease_factor 归一化后的加权平均
// mastery = avg(ease_factor - 1.3) / (5.0 - 1.3)，范围 [0, 1]
fun recalculateMastery(conceptId: String): Double
```

---

## 7. 学习路径算法

位置：`shared/src/commonMain/.../weben/WebenLearningPath.kt`

输入：目标概念 id；当前所有概念的 `mastery` 值（从 DB 读取）
输出：从掌握度最低的前置概念出发，到目标概念的推荐学习序列

**算法**：在 `weben_relation` 图上做 Dijkstra 最短路径，节点权重 = `1.0 - mastery`（掌握度越低、越需要先学）

```kotlin
fun findLearningPath(targetConceptId: String, concepts: Map<String, Double>): List<String>
```

- 依赖关系（predicate = `依赖`）方向的边权重最高（强制前置）
- 其他关系类型（`包含`、`用于`）边权重适度降低
- 掌握度 ≥ 0.8 的概念可跳过（但保留在路径中标注"已掌握"）
- 纯 Kotlin 实现，无外部图库依赖

---

## 8. 前端模块

### 8.1 路由结构

```
fredica-webui/app/routes/
├── weben._index.tsx              ← 概念瀑布流（主界面）
├── weben.concept.$id.tsx         ← 概念详情页
├── weben.graph.tsx               ← 全屏知识图谱
├── weben.review.tsx              ← 复习流（闪卡翻转 + SM-2 评分）
├── weben.path.tsx                ← 学习路径规划
└── weben.sources.tsx             ← 来源管理（关联素材库）
```

### 8.2 组件规划

```
fredica-webui/app/components/weben/
├── ConceptCard.tsx               ← 瀑布流单张卡片
├── ConceptCardWaterfall.tsx      ← 无限滚动瀑布流容器（Scroll Snap）
├── ConceptDetailPanel.tsx        ← 详情侧边栏
├── KnowledgeGraph.tsx            ← d3.js 力导向图
├── MasteryBar.tsx                ← 掌握度进度条
├── FlashcardReviewer.tsx         ← 闪卡翻转 + 评分按钮（0-5）
├── VideoTimelineBar.tsx          ← 播放器进度条知识点标记
├── VideoContextBanner.tsx        ← 播放器顶部横幅（当前/前后概念）
└── LearningPathView.tsx          ← 学习路径列表/图谱视图
```

### 8.3 技术选型

| 组件 | 技术 | 理由 |
|------|------|------|
| 知识图谱可视化 | **d3.js** | 产品文档指定；力导向图布局成熟 |
| 瀑布流滚动 | CSS Scroll Snap + Intersection Observer | 无额外依赖，性能好 |
| 视频片段预览 | `<video>` + `#t=start,end` 媒体片段 URI | 本地视频直接定位时间段 |
| 闪卡翻转动画 | CSS 3D transform（`rotateY`） | 无依赖，原生流畅 |

### 8.4 视频片段预览的现实约束

B 站视频需先下载到本地才能按时间戳播放。
**MVP 阶段**：概念详情页只展示时间戳文字 + "跳转到任务中心下载"按钮。
**Phase D+**：视频已下载完成时，直接用 `VideoSnippetPlayer` 播放对应片段（15s/30s/60s 可选）。

---

## 9. 开发阶段

### Phase A：数据层（第一优先）

**目标**：建好全部 11 张 DB 表 + API 路由骨架，可手动 CRUD 所有实体。

- [ ] `shared/commonMain/weben/` — 6 个数据类 + Repo 接口 + Service
- [ ] `shared/jvmMain/weben/` — 6 个 Db 实现（建表、CRUD）
- [ ] `FredicaApi.jvm.kt` — 初始化 6 个 Service
- [ ] `all_routes.kt` — 按字母序注册全部路由
- [ ] `jvmTest/weben/WebenConceptDbTest.kt` — CRUD + mastery 缓存不变量验证
- [ ] `jvmTest/weben/WebenFlashcardDbTest.kt` — SM-2 update 正确性 + mastery 重算

**完成标准**：`./gradlew :shared:jvmTest` 全绿；手动测试各路由可用。

---

### Phase B：PromptGraph 引擎（第二优先）

**目标**：实现 `PromptGraphEngine`，支持 `LLM_CALL` 节点 + Kotlin-handler `TRANSFORM` 节点。

- [ ] `shared/commonMain/` — `PromptGraphDef`、`PromptGraphRun`、`PromptNodeRun` 数据类
- [ ] `shared/jvmMain/` — `PromptGraphDb.kt`（三张表）、`PromptGraphEngine.kt`
- [ ] 功能：节点 DAG 执行、schema 校验（JSON Schema Draft 7）、取消信号传递、input_snapshot 记录
- [ ] 系统内置 Graph 种子初始化（`resources/prompt_graphs/weben_video_concept_extract.json`）
- [ ] API：`PromptGraphRunStartRoute`、`PromptGraphRunGetRoute`
- [ ] `jvmTest/PromptGraphEngineTest.kt`（FakeLlmClient mock）

**MVP 不做**：CONDITION 节点、HUMAN_REVIEW、MCP、schema 迁移脚本、Fork 管理 UI。

---

### Phase C：视频分析 Executor 链（第三优先）

**目标**：从 B 站 BV 号，全自动产出知识图谱节点。

- [ ] `FetchSubtitleExecutor`（调字幕 API，结果存临时文件）
- [ ] `ExtractAudioExecutor`（Python WS，ffmpeg 提取音轨）
- [ ] `TranscribeChunkExecutor`（Python WS，Whisper 转录）
- [ ] `WebenConceptExtractExecutor`（核心，调 PromptGraphEngine，写入 DB）
- [ ] `WebenSourceAnalyzeRoute`：接收 material_id，构建 WorkflowRun 任务链
- [ ] 端到端验证：真实 BV 号 → 任务链完成 → DB 中出现概念节点 + 闪卡

---

### Phase D：概念瀑布流 UI

- [ ] `weben._index.tsx` — 概念卡片瀑布流（Scroll Snap，分页加载）
- [ ] `ConceptCard.tsx` — 类型徽章、掌握度、相关概念标签
- [ ] `weben.concept.$id.tsx` — 来源列表、关系邻居、闪卡列表、笔记区
- [ ] 浏览即更新 mastery：触发 `WebenFlashcardReviewRoute`（rating=3，view 类型）

---

### Phase E：知识图谱可视化 + 学习路径

- [ ] `weben.graph.tsx` — d3.js 力导向图，节点着色（按类型），边类型视觉编码
- [ ] 点击节点 → 侧边栏展示概念详情
- [ ] `weben.path.tsx` — 学习路径规划（输入目标概念 → 调 `WebenLearningPathRoute`）
- [ ] 图谱内路径高亮模式

---

### Phase F：复习系统

- [ ] `weben.review.tsx` — 今日待复习闪卡流（`WebenReviewQueueRoute`）
- [ ] `FlashcardReviewer.tsx` — 正面题目/背面答案翻转 + 0-5 评分按钮
- [ ] 提交评分 → `WebenFlashcardReviewRoute` → SM-2 更新 → concept.mastery 同步

---

## 10. Phase 2+ 待规划

以下功能产品文档有提到，当前阶段不进开发范围：

| 功能 | 说明 |
|------|------|
| 多模态分析 | OCR（PPT/代码截图）、关键帧提取——需 `cv2`/`easyocr`，Python 依赖重 |
| YouTube 视频源 | 需 yt-dlp；与 Bilibili 路径不同，届时抽象 `VideoSourceAdapter` 接口 |
| 巩固测验 | 选择题/填空题——比闪卡复杂，独立成一个 Phase |
| 学习周报/统计 | 本周掌握概念数、复习次数等聚合查询 API |
| 系统通知推送 | KMP 桌面通知（托盘推送复习卡片）——需 `composeApp` 层实现 |
| 向量相似度去重 | `sentence-transformers` via Python 服务——届时同步考虑 Android 兼容 |
| Android 兼容 | `commonMain` 数据类和算法已平台无关；jvmMain Db 实现届时需 Room 替代版 |

---

## 11. 与现有 CLAUDE.md 开发计划的关系

《织》功能与 CLAUDE.md §6 的剩余 Executor 重写任务**共享 Phase C**：

- `ExtractAudioExecutor` 和 `TranscribeChunkExecutor` 是 CLAUDE.md 待完成项，也是《织》分析链必需项
- 用《织》的真实需求驱动这两个 Executor 的实现，一举两得

建议顺序：

```
《织》Phase A（数据层）        ← 独立，立即开始
    ↓
《织》Phase B（PromptGraph）   ← 独立，不阻塞 Phase 1 Executor 重写
    ↓
Phase 1 剩余 Executor 重写     ← 与《织》Phase C 合并推进
+ 《织》Phase C（Executor 链）
    ↓
全套测试 ./gradlew :shared:jvmTest 全绿
    ↓
《织》Phase D（瀑布流 UI）
    ↓
《织》Phase E/F（图谱 + 复习）
```
