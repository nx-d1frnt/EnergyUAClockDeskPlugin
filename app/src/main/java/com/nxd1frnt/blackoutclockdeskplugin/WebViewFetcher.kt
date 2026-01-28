package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebViewFetcher(private val context: Context) {

    suspend fun fetchHtml(url: String): String = suspendCancellableCoroutine { continuation ->
        // Використовуємо Handler для запуску в Main Thread
        Handler(Looper.getMainLooper()).post {
            // Змінна для зберігання посилання на webView, щоб знищити його потім
            var webView: WebView? = null

            try {
                // Використовуємо applicationContext, щоб уникнути витоків пам'яті
                webView = WebView(context.applicationContext)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // Маскуємось під звичайний Android
                    userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                // ВАЖЛИВО: Програмний рендеринг стабільніший у фонових процесах
                webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Даємо Cloudflare 4 секунди на роздуми
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (continuation.isActive) {
                                view?.evaluateJavascript(
                                    "(function() { return '<html>'+document.getElementsByTagName('html')[0].innerHTML+'</html>'; })();"
                                ) { html ->
                                    try {
                                        val cleanHtml = html?.replace("\\u003C", "<")
                                            ?.replace("\\\"", "\"")
                                            ?.trim('"') ?: ""

                                        if (continuation.isActive) {
                                            continuation.resume(cleanHtml)
                                        }
                                    } catch (e: Exception) {
                                        if (continuation.isActive) continuation.resumeWithException(e)
                                    } finally {
                                        // Очищення
                                        webView?.destroy()
                                    }
                                }
                            }
                        }, 4000)
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        Log.e("WebViewFetcher", "Error: $description")
                        // Не перериваємо, бо іноді помилки (наприклад favicon) не критичні
                    }

                    // КРИТИЧНО: Обробка падіння процесу рендерингу (ваш випадок)
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        Log.e("WebViewFetcher", "WebView Render Process Crashed!")
                        if (continuation.isActive) {
                            continuation.resumeWithException(RuntimeException("WebView Render Process Crashed"))
                        }
                        webView?.destroy()
                        return true // Process handled
                    }
                }

                webView.loadUrl(url)

            } catch (e: Exception) {
                Log.e("WebViewFetcher", "Setup Error: ${e.message}")
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
                webView?.destroy()
            }
        }
    }
}