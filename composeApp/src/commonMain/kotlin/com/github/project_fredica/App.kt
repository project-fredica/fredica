package com.github.project_fredica

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.project_fredica.appwebview.AppWebView
import com.github.project_fredica.components.AppColor
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview


@Composable
fun App() {
    val darkTheme: Boolean = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) {
            AppColor.darkColorScheme
        } else {
            AppColor.lightColorScheme
        },
    ) {
        AppWebView()
    }
}