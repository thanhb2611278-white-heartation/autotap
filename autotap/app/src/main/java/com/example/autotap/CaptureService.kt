package com.example.autotap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bản cho skin "note tròn vàng + vòng trắng":
 * - Làn = các VÒNG TRẮNG rỗng ở giữa màn hình (bỏ vòng nhỏ hai bên).
 * - Mỗi note = 1 "cung viền trên" (từ viền olive chuyển sang nền vàng) chạy dọc cột giữa của làn.
 *   Note chồng lên nhau vẫn tách được vì mỗi note có 1 cung riêng.
 * - Note đè = đầu tròn vàng có thân olive dài phía trên.
 */
class CaptureService : Service() {

    // ===== CHỈNH Ở ĐÂY CHO KHỚP GAME =====
    private val SCALE = 2               // thu nhỏ ảnh (2 = rõ viền note hơn, nặng hơn chút)
    private val MIN_LANES = 3           // số làn tối thiểu để khóa bố cục
    private val MIN_LANE_W = 0.04f     // vòng trắng rộng tối thiểu (so với chiều ngang màn hình). Loại vòng nhỏ 2 bên
    private val CENTER_MIN = 0.30f
    private val CENTER_MAX = 0.70f
    private val TAP_Y = 0.5f            // chạm ở 50% chiều cao màn hình
    private val LAT_MS = 110f           // độ trễ ước tính. Bấm TRỄ -> tăng. Bấm SỚM -> giảm
    private val TOL = 0.12f             // dung sai theo dõi note (theo đường kính vòng)
    private val HOLD_RUN = 0.4f         // thân olive dài >= 40% đường kính => note đè
    private val MAX_HOLD_MS = 8000L

    private fun rd(f: Frame, x: Int, y: Int): Int {
        val o = y * f.rs + x * 4
        return ((f.buf.get(o).toInt() and 255) shl 16) or
            ((f.buf.get(o + 1).toInt() and 255) shl 8) or
            (f.buf.get(o + 2).toInt() and 255)
    }
    private fun isYel(f: Frame, x: Int, y: Int): Boolean {
        val c = rd(f, x, y)
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return r >= 235 && g >= 225 && b in 120..175
    }
    private fun isOlive(f: Frame, x: Int, y: Int): Boolean {
        val c = rd(f, x, y)
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return r in 120..175 && g in 115..170 && b in 60..120 && r - b > 40
    }
    private fun isWhite(f: Frame, x: Int, y: Int): Boolean {
        val c = rd(f, x, y)
        return ((c shr 16) and 255) >= 170 && ((c shr 8) and 255) >= 170 && (c and 255) >= 170
    }
    // =====================================

    private class Lane(val l: Int, val r: Int, val t: Int, val b: Int) {
        val cx = (l + r) / 2
        val d = max(1, r - l + 1)
        var down = false
        var downAt = 0L
        var gone = 0
        var lastT = 0L
        var prev = IntArray(0)
        val firedY = ArrayList<Float>()
        val firedMiss = ArrayList<Int>()
    }

    private class Frame(val buf: ByteBuffer, val rs: Int, val w: Int, val h: Int)

    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val ht = HandlerThread("cap").also { it.start() }
    private val handler = Handler(ht.looper)

    private var lanes: List<Lane> = emptyList()
    private var lastScan = 0L
    private var missScans = 0
    private var sw = 0
    private var sh = 0
    private var lastSizeCheck = 0L
    private var speed = 0f            // tốc độ rơi (điểm ảnh đã thu nhỏ / ms)
    private var tapCount = 0
    private var holdCount = 0
    private var lastNotif = 0L
    private var lastFrameT = 0L
    private var frames = 0
    private var fps = 0
    private var fpsT = 0L
    private var lastErr = ""

    private var maskBuf = BooleanArray(0)
    private var seenBuf = BooleanArray(0)
    private var qBuf = IntArray(0)
    private val arcBuf = IntArray(64)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun status(): Notification =
        Notification.Builder(this, "cap")
            .setContentTitle("Auto Tap đang chạy")
            .setContentText("Làn ${lanes.size} v=${"%.2f".format(speed)} chạm $tapCount giữ $holdCount | fps $fps | cử chỉ ${ClickService.okCnt}/${ClickService.cancelCnt}/${ClickService.failCnt} $lastErr")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOnlyAlertOnce(true)
            .build()

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cap", "Auto Tap", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(1, status(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", 0) ?: return START_NOT_STICKY
        val data: Intent = (if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else intent.getParcelableExtra<Intent>("data")) ?: return START_NOT_STICKY

        val mp = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        proj = mp
        build()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 300)
        return START_NOT_STICKY
    }

    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun build() {
        val dm = realMetrics()
        sw = dm.widthPixels; sh = dm.heightPixels
        val w = sw / SCALE; val h = sh / SCALE
        reader?.close()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = r
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        val v = vd
        if (v == null) {
            vd = proj!!.createVirtualDisplay(
                "cap", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
            )
        } else {
            v.resize(w, h, dm.densityDpi)
            v.surface = r.surface
        }
        adopt(emptyList())
    }

    // Ảnh ngừng về (đơ) thì nhả hết ngón để không bị kẹt nút
    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (lastFrameT != 0L && now - lastFrameT > 600) {
                for (ln in lanes) ln.down = false
                ClickService.instance?.releaseAll()
            }
            handler.postDelayed(this, 300)
        }
    }

    private fun onFrame(rd: ImageReader) {
        val img = rd.acquireLatestImage() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastSizeCheck > 1000) {
                lastSizeCheck = now
                val dm = realMetrics()
                if (dm.widthPixels != sw || dm.heightPixels != sh) { handler.post { build() }; return }
            }
            lastFrameT = now
            frames++
            if (now - fpsT >= 1000) { fps = frames; frames = 0; fpsT = now }
            val p = img.planes[0]
            process(Frame(p.buffer, p.rowStride, img.width, img.height), now)
        } catch (e: Throwable) {
            lastErr = "ERR:" + (e.javaClass.simpleName + " " + (e.message ?: "")).take(50)
            for (ln in lanes) ln.down = false
            ClickService.instance?.releaseAll()
        } finally {
            img.close()
        }
    }

    // Tìm các VÒNG TRẮNG rỗng ở giữa, suy ra làn bị che ở giữa nếu cách đều nhau
    private fun scanRings(f: Frame): List<Lane> {
        val w = f.w; val h = f.h; val total = w * h
        if (maskBuf.size != total) {
            maskBuf = BooleanArray(total); seenBuf = BooleanArray(total); qBuf = IntArray(total)
        } else java.util.Arrays.fill(seenBuf, false)
        val mask = maskBuf; val seen = seenBuf; val q = qBuf
        for (y in 0 until h) for (x in 0 until w) mask[y * w + x] = isWhite(f, x, y)
        val cand = ArrayList<Lane>()
        for (s in 0 until total) {
            if (!mask[s] || seen[s]) continue
            var head = 0; var tail = 0
            q[tail++] = s; seen[s] = true
            var l = w; var r = -1; var t = h; var b = -1; var cnt = 0
            while (head < tail) {
                val p = q[head++]
                val x = p % w; val y = p / w
                cnt++
                if (x < l) l = x; if (x > r) r = x
                if (y < t) t = y; if (y > b) b = y
                if (x > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = true; q[tail++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = true; q[tail++] = p + 1 }
                if (y > 0 && mask[p - w] && !seen[p - w]) { seen[p - w] = true; q[tail++] = p - w }
                if (y < h - 1 && mask[p + w] && !seen[p + w]) { seen[p + w] = true; q[tail++] = p + w }
            }
            val bw = r - l + 1; val bh = b - t + 1
            val cx = (l + r) / 2f
            if (bw >= w * MIN_LANE_W && bh >= 0.4f * bw &&
                cnt <= 0.4f * bw * bh && cx >= w * CENTER_MIN && cx <= w * CENTER_MAX
            ) cand.add(Lane(l, r, t, b))
        }
        // Vòng nguyên (vuông) làm mẫu; vòng bị note che một phần vẫn nhận nếu cùng độ rộng
        val full = cand.filter {
            val hh = it.b - it.t + 1
            abs(it.d - hh) <= 0.2f * max(it.d, hh)
        }
        if (full.isEmpty()) return emptyList()
        val dm = full.map { it.d }.sorted().let { it[it.size / 2] }
        val rt = full.map { it.t }.sorted().let { it[it.size / 2] }
        val rb = full.map { it.b }.sorted().let { it[it.size / 2] }
        val rings = ArrayList<Lane>()
        for (c in cand.sortedBy { it.cx }) {
            if (abs(c.d - dm) > 0.12f * dm) continue
            if (rings.isNotEmpty() && c.cx - rings.last().cx < 0.5f * dm) continue
            rings.add(Lane(c.l, c.r, rt, rb))
        }
        if (rings.size < 2) return rings
        val gaps = (1 until rings.size).map { rings[it].cx - rings[it - 1].cx }
        val unit = gaps.minOrNull()!!
        if (unit < 0.9f * dm) return emptyList()
        val out = ArrayList<Lane>()
        out.add(rings[0])
        for (k in 1 until rings.size) {
            val g = gaps[k - 1]
            val m = Math.round(g.toFloat() / unit)
            if (m < 1 || m > 3 || abs(g - m * unit) > 0.12f * unit) return emptyList()
            for (j in 1 until m) {
                val pr = rings[k - 1]; val off = g * j / m
                out.add(Lane(pr.l + off, pr.r + off, pr.t, pr.b))
            }
            out.add(rings[k])
        }
        return out
    }

    private fun adopt(found: List<Lane>) {
        ClickService.instance?.let { s -> for (i in lanes.indices) s.release(i) }
        lanes = found
    }

    private fun rescan(f: Frame, now: Long) {
        lastScan = now
        val found = scanRings(f)
        if (found.isEmpty()) {
            if (++missScans >= 5 && lanes.isNotEmpty()) adopt(emptyList())  // rời game thì dừng
            return
        }
        missScans = 0
        if (lanes.isEmpty()) { if (found.size >= MIN_LANES) adopt(found); return }
        if (found.size > lanes.size) adopt(found)   // thấy thêm làn (trước đó bị che) thì mở rộng
    }

    private fun process(f: Frame, now: Long) {
        val every = if (lanes.isEmpty()) 300L else 1000L
        if (now - lastScan > every) rescan(f, now)
        if (now - lastNotif > 1000) {
            lastNotif = now
            getSystemService(NotificationManager::class.java).notify(1, status())
        }
        val n = lanes.size
        if (n == 0) return
        val svc = ClickService.instance ?: return

        for ((i, ln) in lanes.withIndex()) {
            val d = ln.d
            val cx = min(max(ln.cx, 0), f.w - 1)
            val yTop = max(1, ln.t - (3.2f * d).toInt())
            val yBot = min(f.h - 1, ln.b + (0.2f * d).toInt())
            val tapX = (i + 0.5f) / n * sw      // chia đều chiều ngang màn hình theo số làn
            val tapY = sh * TAP_Y

            // 1) Các "cung viền trên" của note trong cột giữa làn
            var cntA = 0
            var pv = isYel(f, cx, yTop - 1)
            for (y in yTop..yBot) {
                val yl = isYel(f, cx, y)
                if (yl && !pv && cntA < arcBuf.size) arcBuf[cntA++] = y
                pv = yl
            }
            val dt = if (ln.lastT == 0L) 0L else now - ln.lastT
            ln.lastT = now

            // 2) Đo tốc độ rơi từ chuyển động của các cung giữa 2 khung hình
            if (dt in 4L..100L && ln.prev.isNotEmpty()) {
                val dys = ArrayList<Int>()
                for (k in 0 until cntA) {
                    val a = arcBuf[k]
                    var best = -1
                    for (p in ln.prev) if (p <= a && a - p <= 0.3f * d && p > best) best = p
                    if (best >= 0 && a > best) dys.add(a - best)
                }
                if (dys.isNotEmpty()) {
                    dys.sort()
                    val v = dys[dys.size / 2].toFloat() / dt
                    speed = if (speed == 0f) v else speed * 0.85f + v * 0.15f
                }
            }
            ln.prev = arcBuf.copyOf(cntA)

            // 3) Theo dõi các note đã bấm (không bấm lại)
            val adv = if (dt in 1L..200L) speed * dt else 0f
            for (k in ln.firedY.indices) ln.firedY[k] = ln.firedY[k] + adv
            val tol = TOL * d
            val matched = BooleanArray(ln.firedY.size)
            val isNew = BooleanArray(cntA)
            for (k in 0 until cntA) {
                val a = arcBuf[k]
                var bj = -1; var bd = Float.MAX_VALUE
                for (j in ln.firedY.indices) {
                    val dd = abs(ln.firedY[j] - a)
                    if (dd <= tol && dd < bd) { bd = dd; bj = j }
                }
                if (bj >= 0) { ln.firedY[bj] = a.toFloat(); matched[bj] = true } else isNew[k] = true
            }
            var j = ln.firedY.size - 1
            while (j >= 0) {
                if (matched[j]) ln.firedMiss[j] = 0 else ln.firedMiss[j] = ln.firedMiss[j] + 1
                if (ln.firedMiss[j] > 3 || ln.firedY[j] > ln.b + 1.2f * d) {
                    ln.firedY.removeAt(j); ln.firedMiss.removeAt(j)
                }
                j--
            }

            // 4) Đang giữ note đè: nhả khi hết thân olive
            if (ln.down) {
                val zt = max(0, ln.t - (0.3f * d).toInt())
                var o = 0; var yw = 0
                for (y in zt..ln.b) { if (isOlive(f, cx, y)) o++ else if (isYel(f, cx, y)) yw++ }
                val rows = (ln.b - zt + 1).toFloat()
                val frO = o / rows; val frY = yw / rows
                val held = now - ln.downAt
                val grace = if (speed > 0f) (1.8f * d / speed).toLong().coerceIn(150L, 900L) else 400L
                val end = held > MAX_HOLD_MS ||
                    (held > 250 && frO + frY < 0.05f) ||
                    (held > grace && frO < 0.2f)
                if (end) ln.gone++ else ln.gone = 0
                if (ln.gone >= 2 || held > MAX_HOLD_MS) {
                    ln.down = false; ln.gone = 0
                    svc.release(i)
                }
            }

            // 5) Note mới chạm tới vạch bấm (đã trừ độ trễ) thì bấm
            val yTrig = ln.t - speed * LAT_MS
            for (k in 0 until cntA) {
                if (!isNew[k]) continue
                val a = arcBuf[k]
                if (a < yTrig || a > ln.b) continue
                ln.firedY.add(a.toFloat()); ln.firedMiss.add(0)
                if (ln.down) continue
                if (a > yTrig + 0.6f * d) continue      // note đã trễ quá, bỏ qua
                // Note đè: phía trên cung (bỏ 2 hàng viền mờ) có thân olive dài
                var run = 0
                var yy = a - 3
                while (yy > 0 && isOlive(f, cx, yy)) { run++; yy-- }
                if (run >= HOLD_RUN * d) {
                    ln.down = true; ln.downAt = now; ln.gone = 0
                    holdCount++
                    svc.press(i, tapX, tapY)
                } else {
                    tapCount++
                    svc.tap(i, tapX, tapY)
                }
            }
        }
    }

    override fun onDestroy() {
        ClickService.instance?.let { s -> for (i in lanes.indices) s.release(i) }
        vd?.release()
        reader?.close()
        proj?.stop()
        ht.quitSafely()
        super.onDestroy()
    }
}
