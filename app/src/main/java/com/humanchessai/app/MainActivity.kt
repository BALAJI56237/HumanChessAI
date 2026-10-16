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
     * Mirror DroidFish's real portrait layout instead of guessing with
     * split-screen media queries.
     *
     * DroidFish main.xml orders the screen as:
     * title -> chessboard -> status + six 36x32dp buttons -> move/analysis area.
     * ChessBoardPlay caps the board at 75% of available height in portrait and
     * 65% of available width in landscape. Text is 12sp and title text 14sp.
     *
     * The DOM is rearranged at runtime to follow that same structure while all
     * chess logic and Maia ONNX inference stay unchanged.
     */
    private val droidFishExactLayoutPatch = """
        (function () {
          if (window.__humanChessDroidFishExactLayout) return;
          window.__humanChessDroidFishExactLayout = true;

          var app = document.querySelector('.app');
          var topbar = document.querySelector('.topbar');
          var boardWrap = document.querySelector('.boardWrap');
          var moves = document.getElementById('moves');
          var tools = document.querySelector('.tools');
          var analysis = document.querySelector('.analysis');
          var status = document.getElementById('status');
          if (!app || !topbar || !boardWrap || !moves || !tools || !analysis || !status) return;

          // DroidFish puts current-position status and buttons on the SAME row.
          var row = document.getElementById('dfStatusRow');
          if (!row) {
            row = document.createElement('div');
            row.id = 'dfStatusRow';
            boardWrap.insertAdjacentElement('afterend', row);
          }
          row.appendChild(status);
          row.appendChild(tools);

          // DroidFish keeps move/analysis text in the remaining area below.
          analysis.insertBefore(moves, analysis.firstChild);

          var style = document.createElement('style');
          style.id = 'humanchess-droidfish-exact-css';
          style.textContent = `
            html, body {
              width: 100% !important;
              height: 100% !important;
              margin: 0 !important;
              overflow: hidden !important;
            }
            .app {
              width: 100% !important;
              height: 100dvh !important;
              min-height: 0 !important;
              display: flex !important;
              flex-direction: column !important;
              overflow: hidden !important;
            }

            /* DroidFish title.xml uses 14sp title/player text. */
            .topbar {
              flex: 0 0 auto !important;
              height: 36px !important;
              min-height: 36px !important;
              grid-template-columns: 28px 1fr 1fr 1fr !important;
              align-items: center !important;
              padding: 0 2px 2px !important;
              gap: 1px !important;
            }
            .hamb {
              width: 26px !important;
              height: 30px !important;
              padding: 0 !important;
              border: 0 !important;
              font-size: 20px !important;
              line-height: 1 !important;
            }
            .player,
            .title {
              font-size: 14px !important;
              line-height: 16px !important;
              font-weight: 600 !important;
            }
            .title { font-weight: 700 !important; }
            .title small {
              display: block !important;
              margin: 0 !important;
              font-size: 8px !important;
              line-height: 8px !important;
              font-weight: 400 !important;
            }

            /* Board dimension is assigned by layoutLikeDroidFish(). */
            .boardWrap {
              flex: 0 0 auto !important;
              align-self: center !important;
              margin: 0 auto !important;
              aspect-ratio: 1 / 1 !important;
              overflow: hidden !important;
            }
            .coord { font-size: 9px !important; }

            /* main.xml: status has weight=1, then six 36x32dp buttons. */
            #dfStatusRow {
              flex: 0 0 36px !important;
              width: 100% !important;
              height: 36px !important;
              display: flex !important;
              align-items: flex-start !important;
              padding: 1px 6px 3px 1px !important;
              margin: 0 !important;
              overflow: hidden !important;
            }
            #dfStatusRow .status {
              flex: 1 1 auto !important;
              min-width: 0 !important;
              min-height: 0 !important;
              margin: 0 !important;
              padding: 2px 2px 0 0 !important;
              color: var(--move) !important;
              font: 12px/15px monospace !important;
              white-space: nowrap !important;
              overflow: hidden !important;
              text-overflow: ellipsis !important;
            }
            .tools {
              flex: 0 0 auto !important;
              width: auto !important;
              height: 32px !important;
              min-height: 32px !important;
              display: flex !important;
              align-items: stretch !important;
              justify-content: flex-end !important;
              gap: 0 !important;
              margin: 0 !important;
              padding: 0 !important;
            }
            .tool {
              box-sizing: border-box !important;
              width: 36px !important;
              min-width: 36px !important;
              height: 32px !important;
              min-height: 32px !important;
              margin: 0 !important;
              padding: 0 !important;
              border-radius: 3px !important;
              font-size: 21px !important;
              line-height: 30px !important;
              box-shadow: none !important;
            }
            .tool.nav { font-size: 27px !important; }

            /* DroidFish move/thinking TextViews use 12sp monospace. */
            .analysis {
              flex: 1 1 auto !important;
              min-height: 0 !important;
              height: auto !important;
              overflow-y: auto !important;
              overflow-x: hidden !important;
              margin: 0 !important;
              padding: 0 2px 3px !important;
              color: var(--move) !important;
              font: 12px/1.25 monospace !important;
            }
            .moves {
              display: block !important;
              width: 100% !important;
              height: auto !important;
              min-height: 17px !important;
              margin: 0 !important;
              padding: 0 !important;
              border: 0 !important;
              color: var(--move) !important;
              font: 12px/16px monospace !important;
              white-space: nowrap !important;
              overflow-x: auto !important;
              overflow-y: hidden !important;
            }
            #pvs { margin: 0 !important; padding: 0 !important; }
            .pv {
              margin: 0 !important;
              padding: 0 !important;
              font: 12px/15px monospace !important;
              overflow-wrap: anywhere !important;
            }
            .pct {
              min-width: 50px !important;
              font-size: 12px !important;
              font-weight: 700 !important;
            }
            .wdl {
              margin: 1px 0 0 !important;
              padding: 0 !important;
              font: 12px/15px monospace !important;
            }

            .navDrawer { width: min(82vw, 360px) !important; }

            /* DroidFish-style pawn promotion context dialog. */
            #hcaPromotionShade {
              position: fixed !important;
              inset: 0 !important;
              z-index: 120 !important;
              display: none !important;
              align-items: center !important;
              justify-content: center !important;
              background: #0008 !important;
            }
            #hcaPromotionShade.open { display: flex !important; }
            #hcaPromotionMenu {
              width: min(78vw, 320px) !important;
              overflow: hidden !important;
              border-radius: 4px !important;
              background: #3b3b3b !important;
              box-shadow: 0 8px 26px #000a !important;
              color: #fff !important;
            }
            #hcaPromotionTitle {
              padding: 13px 16px 10px !important;
              font-size: 18px !important;
              font-weight: 500 !important;
              border-bottom: 1px solid #ffffff1f !important;
            }
            .hcaPromotionItem {
              width: 100% !important;
              height: 52px !important;
              display: flex !important;
              align-items: center !important;
              gap: 12px !important;
              padding: 4px 16px !important;
              border: 0 !important;
              border-bottom: 1px solid #ffffff18 !important;
              background: transparent !important;
              color: #fff !important;
              text-align: left !important;
              font-size: 17px !important;
            }
            .hcaPromotionItem:last-child { border-bottom: 0 !important; }
            .hcaPromotionItem:active { background: #ffffff18 !important; }
            .hcaPromotionItem img {
              width: 38px !important;
              height: 38px !important;
              object-fit: contain !important;
            }
          `;
          document.head.appendChild(style);

          function layoutLikeDroidFish() {
            var vw = Math.max(1, app.clientWidth || window.innerWidth || 1);
            var vh = Math.max(1, window.innerHeight || document.documentElement.clientHeight || 1);
            var titleH = Math.max(0, topbar.getBoundingClientRect().height);
            var availableH = Math.max(1, vh - titleH);

            // Exact constants from ChessBoardPlay:
            // portrait -> max 75% of available height
            // landscape -> max 65% of available width
            var portrait = availableH > vw;
            var side = portrait
              ? Math.min(vw, Math.floor(availableH * 0.75))
              : Math.min(availableH, Math.floor(vw * 0.65));

            // Never let browser rounding collapse the board to a tiny size.
            side = Math.max(Math.min(vw, 280), side);
            boardWrap.style.width = side + 'px';
            boardWrap.style.height = side + 'px';
          }

          layoutLikeDroidFish();
          requestAnimationFrame(layoutLikeDroidFish);
          setTimeout(layoutLikeDroidFish, 80);
          window.addEventListener('resize', layoutLikeDroidFish, {passive:true});
          if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', layoutLikeDroidFish, {passive:true});
          }

          // DroidFish does not auto-queen. When a pawn reaches the final rank it
          // opens a context menu with Queen, Rook, Bishop and Knight. Override
          // the web UI move sender to do the same before committing the move.
          var promotionShade = document.createElement('div');
          promotionShade.id = 'hcaPromotionShade';
          promotionShade.innerHTML = '<div id="hcaPromotionMenu"><div id="hcaPromotionTitle">Promote pawn to?</div><div id="hcaPromotionItems"></div></div>';
          document.body.appendChild(promotionShade);

          var promotionResolver = null;
          function closePromotion(choice) {
            promotionShade.classList.remove('open');
            var resolver = promotionResolver;
            promotionResolver = null;
            if (resolver) resolver(choice || null);
          }
          function choosePromotion(options) {
            return new Promise(function(resolve) {
              promotionResolver = resolve;
              var box = document.getElementById('hcaPromotionItems');
              box.innerHTML = '';
              var sideWhite = !data || data.turn === 'white';
              var choices = [
                ['q', 'Queen', sideWhite ? 'Q' : 'q'],
                ['r', 'Rook', sideWhite ? 'R' : 'r'],
                ['b', 'Bishop', sideWhite ? 'B' : 'b'],
                ['n', 'Knight', sideWhite ? 'N' : 'n']
              ];
              choices.forEach(function(entry) {
                if (options.indexOf(entry[0]) < 0) return;
                var btn = document.createElement('button');
                btn.className = 'hcaPromotionItem';
                var img = document.createElement('img');
                try {
                  img.src = 'pieces/' + prefs.pieceSet + '/' + pieceFile[entry[2]];
                } catch (_) {
                  img.style.display = 'none';
                }
                var label = document.createElement('span');
                label.textContent = entry[1];
                btn.appendChild(img);
                btn.appendChild(label);
                btn.onclick = function() { closePromotion(entry[0]); };
                box.appendChild(btn);
              });
              promotionShade.classList.add('open');
            });
          }
          promotionShade.addEventListener('click', function(e) {
            if (e.target === promotionShade) closePromotion(null);
          });

          var oldBack = window.nativeBackPressed;
          window.nativeBackPressed = function() {
            if (promotionShade.classList.contains('open')) {
              closePromotion(null);
              return true;
            }
            return oldBack ? oldBack() : false;
          };

          makeMove = async function(from, to) {
            if (busy) return;
            var promotion = null;
            var base = from + to;
            var options = [];
            (data && data.legal_moves ? data.legal_moves : []).forEach(function(uci) {
              if (uci.slice(0, 4) === base && uci.length >= 5) {
                var p = uci.charAt(4).toLowerCase();
                if ('qrbn'.indexOf(p) >= 0 && options.indexOf(p) < 0) options.push(p);
              }
            });

            if (options.length) {
              promotion = await choosePromotion(options);
              if (!promotion) {
                selected = null;
                quickDest = null;
                renderBoard();
                return;
              }
            }

            busy = true;
            analysisEpoch++;
            try {
              var body = {from: from, to: to};
              if (promotion) body.promotion = promotion;
              data = await api('/api/move', {body: JSON.stringify(body)});
              selected = null;
              quickDest = null;
              preds = [];
              render();
            } catch (e) {
              toast(e.message);
              selected = null;
              quickDest = null;
              renderBoard();
            } finally {
              busy = false;
            }
          };
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
                webView.evaluateJavascript(droidFishExactLayoutPatch, null)
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
