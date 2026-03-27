async function main() {
  // subtitles/{lan}：指定语言代码（如 ai-zh、zh），first 表示取首条
  const subtitle = await getVar("material/" + __materialId + "/subtitles/first")
  const schemaHint = await getSchemaHint("weben/summary")

  return `你是一位知识图谱构建专家。请根据以下信息，抽取结构化知识。

<视频字幕内容>
${subtitle}
</视频字幕内容>

---

请严格按照下方 JSON Schema 格式输出，不要添加任何额外字段

\`\`\`json
${schemaHint}
\`\`\`

请严格按照上方 JSON Schema 格式输出，不要添加任何额外字段。`
}
