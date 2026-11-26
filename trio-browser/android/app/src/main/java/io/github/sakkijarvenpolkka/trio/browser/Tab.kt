package io.github.sakkijarvenpolkka.trio.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One browser tab: its WebView plus the state the UI shows. */
class Tab(val id: Long, val webView: WebView) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var favicon by mutableStateOf<Bitmap?>(null)
    var error by mutableStateOf<String?>(null)
    /** The address that failed with [error]. */
    var errorUrl: String? = null

    /** A main-frame address whose certificate was refused; shown as an error if that load ends without a page. */
    var sslFailedUrl: String? = null
    var crashed by mutableStateOf(false)

    /** Transition to record for the next committed navigation ("typed", "search", …). */
    var pendingTransition: String? = null

    /** A restored or crashed tab loads its page only when it is shown. */
    var deferredUrl: String? = null

    /** The main-frame address being loaded (for telling page errors from errors of a page's parts). */
    var mainUrl: String? = null
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
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
    }
}

/** Takes the view off the screen and destroys it. */
fun WebView.dispose() {
    (parent as? ViewGroup)?.removeView(this)
    destroy()
}
