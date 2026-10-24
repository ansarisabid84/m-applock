package com.sabid.applock.ui.browser

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Private browser.
 *
 * Incognito here means it in the literal sense: no history stack persisted, no cache,
 * no cookies at exit, no form data, no third-party cookies. It is the one part of the
 * app that touches the network, and it does so only for pages the user types in.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onBack: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                clearHistory(); clearCache(true); clearFormData()
                CookieManager.getInstance().removeAllCookies(null)
                destroy()
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("Search or enter address") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = {
                    val target = when {
                        url.startsWith("http://") || url.startsWith("https://") -> url
                        url.contains(".") && !url.contains(" ") -> "https://$url"
                        else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(url, "UTF-8")
                    }
                    webView?.loadUrl(target)
                }),
                modifier = Modifier.weight(1f)
            )
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        savePassword = false
                        saveFormData = false
                        databaseEnabled = false
                        setGeolocationEnabled(false)
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, u: String?, f: android.graphics.Bitmap?) { loading = true }
                        override fun onPageFinished(v: WebView?, u: String?) { loading = false; clearHistory() }
                    }
                    webChromeClient = WebChromeClient()
                }
            }
        )
    }
}
