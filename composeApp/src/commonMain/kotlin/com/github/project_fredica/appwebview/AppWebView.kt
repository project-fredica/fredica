package com.github.project_fredica.appwebview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.components.BackHandler2
import com.multiplatform.webview.jsbridge.IJsMessageHandler
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.jsbridge.rememberWebViewJsBridge
import com.multiplatform.webview.util.KLogSeverity
import com.multiplatform.webview.web.LoadingState
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.WebViewNavigator
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppWebView() {
    val logger = createLogger()

    val appHosts = listOf(
        "http://localhost:${FredicaApi.DEFAULT_DEV_WEBUI_PORT}"
    )

    val jsBridge = rememberWebViewJsBridge()

    LaunchedEffect(jsBridge) {
        jsBridge.register(GreetJsMessageHandler())
    }

    val state = rememberWebViewState(url = appHosts[0])
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

    fun navigateBack() {
        navigator.navigateBack()
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

    BackHandler2 {
        navigateBack()
    }

    Column {
        if (navigator.canGoBack || !isAppDomain()) {
            TopAppBar(
                title = {
                    state.pageTitle.let { pageTitle ->
                        if (pageTitle != null) {
                            if (!isAppDomain()) {
                                Text(text = "您已进入非APP内网站 - $pageTitle")
                            } else {
                                Text(text = pageTitle)
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (navigator.canGoBack) {
                        IconButton(onClick = { navigateBack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
            )
        }

        if (!isAppDomain()) {
            Row {
                Box(modifier = Modifier.weight(1f)) {
                    if (state.errorsForCurrentRequest.isNotEmpty()) {
                        Image(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Error",
                            colorFilter = ColorFilter.tint(Color.Red),
                            modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp),
                        )
                    }

                    OutlinedTextField(
                        value = urlBarValue ?: "",
                        onValueChange = { urlBarValue = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Button(
                    onClick = {
                        urlBarValue?.let {
                            logger.info("loadUrl : $it")
                            navigator.loadUrl(it)
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterVertically).padding(start = 10.dp),
                ) {
                    Text("Go")
                }
            }
        }

        val loadingState = state.loadingState
        if (loadingState is LoadingState.Loading) {
            LinearProgressIndicator(
                progress = {
                    loadingState.progress
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        WebView(
            state = state,
            modifier = Modifier.fillMaxSize(),
            navigator = navigator,
        )
    }
}

class GreetJsMessageHandler : IJsMessageHandler {
    val logger = createLogger()

    override fun methodName(): String {
        return "Greet"
    }

    override fun handle(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        logger.debug("Greet Handler Get Message: $message")
        callback("Hello from : $message")
    }
}