package com.humanchessai.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var controller: HumanChessController
    private lateinit var bridge: NativeBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.rgb(14, 15, 20)
        window.navigationBarColor = android.graphics.Color.rgb(14, 15, 20)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = false
        webView.settings.mediaPlaybackRequiresUserGesture = true
        webView.setBackgroundColor(android.graphics.Color.rgb(14, 15, 20))
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        controller = HumanChessController(applicationContext) {
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onNativeModelReady && window.onNativeModelReady();",
                    null,
                )
            }
        }
        bridge = NativeBridge(this, webView, controller)
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onBackPressed() {
        webView.evaluateJavascript(
            "window.nativeBackPressed ? window.nativeBackPressed() : false;"
        ) { handled ->
            if (handled != "true") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        bridge.close()
        controller.close()
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }
}

private class NativeBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val controller: HumanChessController,
) {
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val aiExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    @JavascriptInterface
    fun request(id: String, path: String, bodyJson: String?) {
        val executor = if (
            path == "/api/predictions" ||
            path == "/api/pv" ||
            path == "/api/ai-move" ||
            path == "/api/line"
        ) aiExecutor else controlExecutor

        executor.execute {
            val response = try {
                val body = if (bodyJson.isNullOrBlank()) JSONObject() else JSONObject(bodyJson)
                controller.handle(path, body)
            } catch (t: Throwable) {
                NativeResponse(500, JSONObject().put("error", t.message ?: t.javaClass.simpleName))
            }
            complete(id, response)
        }
    }

    @JavascriptInterface
    fun platform(): String = "android-native"

    private fun complete(id: String, response: NativeResponse) {
        val idJs = JSONObject.quote(id)
        val payloadJs = JSONObject.quote(response.body.toString())
        val script = "window.__nativeComplete && window.__nativeComplete($idJs, ${response.status}, $payloadJs);"
        activity.runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    fun close() {
        controlExecutor.shutdownNow()
        aiExecutor.shutdownNow()
    }
}
