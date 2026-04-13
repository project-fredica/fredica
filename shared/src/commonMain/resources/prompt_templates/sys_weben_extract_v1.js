async function main() {
  // subtitles/first：取第一条可用字幕；如有 __subtitleId 可替换为 subtitles/__subtitleId
  const subtitle = await getVar("material/" + __materialId + "/subtitles/first")
  const schemaHint = await getSchemaHint("weben/summary")
  const title = await getVar("material/" + __materialId + "/title")
  const description = await getVar("material/" + __materialId + "/description")

  return `你是一位知识图谱构建专家。请根据以下信息，抽取结构化知识。

<视频标题>
${title}
</视频标题>

<视频描述>
${description}
</视频描述>

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
