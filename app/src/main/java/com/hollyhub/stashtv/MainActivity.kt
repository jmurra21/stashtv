package com.hollyhub.stashtv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

// Change this if your Stash address ever changes.
const val START_URL = "https://stash.hollyhub.stufftoread.com/"

// Tells the app when a text box is focused so the on-screen keyboard gets the remote keys.
private const val INJECT = """
(function(){if(window.__stv)return;window.__stv=1;
var skip=['checkbox','radio','button','submit','range','color','file'];
function t(e){var n=e.target;if(!n)return false;
if(n.tagName==='TEXTAREA'||n.isContentEditable)return true;
return n.tagName==='INPUT'&&skip.indexOf(n.type)<0;}
document.addEventListener('focusin',function(e){if(t(e))StashTV.input(true)},true);
document.addEventListener('focusout',function(e){if(t(e))StashTV.input(false)},true);
})();
"""

class CursorView(ctx: Context) : View(ctx) {
    var cx = 0f
    var cy = 0f
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f * d
    }

    override fun onDraw(c: Canvas) {
        val p = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, cy + 22 * d); lineTo(cx + 6 * d, cy + 17 * d)
            lineTo(cx + 11 * d, cy + 27 * d); lineTo(cx + 15 * d, cy + 25 * d)
            lineTo(cx + 10 * d, cy + 15 * d); lineTo(cx + 17 * d, cy + 15 * d)
            close()
        }
        c.drawPath(p, fill)
        c.drawPath(p, edge)
    }
}

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private var custom: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    @Volatile private var inputFocused = false
    private val d by lazy { resources.displayMetrics.density }
    private val main = Handler(Looper.getMainLooper())

    inner class Bridge {
        @JavascriptInterface
        fun input(on: Boolean) { runOnUiThread { inputFocused = on } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        web = WebView(this)
        cursor = CursorView(this)
        val full = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(web, full)
        root.addView(cursor, full)
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.setBackgroundColor(Color.parseColor("#202b33"))
        cursor.isFocusable = false
        CookieManager.getInstance().setAcceptCookie(true)
        web.addJavascriptInterface(Bridge(), "StashTV")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) {
                v.evaluateJavascript(INJECT, null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                if (custom != null) { cb.onCustomViewHidden(); return }
                custom = v; customCb = cb
                root.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                cursor.visibility = View.GONE
            }
            override fun onHideCustomView() { hideCustom() }
        }

        web.requestFocus()
        web.loadUrl(START_URL)
        root.post {
            cursor.cx = root.width / 2f
            cursor.cy = root.height / 2f
            cursor.invalidate()
        }
    }

    private fun hideCustom() {
        custom?.let { root.removeView(it) }
        custom = null
        customCb?.onCustomViewHidden()
        customCb = null
        cursor.visibility = View.VISIBLE
        web.requestFocus()
    }

    private fun js(s: String) = web.evaluateJavascript(s, null)

    private fun togglePlay() =
        js("var v=document.querySelector('video');if(v){v.paused?v.play():v.pause();}")

    private fun seek(sec: Int) =
        js("var v=document.querySelector('video');if(v){v.currentTime+=($sec);}")

    private fun click() {
        val t = SystemClock.uptimeMillis()
        val x = cursor.cx
        val y = cursor.cy
        val dn = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        dn.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(dn); dn.recycle()
        main.postDelayed({
            val t2 = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(t, t2, MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            web.dispatchTouchEvent(up); up.recycle()
        }, 60)
    }

    // Scrolls whatever scrollable element is under the cursor (or the page).
    @Suppress("DEPRECATION")
    private fun scrollAt(dx: Int, dy: Int) {
        val sc = if (web.scale > 0f) web.scale else d
        val x = (cursor.cx / sc).toInt()
        val y = (cursor.cy / sc).toInt()
        js("""(function(x,y,dx,dy){var el=document.elementFromPoint(x,y);
while(el&&el!==document.body&&el!==document.documentElement){var s=getComputedStyle(el);
var v=dy!=0&&/(auto|scroll)/.test(s.overflowY)&&el.scrollHeight>el.clientHeight;
var h=dx!=0&&/(auto|scroll)/.test(s.overflowX)&&el.scrollWidth>el.clientWidth;
if(v||h){el.scrollBy(dx,dy);return}el=el.parentElement}
window.scrollBy(dx,dy)})($x,$y,$dx,$dy)""")
    }

    private fun move(e: KeyEvent) {
        val step = (10 + minOf(e.repeatCount, 25) * 2) * d
        var dx = 0f
        var dy = 0f
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
            else -> dx = step
        }
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        val nx = cursor.cx + dx
        val ny = cursor.cy + dy
        cursor.cx = nx.coerceIn(0f, w - 1)
        cursor.cy = ny.coerceIn(0f, h - 1)
        cursor.invalidate()
        // Pushing against a screen edge scrolls.
        if (nx < 0f || nx > w - 1 || ny < 0f || ny > h - 1) {
            val k = 2.5f / d
            scrollAt(if (nx < 0f || nx > w - 1) (dx * k).toInt() else 0,
                     if (ny < 0f || ny > h - 1) (dy * k).toInt() else 0)
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (k == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
        val inFull = custom != null
        if (inputFocused && !inFull) return super.dispatchKeyEvent(e)
        val down = e.action == KeyEvent.ACTION_DOWN
        val handled = when (k) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) togglePlay(); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) seek(10); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) seek(-10); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (down && e.repeatCount == 0) { if (inFull) togglePlay() else click() }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (inFull) { if (down) seek(-10); true } else { if (down) move(e); true }
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (inFull) { if (down) seek(10); true } else { if (down) move(e); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                if (inFull) false else { if (down) move(e); true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (down && !inFull) scrollAt(0, -500); true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down && !inFull) scrollAt(0, 500); true
            }
            else -> false
        }
        return handled || super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            custom != null -> hideCustom()
            inputFocused -> {
                js("if(document.activeElement)document.activeElement.blur()")
                inputFocused = false
            }
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }
}
