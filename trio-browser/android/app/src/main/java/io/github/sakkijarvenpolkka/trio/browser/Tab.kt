package io.github.sakkijarvenpolkka.trio.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** One browser tab: its WebView plus the state the UI shows. */
class Tab(val id: Long, val webView: WebView) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var favicon by mutableStateOf<Bitmap?>(null)
    var error by mutableStateOf<String?>(null)
    var crashed by mutableStateOf(false)

    /** Transition to record for the next committed navigation ("typed", "search", …). */
    var pendingTransition: String? = null
    var visitId: Long? = null
    var visitAccum = 0L
    var activeSince: Long? = null

    val loading: Boolean get() = progress in 0..99

    fun refreshNav() {
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    companion object {
        /**
         * The engine's own user agent without the WebView markers ("; wv", "Version/4.0", build
         * id), so sites see an ordinary Chrome for Android user agent for this Chromium version.
         */
        fun cleanUserAgent(default: String): String = default
            .replace("; wv)", ")")
            .replace(Regex(" Build/[^;)]+"), "")
            .replace("Version/4.0 ", "")

        /** Settings shared by tab and agent WebViews. */
        @SuppressLint("SetJavaScriptEnabled")
        fun configure(context: Context, webView: WebView) {
            val s = webView.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.databaseEnabled = true
            s.setSupportMultipleWindows(true)
            s.javaScriptCanOpenWindowsAutomatically = false
            s.builtInZoomControls = true
            s.displayZoomControls = false
            s.loadWithOverviewMode = true
            s.useWideViewPort = true
            s.mediaPlaybackRequiresUserGesture = true
            s.allowFileAccess = false
            s.allowContentAccess = false
            s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            s.userAgentString = cleanUserAgent(WebSettings.getDefaultUserAgent(context))
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
    }
}
