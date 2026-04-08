package com.github.project_fredica.llm

interface LlmTestConfig {
    val llmTestApiKey: String
    val llmTestBaseUrl: String
    val llmTestModel: String

    val llmTestIsConfigured get() = llmTestApiKey.isNotBlank() && llmTestBaseUrl.isNotBlank() && llmTestModel.isNotBlank()
}