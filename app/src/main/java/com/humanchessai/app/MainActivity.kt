package com.humanchessai.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

    /**
     * DroidFish-style compact chrome. Full screen remains board-first, while
     * short Android split-screen panes shrink the surrounding controls but keep
     * the board large enough to be useful. Maia output remains visible below.
     */
    private val compactDroidFishUiPatch = """
        (function () {
          if (document.getElementById('humanchess-compact-droidfish-ui')) return;
          var style = document.createElement('style');
          style.id = 'humanchess-compact-droidfish-ui';
          style.textContent = `
            .topbar {
              height: 36px !important;
              grid-template-columns: 30px 1fr auto 1fr !important;
              padding: 0 4px !important;
              gap: 2px !important;
            }
            .hamb {
              width: 28px !important;
              height: 28px !important;
              font-size: 21px !important;
              padding: 0 !important;
            }
            .player {
              font-size: 11px !important;
              font-weight: 700 !important;
            }
            .title {
              font-size: 13px !important;
              font-weight: 800 !important;
            }
            .title small {
              font-size: 8px !important;
              margin-top: 1px !important;
            }

            .moves {
              height: 24px !important;
              padding: 3px 4px !important;
              font: 11px/18px monospace !important;
            }

            .tools {
              height: 39px !important;
              gap: 3px !important;
              padding: 2px 4px !important;
            }
            .tool {
              width: 34px !important;
              height: 34px !important;
              min-width: 34px !important;
              border-radius: 6px !important;
              font-size: 19px !important;
              box-shadow: 0 1px 2px #0005 !important;
              padding: 0 !important;
            }
            .tool.nav { font-size: 25px !important; }

            .analysis {
              min-height: 150px !important;
              padding: 2px 4px 12px !important;
              font: 11px/1.32 monospace !important;
            }
            .status { min-height: 16px !important; }
            .pv { margin: 3px 0 !important; }
            .pct { min-width: 43px !important; font-size: 11px !important; }
            .wdl { margin-top: 4px !important; font-size: 11px !important; }

            .navDrawer { width: min(72vw, 330px) !important; }
            .navItem { padding: 14px 16px !important; font-size: 16px !important; }
            .dialog { width: min(90vw, 420px) !important; border-radius: 16px !important; padding: 14px !important; }
            .dialog h2 { font-size: 18px !important; margin: 1px 0 10px !important; }
            .dialog h3 { font-size: 11px !important; margin: 12px 0 6px !important; }
            .dialog .action { padding: 11px 6px !important; font-size: 15px !important; }
            .dialog .closeBtn { margin-top: 10px !important; padding: 9px !important; font-size: 14px !important; }
            .choice small, .helpText { font-size: 11px !important; }

            /* Split-screen: keep the compact DroidFish chrome, but keep the
               chessboard around 80% of the pane width instead of collapsing it
               to half-width. */
            @media (max-height: 1150px) {
              html, body { overflow-y: hidden !important; }
              .app {
                min-height: 100dvh !important;
                height: 100dvh !important;
                overflow: hidden !important;
              }

              .topbar {
                height: 30px !important;
                grid-template-columns: 25px 1fr auto 1fr !important;
                padding: 0 2px !important;
                gap: 1px !important;
              }
              .hamb { width: 23px !important; height: 23px !important; font-size: 17px !important; }
              .player { font-size: 9px !important; }
              .title { font-size: 10px !important; }
              .title small { font-size: 6.5px !important; margin-top: 0 !important; }

              .boardWrap {
                width: min(82vw, calc(100dvh - 190px)) !important;
                max-width: 100% !important;
                aspect-ratio: 1 !important;
                margin: 0 auto !important;
              }
              .coord { font-size: 8px !important; }

              .moves {
                height: 19px !important;
                padding: 2px 3px !important;
                font: 9.5px/15px monospace !important;
              }

              .tools {
                height: 34px !important;
                gap: 2px !important;
                padding: 1px 4px !important;
              }
              .tool {
                width: 30px !important;
                height: 30px !important;
                min-width: 30px !important;
                border-radius: 5px !important;
                font-size: 16px !important;
              }
              .tool.nav { font-size: 20px !important; }

              .analysis {
                min-height: 0 !important;
                height: calc(100dvh - 83px - min(82vw, calc(100dvh - 190px))) !important;
                overflow-y: auto !important;
                padding: 1px 4px 5px !important;
                font: 9.5px/1.23 monospace !important;
              }
              .status { min-height: 13px !important; }
              .pv { margin: 1px 0 !important; }
              .pct { min-width: 38px !important; font-size: 9.5px !important; }
              .wdl { margin-top: 2px !important; font-size: 9.5px !important; }
            }

            @media (max-height: 900px) {
              .topbar { height: 27px !important; }
              .hamb { width: 21px !important; height: 21px !important; font-size: 15px !important; }
              .player { font-size: 8px !important; }
              .title { font-size: 9px !important; }
              .title small { display: none !important; }

              .boardWrap { width: min(78vw, calc(100dvh - 185px)) !important; }
              .moves { height: 18px !important; font-size: 9px !important; }
              .tools { height: 31px !important; }
              .tool { width: 27px !important; height: 27px !important; min-width: 27px !important; font-size: 14px !important; }
              .tool.nav { font-size: 18px !important; }
              .analysis {
                height: calc(100dvh - 76px - min(78vw, calc(100dvh - 185px))) !important;
                font-size: 9px !important;
                line-height: 1.18 !important;
              }
            }
          `;
          document.head.appendChild(style);
        })();
    """.trimIndent()

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
        webView.settings.setSupportZoom(false)
        webView.setBackgroundColor(android.graphics.Color.rgb(14, 15, 20))
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript(compactDroidFishUiPatch, null)
            }
        }
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

    @JavascriptInterface
    fun copyText(text: String) {
        activity.runOnUiThread {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("HumanChessAI", text))
        }
    }

    @JavascriptInterface
    fun clipboardText(): String {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(activity)?.toString() ?: ""
    }

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
