package com.github.project_fredica.components

import androidx.compose.runtime.Composable

@Composable
expect fun BackHandler2(enabled: Boolean = true, onBack: () -> Unit)