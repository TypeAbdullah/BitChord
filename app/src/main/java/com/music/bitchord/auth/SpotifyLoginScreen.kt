package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private val WEBVIEW_VERSION_TOKEN = Regex("""Version/\d+(\.\d+)*\s*""")

private const val FALLBACK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

/**
 * Strips the WebView-specific tokens so Spotify serves the full modern login form
 * rather than a blank or blocked fallback page.
 */
private fun browserUserAgent(platformUserAgent: String?): String {
    val stripped = platformUserAgent
        ?.replace("; wv", "")
        ?.replace(WEBVIEW_VERSION_TOKEN, "")
        ?.replace("  ", " ")
        ?.trim()
    return stripped?.takeIf { it.contains("Chrome/") } ?: FALLBACK_USER_AGENT
}

/**
 * In-app Spotify web login screen.
 *
 * Loads Spotify's official accounts login page in an un-sandboxed Chrome Mobile viewport.
 * When the login completes and Spotify redirects to open.spotify.com or sets the session cookie,
 * the CookieManager captures the `sp_dc` session cookie and hands it to [onCookiesCaptured].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(
    onCookiesCaptured: (spdcToken: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.userAgentString = browserUserAgent(settings.userAgentString)

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                var captured = false

                val checkCookies: () -> Unit = {
                    if (!captured) {
                        val cookieStr = cookieManager.getCookie("https://open.spotify.com").orEmpty() + "; " +
                            cookieManager.getCookie("https://spotify.com").orEmpty() + "; " +
                            cookieManager.getCookie("https://accounts.spotify.com").orEmpty()
                        val match = Regex("""sp_dc=([^;]+)""").find(cookieStr)?.groupValues?.get(1)?.trim()
                        if (!match.isNullOrBlank()) {
                            captured = true
                            onCookiesCaptured(match)
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        canGoBack = view?.canGoBack() == true
                        checkCookies()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        canGoBack = view?.canGoBack() == true
                        checkCookies()
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        canGoBack = view?.canGoBack() == true
                        checkCookies()
                    }
                }

                loadUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                webView = this
            }
        },
    )
}
