package com.example.autotap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Mỗi làn = 1 ngón (id = số thứ tự làn).
 * tap():   chạm nhanh, luôn nhấc lên sau mỗi lần (không bị "dính" với lần chạm trước).
 * press()/release(): giữ ngón cho note đè, nối các đoạn cử chỉ ngắn bằng continueStroke.
 * Mọi lần chạm đang chờ được gom vào MỘT cử chỉ để các làn chạm cùng lúc.
 */
class ClickService : AccessibilityService() {
    companion object {
        @Volatile var instance: ClickService? = null
        @Volatile var okCnt = 0
        @Volatile var cancelCnt = 0
        @Volatile var failCnt = 0
        private const val SEG = 35L   // độ dài mỗi đoạn / mỗi lần chạm (ms)
    }

    private val main = Handler(Looper.getMainLooper())
    private val taps = LinkedHashMap<Int, PointF>()
    private val want = HashMap<Int, PointF>()
    private val pts = HashMap<Int, PointF>()
    private val live = HashMap<Int, GestureDescription.StrokeDescription>()
    private var busy = false
    private var gid = 0

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(id: Int, x: Float, y: Float) {
        main.post {
            if (!want.containsKey(id) && !live.containsKey(id)) { taps[id] = PointF(x, y); pump() }
        }
    }
    fun press(id: Int, x: Float, y: Float) { main.post { want[id] = PointF(x, y); pump() } }
    fun release(id: Int) { main.post { want.remove(id); pump() } }
    fun releaseAll() { main.post { want.clear(); taps.clear(); pump() } }

    private fun pump() {
        if (busy) return
        if (taps.isEmpty() && want.isEmpty() && live.isEmpty()) return
        val b = GestureDescription.Builder()
        val next = HashMap<Int, GestureDescription.StrokeDescription>()
        var n = 0
        for (id in (want.keys + live.keys).toSet()) {
            val old = live[id]
            val p = want[id]
            if (p != null) {
                val pt = if (old != null) pts[id]!! else p.also { pts[id] = it }
                val path = Path().apply { moveTo(pt.x, pt.y) }
                val s = if (old != null) old.continueStroke(path, 0, SEG, true)
                        else GestureDescription.StrokeDescription(path, 0, SEG, true)
                b.addStroke(s); next[id] = s; n++
            } else if (old != null) {
                val pt = pts[id]!!
                val path = Path().apply { moveTo(pt.x, pt.y) }
                b.addStroke(old.continueStroke(path, 0, 10, false)); n++
            }
        }
        for ((_, pt) in taps) {
            val path = Path().apply { moveTo(pt.x, pt.y) }
            b.addStroke(GestureDescription.StrokeDescription(path, 0, SEG, false)); n++
        }
        taps.clear()
        if (n == 0) return
        live.clear(); live.putAll(next)
        busy = true
        val my = ++gid
        val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (my != gid) return
                okCnt++; busy = false; pump()
            }
            override fun onCancelled(g: GestureDescription?) {
                if (my != gid) return
                cancelCnt++; busy = false; live.clear(); pump()
            }
        }, main)
        if (!ok) { failCnt++; busy = false; live.clear(); return }
        // Chống kẹt: nếu hệ thống không báo kết quả thì tự gỡ để không bị "đơ"
        main.postDelayed({
            if (busy && my == gid) { failCnt++; busy = false; live.clear(); pump() }
        }, 300)
    }
}
