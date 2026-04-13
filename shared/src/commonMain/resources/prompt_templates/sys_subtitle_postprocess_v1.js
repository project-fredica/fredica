async function main() {
  // __subtitleId 由编辑器上下文注入（如 asr.large-v3），first 表示取首条
  const subtitle = await getVar("material/" + __materialId + "/subtitles/" + __subtitleId)
  const title = await getVar("material/" + __materialId + "/title")
  const language = "中文"

  const blocks = subtitle.split(/\n\n+/).filter(function(b) { return b.trim() })
  const CHUNK_SIZE = 80

  if (blocks.length <= CHUNK_SIZE) {
    return buildPrompt(title, language, subtitle)
  }

  var chunks = []
  for (var i = 0; i < blocks.length; i += CHUNK_SIZE) {
    chunks.push(blocks.slice(i, i + CHUNK_SIZE).join("\n\n"))
  }
  return chunks.map(function(chunk) { return buildPrompt(title, language, chunk) })
}

function buildPrompt(title, language, srtContent) {
  return `你是一位专业的字幕翻译和校对专家。请将以下 SRT 字幕翻译为${language}，并进行纠错润色。（已经是${language}的话就纠错润色就行）

要求：
1. 保持原始 SRT 格式不变（序号、时间轴、空行分隔）
2. 不要合并或拆分字幕段
3. 翻译要自然流畅，符合${language}表达习惯
4. 专有名词保留原文并在括号中注明${language}含义

<视频标题>
${title}
</视频标题>

<SRT 字幕>
${srtContent}
</SRT 字幕>

请直接输出处理后的完整 SRT 字幕，不要添加任何解释或说明。
输出末尾必须以两个空行结束。`
}
