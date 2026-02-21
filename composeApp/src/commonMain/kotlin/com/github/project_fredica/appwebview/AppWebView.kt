package com.github.project_fredica.appwebview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.Platform
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.getPlatform
import com.github.project_fredica.appwebview.messages.AppWebViewMessages
import com.github.project_fredica.components.BackHandler2
import com.github.project_fredica.components.TextSm
import com.github.project_fredica.components.TextXsGray
import com.multiplatform.webview.jsbridge.IJsMessageHandler
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.jsbridge.rememberWebViewJsBridge
import com.multiplatform.webview.util.KLogSeverity
import com.multiplatform.webview.web.LoadingState
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.WebViewNavigator
import com.multiplatform.webview.web.WebViewState
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState
import org.jetbrains.compose.ui.tooling.preview.Preview


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppWebView() {
    val logger = createLogger { "AppWebView" }
    val appHosts = listOf(
        "http://localhost:${FredicaApi.DEFAULT_DEV_WEBUI_PORT}"
    )

    val jsBridge = rememberWebViewJsBridge()

    LaunchedEffect(jsBridge) {
        AppWebViewMessages.all.forEach {
            logger.debug("register handler : $it -> event ${it.methodName()}")
            jsBridge.register(it)
        }

    }

    val state = rememberWebViewState(
        url =
            if (Platform.getPlatform().isAndroid) appHosts[0]
            else "${appHosts[0]}/app-desktop-home"
    )
    LaunchedEffect(Unit) {
        state.webSettings.apply {
            logSeverity = KLogSeverity.Debug
            customUserAgentString =
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 11_1) AppleWebKit/625.20 (KHTML, like Gecko) Version/14.3.43 Safari/625.20"
        }
    }
    val navigator = rememberWebViewNavigator()
    var urlBarValue by remember(state.lastLoadedUrl) {
        mutableStateOf(state.lastLoadedUrl)
    }

    fun isAppDomain(): Boolean {
        urlBarValue.let {
            if (it != null) {
                if (appHosts.find { host -> it.startsWith(host) } !== null) {
                    return true
                }
            }
        }
        return false
    }

    fun navigateBack() {
        navigator.navigateBack()
    }

    BackHandler2 {
        navigateBack()
    }

    Column {
        AppWebViewTopBar(
            state = state,
            navigator = navigator,
            refresh = { navigator.reload() },
            isAppDomain = ::isAppDomain,
            navigateBack = ::navigateBack,
        )

        WebView(
            state = state,
            modifier = Modifier.fillMaxSize(),
            navigator = navigator,
            webViewJsBridge = jsBridge,
            captureBackPresses = true,
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppWebViewTopBar(
    state: WebViewState,
    navigator: WebViewNavigator,
    refresh: () -> Unit,
    isAppDomain: () -> Boolean,
    navigateBack: () -> Unit,
    isPreview: Boolean = false,
) {
    Box {
        val itemsHeight = 35.dp

        TopAppBar(
            expandedHeight = 46.dp,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth().height(itemsHeight),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionContainer(modifier = Modifier.padding(start = 20.dp)) {
                        if (isPreview) {
                            TextSm("Fredica", maxLines = 1, fontWeight = FontWeight.Bold)
                        } else {
                            state.pageTitle.let { pageTitle ->
                                if (pageTitle != null) {
                                    TextSm(
                                        text = "${
                                            if (isAppDomain()) "" else "⚠ 非APP网站 - "
                                        }$pageTitle",
                                        maxLines = 1,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                    state.lastLoadedUrl?.let {
                        SelectionContainer(modifier = Modifier.padding(start = 10.dp)) {
                            TextXsGray(it, maxLines = 1)
                        }
                    }
                }
            },
            navigationIcon = {
                Row(
                    modifier = Modifier.height(itemsHeight),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { navigateBack() }, enabled = navigator.canGoBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                        )
                    }
                }
            },
        )

        val loadingState = state.loadingState
        if (loadingState is LoadingState.Loading) {
            LinearProgressIndicator(
                progress = {
                    loadingState.progress
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview
@Composable
private fun AppWebViewTopBarPreview() {
    val initUrl = "https://baidu.com"
    val state = rememberWebViewState(initUrl)
    AppWebViewTopBar(
        isPreview = true,
        state = state,
        navigator = rememberWebViewNavigator(),
        refresh = {},
        isAppDomain = { false },
        navigateBack = {},
    )
}