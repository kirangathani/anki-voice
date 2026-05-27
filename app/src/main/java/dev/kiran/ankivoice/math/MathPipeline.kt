package dev.kiran.ankivoice.math

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

class MathPipelineError(message: String) : RuntimeException(message)

/**
 * Hidden WebView that converts card HTML (with embedded LaTeX) into plain
 * speech text via MathJax (tex → mathml) + SRE (mathml → ClearSpeak).
 *
 * Stateful, expensive to construct: hold one instance per app process.
 * Safe to call [extractSpeech] concurrently — calls are serialised internally.
 *
 * Threading: WebView ops happen on Main. Callers can be on any dispatcher.
 */
@SuppressLint("SetJavaScriptEnabled")
class MathPipeline(context: Context) {

    private val appContext: Context = context.applicationContext
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var webView: WebView? = null

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() { ready.complete(Unit) }

        @JavascriptInterface
        fun onError(message: String) {
            ready.completeExceptionally(MathPipelineError(message))
        }
    }

    /**
     * Loads the JS shim, MathJax, and SRE; resolves when they're all initialised.
     * Idempotent — subsequent calls return immediately.
     */
    suspend fun warmUp(): Unit = withContext(Dispatchers.Main) {
        if (webView == null) {
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
                .build()

            webView = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
                }
                addJavascriptInterface(Bridge(), "AndroidBridge")
                loadUrl("https://appassets.androidplatform.net/assets/math/pipeline.html")
            }
        }
        ready.await()
    }

    /**
     * Replaces every LaTeX block in [rawHtml] with its ClearSpeak text and
     * strips other HTML. Result is plain-text suitable for TTS.
     */
    suspend fun extractSpeech(rawHtml: String): String = mutex.withLock {
        warmUp()
        withContext(Dispatchers.Main) {
            val wv = webView ?: throw MathPipelineError("webview not initialised")
            val jsArg = JSONObject.quote(rawHtml)
            val raw = evalJs(wv, "extractSpeech($jsArg)")
            // evaluateJavascript returns the JS result JSON-encoded. Our JS
            // returns a JSON-encoded string, so we unwrap twice.
            val inner = JSONTokener(raw).nextValue() as? String
                ?: throw MathPipelineError("unexpected JS result: $raw")
            val obj = JSONObject(inner)
            if (obj.has("error")) throw MathPipelineError(obj.getString("error"))
            obj.getString("speechText")
        }
    }

    private suspend fun evalJs(wv: WebView, js: String): String =
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result -> cont.resume(result ?: "null") }
        }
}
