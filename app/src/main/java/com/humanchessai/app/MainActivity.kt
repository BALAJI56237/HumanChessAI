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

    /**
     * DroidFish does not create a second, floating copy of a chess piece while
     * dragging. It keeps one drag state, draws that piece at the finger
     * coordinates, and clears the drag state on UP/CANCEL.
     *
     * The HTML board originally re-rendered the whole board when dragging
     * started, even though the touched square owned pointer capture. Android
     * WebView can then lose the pointer sequence and leave the temporary clone
     * visible. This patch mirrors DroidFish's approach: move the actual piece
     * element during the gesture, never rebuild the board mid-drag, then render
     * the board once when the gesture finishes.
     */
    private val droidFishDragPatch = """
        (function () {
          if (window.__humanChessDragPatchInstalled) return;
          window.__humanChessDragPatchInstalled = true;

          function clearDragVisual(d) {
            if (!d) return;
            try {
              if (d.pieceEl) d.pieceEl.remove();
            } catch (_) {}
          }

          function decorateDragTargets(from) {
            try {
              document.querySelectorAll('.sq').forEach(function (sq) {
                sq.classList.remove('sel', 'target', 'capture');
              });
              var fromEl = document.querySelector('.sq[data-square="' + from + '"]');
              if (fromEl) fromEl.classList.add('sel');
              var targets = legalTargets(from);
              document.querySelectorAll('.sq').forEach(function (sq) {
                var s = sq.dataset.square;
                if (targets.has(s)) {
                  sq.classList.add('target');
                  if (data && data.pieces && data.pieces[s]) sq.classList.add('capture');
                }
              });
            } catch (_) {}
          }

          window.pointerDown = function (e, el, sq, piece) {
            if (busy || !sideHuman() || data.game_over) return;

            if (drag) {
              clearDragVisual(drag);
              drag = null;
            }

            drag = {
              id: e.pointerId,
              el: el,
              from: sq,
              piece: piece,
              x: e.clientX,
              y: e.clientY,
              active: false,
              pieceEl: null,
              squareSize: 0
            };

            try { el.setPointerCapture(e.pointerId); } catch (_) {}
            el.onpointermove = pointerMove;
            el.onpointerup = pointerUp;
            el.onpointercancel = pointerCancel;
          };

          window.pointerMove = function (e) {
            if (!drag || e.pointerId !== drag.id) return;

            if (!drag.active && drag.piece && own(drag.piece) &&
                Math.hypot(e.clientX - drag.x, e.clientY - drag.y) > 8) {
              drag.active = true;
              selected = drag.from;

              var sourcePiece = drag.el.querySelector('.piece');
              var rect = drag.el.getBoundingClientRect();
              drag.squareSize = rect.width;

              if (sourcePiece) {
                // Move the real rendered piece instead of creating a clone.
                // This is the important DroidFish-style part: one piece only.
                drag.pieceEl = sourcePiece;
                document.body.appendChild(sourcePiece);

                var cs = getComputedStyle(drag.el);
                sourcePiece.style.position = 'fixed';
                sourcePiece.style.margin = '0';
                sourcePiece.style.padding = '0';
                sourcePiece.style.width = rect.width + 'px';
                sourcePiece.style.height = rect.height + 'px';
                sourcePiece.style.display = 'flex';
                sourcePiece.style.alignItems = 'center';
                sourcePiece.style.justifyContent = 'center';
                sourcePiece.style.left = (e.clientX - rect.width / 2) + 'px';
                sourcePiece.style.top = (e.clientY - rect.height / 2) + 'px';
                sourcePiece.style.zIndex = '100';
                sourcePiece.style.pointerEvents = 'none';
                sourcePiece.style.fontFamily = cs.fontFamily;
                sourcePiece.style.fontSize = cs.fontSize;
                sourcePiece.style.lineHeight = '1';
                sourcePiece.style.color = cs.color;
                sourcePiece.style.filter = 'none';
              }

              decorateDragTargets(drag.from);
            }

            if (drag.active && drag.pieceEl) {
              e.preventDefault();
              var s = drag.squareSize || drag.el.getBoundingClientRect().width;
              drag.pieceEl.style.left = (e.clientX - s / 2) + 'px';
              drag.pieceEl.style.top = (e.clientY - s / 2) + 'px';
            }
          };

          window.pointerUp = async function (e) {
            if (!drag || e.pointerId !== drag.id) return;

            var d = drag;
            drag = null;

            try { d.el.releasePointerCapture(e.pointerId); } catch (_) {}
            clearDragVisual(d);

            if (d.active) {
              var hit = document.elementFromPoint(e.clientX, e.clientY);
              var sqEl = hit && hit.closest ? hit.closest('.sq') : null;
              var to = sqEl && sqEl.dataset ? sqEl.dataset.square : null;

              if (to && legalTargets(d.from).has(to)) {
                await makeMove(d.from, to);
              } else {
                selected = null;
                renderBoard();
              }
            } else {
              await tapSquare(d.from, d.piece);
            }
          };

          window.pointerCancel = function (e) {
            var d = drag;
            drag = null;
            clearDragVisual(d);
            selected = null;
            renderBoard();
          };

          // Also recover cleanly if Android pauses/cancels a gesture outside the
          // board before WebView delivers a normal pointer-up event.
          window.addEventListener('blur', function () {
            if (!drag) return;
            var d = drag;
            drag = null;
            clearDragVisual(d);
            selected = null;
            renderBoard();
          });

          document.addEventListener('visibilitychange', function () {
            if (!document.hidden || !drag) return;
            var d = drag;
            drag = null;
            clearDragVisual(d);
            selected = null;
          });
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
        webView.setBackgroundColor(android.graphics.Color.rgb(14, 15, 20))
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript(droidFishDragPatch, null)
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
