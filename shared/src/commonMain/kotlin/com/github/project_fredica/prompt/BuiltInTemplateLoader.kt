package com.github.project_fredica.prompt

// =============================================================================
// BuiltInTemplateLoader —— 内置系统模板加载器（expect/actual）
// =============================================================================
//
// commonMain 侧只声明接口；各平台在自己的 actual 中实现资源读取。
//
// 资源目录：commonMain/resources/prompt_templates/
//   _index.txt       — 一行一个模板 ID（# 开头为注释，空行忽略）
//   {id}.meta.json   — 元数据（id / name / description / category / schema_target）
//   {id}.js          — 脚本正文（async function main() { ... }）
// =============================================================================

import com.github.project_fredica.db.PromptTemplate

expect object BuiltInTemplateLoader {
    fun loadAll(): List<PromptTemplate>
}
