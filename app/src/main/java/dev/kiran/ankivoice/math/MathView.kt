package dev.kiran.ankivoice.math

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

/**
 * Displays Anki card HTML (possibly containing MathJax LaTeX) in a WebView.
 *
 * Auto-sizes height to content: a ResizeObserver in the page posts the body's
 * scrollHeight back through [SizeBridge], which updates a Compose state so
 * the AndroidView modifier re-applies the right height.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathView(html: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // Start with a small placeholder height; replaced as soon as JS reports the
    // real content height.
    var measuredHeight by remember(html) { mutableStateOf(80.dp) }

    AndroidView(
        modifier = modifier.height(measuredHeight),
        factory = { ctx ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()

            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
                }
                addJavascriptInterface(
                    SizeBridge { pxHeight ->
                        val dp = with(density) { pxHeight.toDp() }
                        measuredHeight = dp
                    },
                    "AndroidSizeBridge",
                )
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                "https://appassets.androidplatform.net/",
                wrapMathHtml(html),
                "text/html",
                "utf-8",
                null,
            )
        },
    )
}

/**
 * JS-side `AndroidSizeBridge.setHeight(px)` posts back here. The call lands on
 * a binder thread; we hop to main before updating Compose state.
 */
private class SizeBridge(private val onHeightPx: (Int) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun setHeight(pxHeight: Int) {
        main.post { onHeightPx(pxHeight) }
    }
}

/**
 * Wraps the card's raw HTML in a MathJax-loading page that also reports its
 * body's scrollHeight on every size change.
 */
private fun wrapMathHtml(content: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body { margin: 0; padding: 0; }
  body { font-family: -apple-system, sans-serif; font-size: 17px; line-height: 1.45;
         padding: 8px 4px; color: #1c1c1c; background: transparent; }
  mjx-container { color: inherit; }
</style>
<script>
window.MathJax = {
    tex: {
        inlineMath: [['\\(', '\\)']],
        displayMath: [['\\[', '\\]'], ['$$', '$$']],
        processEscapes: true
    },
    options: { enableMenu: false },
    startup: {
        pageReady: function() {
            return MathJax.startup.defaultPageReady().then(reportHeight);
        }
    }
};

function reportHeight() {
    if (window.AndroidSizeBridge) {
        AndroidSizeBridge.setHeight(document.body.scrollHeight);
    }
}

// Re-report on any subsequent layout change (image loads, font swaps, etc.)
window.addEventListener('load', function() {
    if (typeof ResizeObserver === 'function') {
        new ResizeObserver(reportHeight).observe(document.body);
    } else {
        setTimeout(reportHeight, 100);
        setTimeout(reportHeight, 500);
    }
});
</script>
<script src="/assets/math/tex-chtml.js"></script>
</head>
<body>$content</body>
</html>
""".trimIndent()
