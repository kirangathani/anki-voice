package dev.kiran.ankivoice.math

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

/**
 * Displays Anki card HTML (which may contain MathJax LaTeX blocks) in a
 * WebView. MathJax is loaded inline; rendering happens client-side.
 *
 * Each instance owns its own WebView and reloads on [html] change.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
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
 * Wraps the card's raw HTML in a minimal page that loads MathJax with the
 * delimiter set Anki uses. `$$...$$` is enabled for display math so user
 * cards using that shortcut render too.
 */
private fun wrapMathHtml(content: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  body { font-family: -apple-system, sans-serif; font-size: 17px; line-height: 1.45;
         margin: 0; padding: 12px; color: #1c1c1c; background: transparent; }
  mjx-container { color: inherit; }
</style>
<script>
window.MathJax = {
    tex: {
        inlineMath: [['\\(', '\\)']],
        displayMath: [['\\[', '\\]'], ['$$', '$$']],
        processEscapes: true
    },
    options: { enableMenu: false }
};
</script>
<script src="/assets/math/tex-chtml.js"></script>
</head>
<body>$content</body>
</html>
""".trimIndent()
