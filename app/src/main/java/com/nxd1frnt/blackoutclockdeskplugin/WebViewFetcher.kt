package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebViewFetcher(private val context: Context) {

    suspend fun fetchHtml(url: String): String = suspendCancellableCoroutine { continuation ->
        val isCompleted = AtomicBoolean(false)

        Handler(Looper.getMainLooper()).post {
            var webView: WebView? = null

            fun destroyWebView(targetView: WebView? = webView) {
                try {
                    targetView?.apply {
                        stopLoading()
                        clearHistory()
                        clearCache(true)
                        loadUrl("about:blank")
                        destroy()
                    }
                    webView = null
                    WebStorage.getInstance().deleteAllData()
                    CoroutineScope(Dispatchers.IO).launch {
                        cleanWebViewCache(context)
                    }
                } catch (e: Exception) {
                    Log.e("WebViewFetcher", "Error destroying webview", e)
                }
            }

            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post {
                    destroyWebView()
                }
            }

            try {
                val createdWebView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
                webView = createdWebView

                createdWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!isCompleted.get() && continuation.isActive) {
                                view?.evaluateJavascript(
                                    "(function() { return '<html>'+document.getElementsByTagName('html')[0].innerHTML+'</html>'; })();"
                                ) { html ->
                                    try {
                                        val cleanHtml = html?.replace("\\u003C", "<")
                                            ?.replace("\\\"", "\"")
                                            ?.trim('"') ?: ""

                                        if (isCompleted.compareAndSet(false, true)) {
                                            continuation.resume(cleanHtml)
                                        }
                                    } catch (e: Exception) {
                                        if (isCompleted.compareAndSet(false, true)) {
                                            continuation.resumeWithException(e)
                                        }
                                    } finally {
                                        destroyWebView(view)
                                    }
                                }
                            } else {
                                destroyWebView(view)
                            }
                        }, 2500)
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        Log.e("WebViewFetcher", "Error: $description")
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        Log.e("WebViewFetcher", "WebView Render Process Crashed!")
                        if (isCompleted.compareAndSet(false, true)) {
                            continuation.resumeWithException(RuntimeException("WebView Render Process Crashed"))
                        }
                        destroyWebView(view)
                        return true
                    }
                }

                createdWebView.loadUrl(url)

            } catch (e: Exception) {
                Log.e("WebViewFetcher", "Setup Error: ${e.message}")
                if (isCompleted.compareAndSet(false, true)) {
                    continuation.resumeWithException(e)
                }
                destroyWebView()
            }
        }
    }

    companion object {
        fun cleanWebViewCache(context: Context) {
            try {
                val webViewCacheDir = File(context.cacheDir, "WebView")
                if (webViewCacheDir.exists()) {
                    deleteDir(webViewCacheDir)
                }
                val orgChromiumDir = File(context.cacheDir, "org.chromium.android_webview")
                if (orgChromiumDir.exists()) {
                    deleteDir(orgChromiumDir)
                }
            } catch (e: Exception) {
                Log.e("WebViewFetcher", "Failed to clean webview cache dir", e)
            }
        }

        private fun deleteDir(dir: File?): Boolean {
            if (dir != null && dir.isDirectory) {
                val children = dir.listFiles()
                if (children != null) {
                    for (child in children) {
                        deleteDir(child)
                    }
                }
                return dir.delete()
            } else if (dir != null && dir.isFile) {
                return dir.delete()
            }
            return false
        }
    }
}