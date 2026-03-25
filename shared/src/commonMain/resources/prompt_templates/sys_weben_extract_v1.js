async function main() {
  const subtitle = await getVar("material/current/subtitles/default_text")
  const schemaHint = await getSchemaHint("weben/summary")

  return `你是一位知识图谱构建专家。请根据以下视频字幕，抽取结构化知识。

${schemaHint}

字幕内容：
${subtitle}

请严格按照上方 JSON Schema 格式输出，不要添加任何额外字段。`
}
