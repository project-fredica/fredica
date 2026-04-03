package com.github.project_fredica.prompt.variables

interface PromptVariableResolver {
    suspend fun resolve(key: String): String
}