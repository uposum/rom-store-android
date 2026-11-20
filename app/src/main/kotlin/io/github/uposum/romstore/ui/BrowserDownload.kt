package io.github.uposum.romstore.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** What the in-app browser is opened for: a game's file on a site's page. */
data class BrowserRequest(
    val title: String,
    val system: String,
    val source: String,
    val cover: String?,
    val pageUrl: String,
    /** A failed download this replaces once the browser hands the file over. */
    val replacesJob: String? = null,
    /** The game page ([AppViewModel.gameKey]) the download belongs to, if any. */
    val gameKey: String? = null,
)

private val GAME_FILE = Regex(
    """\.(zip|7z|rar|iso|chd|rvz|wbfs|wua|nsp|nsz|xci|xcz|cso|pbp|cia|3ds|nds|gba|gbc|gb|nes|sfc|smc|n64|z64|v64|md|bin|cue|gdi|cdi|pkg|vpk|gcm|m3u)$""",
    RegexOption.IGNORE_CASE,
)

/** A link to a game file (by its path; the query is a signature). */
fun isGameFile(url: String): Boolean {
    val path = runCatching { java.net.URI(url.replace(" ", "%20")).rawPath }.getOrNull() ?: url.substringBefore('?')
    return GAME_FILE.containsMatchIn(path)
}

/** A file the browser started downloading, handed over to the app's downloader. */
data class BrowserFile(
    val url: String,
    val userAgent: String,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
    val referer: String?,
)

/**
 * The site's own page in a browser view, for links that need one: a
 * Cloudflare check, a countdown and button, a file host's download page. The
 * user goes through the page themselves; as soon as the site hands out the
 * file, the download is taken over and saved like any other.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserDownload(request: BrowserRequest, onFile: (BrowserFile) -> Unit, onClose: () -> Unit) = key(request) {
    // Keyed on the request: a new one starts with a fresh page and state.
    var webView by remember { mutableStateOf<WebView?>(null) }
    var host by remember { mutableStateOf(Uri.parse(request.pageUrl).host ?: "") }
    var progress by remember { mutableFloatStateOf(0f) }
    var handedOver by remember { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }
    DisposableEffect(Unit) {
        onDispose { webView?.apply { stopLoading(); destroy() } }
    }
    // The browser is the top layer: focus moves into it, onto Close (down goes into the page),
    // so nothing under it (the game page's Download button) keeps taking A.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().background(C.bg)
            // Touches stop here: nothing under the browser (the top bar, the game page) gets them.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .imePadding()
            .trapFocus(),
    ) {
        Row(
            Modifier.fillMaxWidth().background(C.bar).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Get ${request.title}", style = T.h3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (handedOver) "Downloading in ROM Store…" else "$host · go through the page; the download is saved to your ${io.github.uposum.romstore.core.model.Systems.label(request.system)} folder",
                    style = T.small, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            // A real browser always works: it downloads to the device's Downloads folder.
            val context = androidx.compose.ui.platform.LocalContext.current
            Btn("Open in browser", {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(webView?.url ?: request.pageUrl))) }
                    .onFailure { android.widget.Toast.makeText(context, "No browser installed", android.widget.Toast.LENGTH_SHORT).show() }
            }, kind = ButtonKind.Ghost)
            Spacer(Modifier.width(8.dp))
            IconBtn(Icons.Rounded.Refresh, "Reload", { webView?.reload() }, size = 36.dp)
            Spacer(Modifier.width(8.dp))
            IconBtn(Icons.Rounded.Close, "Close", onClose, Modifier.focusRequester(closeFocus), size = 36.dp)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.surface)) {
            if (progress in 0.01f..0.99f) Box(Modifier.fillMaxWidth(progress).height(2.dp).background(C.accent))
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    // Pop-ups without a click are refused (onCreateWindow): on these sites
                    // the page itself opens ad windows, which would otherwise replace it.
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(true)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    val ui = android.os.Handler(android.os.Looper.getMainLooper())
                    val agent = settings.userAgentString
                    val main = this
                    /** Hand a file request over to the app before the browser spends the link (one-time links). */
                    fun takeOver(url: String, referer: String?): Boolean {
                        if (handedOver || !isGameFile(url)) return false
                        handedOver = true
                        ui.post { onFile(BrowserFile(url, agent, null, null, -1, referer)) }
                        return true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                            // The file itself: the app downloads it.
                            if (takeOver(req.url.toString(), view.url)) return true
                            // Web pages load here; app links and store links (ad redirects) do not.
                            val scheme = req.url.scheme?.lowercase()
                            return scheme != "http" && scheme != "https"
                        }

                        // Pages that start the download on their own do it in a hidden frame,
                        // which a WebView drops; the request is caught here instead.
                        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): android.webkit.WebResourceResponse? {
                            val url = req.url.toString()
                            // The very first load of a file link is the site's own check page:
                            // let it run until the check has set its cookie.
                            val first = url == request.pageUrl && CookieManager.getInstance().getCookie(url)?.contains("cf_clearance") != true
                            if (!first && req.method.equals("GET", true) && isGameFile(url)) {
                                val referer = req.requestHeaders["Referer"]
                                if (takeOver(url, referer) || handedOver) {
                                    return android.webkit.WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                                }
                            }
                            return null
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            host = Uri.parse(url).host ?: host
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress / 100f
                        }

                        /**
                         * A click that opens a new window (target=_blank, window.open: a file
                         * host's download button) opens it here instead: a throwaway view takes
                         * the window, and its first address is the file (taken over) or a page
                         * loaded in this view (B goes back). Pop-ups without a click are refused.
                         */
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                            if (!isUserGesture || resultMsg == null) return false
                            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                            val popup = WebView(view.context)
                            var grabbed = false
                            fun grab(url: String?) {
                                if (grabbed || url.isNullOrBlank() || url == "about:blank") return
                                grabbed = true
                                ui.post { popup.stopLoading(); popup.destroy() }
                                if (takeOver(url, main.url)) return
                                val scheme = Uri.parse(url).scheme?.lowercase()
                                if (scheme == "http" || scheme == "https") ui.post { main.loadUrl(url) }
                            }
                            popup.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                                    grab(req.url.toString())
                                    return true
                                }

                                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) = grab(url)
                            }
                            popup.setDownloadListener { url, _, _, _, _ -> grab(url) }
                            transport.webView = popup
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                        if (handedOver) return@setDownloadListener
                        handedOver = true
                        onFile(BrowserFile(url, userAgent, contentDisposition, mimeType, contentLength, this.url))
                    }
                    loadUrl(request.pageUrl)
                    webView = this
                }
            },
        )
    }
}
