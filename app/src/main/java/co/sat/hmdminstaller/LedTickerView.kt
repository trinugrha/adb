package co.sat.hmdminstaller

import android.content.Context
import android.graphics.Canvas
import android.provider.Settings
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.appcompat.widget.AppCompatTextView

/**
 * Teks berjalan seperti papan LED.
 *
 * Marquee bawaan TextView, dan juga scrollX milik TextView, di-reset tiap kali layout berubah (timer "Sesi"
 * berganti tiap detik), sehingga teks selalu kembali ke awal. View ini TIDAK memakai scroll: teks digambar
 * sendiri di onDraw dengan offset berbasis WAKTU (kecepatan tetap, mulus, tak terpengaruh layout).
 *
 * Bila teks muat di lebar view, teks diam. Bila tidak, teks digambar dua kali dengan jeda sehingga berputar
 * tanpa putus. Teks yang sama tidak mengulang animasi, baik lewat [setMessage] maupun `setText`/`text =`.
 *
 * Animasi hanya berjalan saat view benar-benar terlihat (terpasang, tidak GONE/INVISIBLE, jendela tampil).
 * Saat tersembunyi atau layar mati, frame callback dihentikan agar tidak boros baterai, lalu dilanjutkan
 * dari posisi terakhir ketika terlihat lagi.
 */
internal class LedTickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var message: String = ""
    private var textWidth = 0f
    private var period = 0f          // lebar satu putaran (teks + jeda), px
    private var needScroll = false   // teks lebih lebar dari view
    private var running = false      // frame callback sedang aktif
    private var startNanos = 0L
    private var offset = 0f
    private var animate = true       // false bila pengguna mematikan animasi sistem
    private var staticText = ""      // teks terpotong dengan "…" untuk mode tanpa animasi

    /** False selama konstruktor TextView berjalan (ia memanggil setText sebelum field kelas ini siap). */
    private var ready = false

    private val speedPxPerSec = 45f * resources.displayMetrics.density
    private val gapPx = 48f * resources.displayMetrics.density

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            // Double (bukan Float) supaya tetap mulus setelah berjam-jam; coerceAtLeast menahan frame pertama
            // yang kadang bernilai sedikit lebih kecil dari startNanos.
            val elapsed = (frameTimeNanos - startNanos).coerceAtLeast(0L)
            offset = (elapsed * speedPxPerSec.toDouble() / 1_000_000_000.0 % period).toFloat()
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        // Selaraskan dengan teks awal dari XML (android:text), lalu aktifkan override setText.
        message = text?.toString() ?: ""
        ready = true
    }

    fun setMessage(s: String) {
        if (s == message) return
        setText(s)
    }

    /**
     * Semua jalur pengisian teks (setMessage, `text = ...`, setText(resId)) lewat sini, sehingga [message]
     * selalu sama dengan yang tampil. Teks yang sama tidak mengulang animasi.
     */
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        if (!ready) return
        val s = text?.toString() ?: ""
        if (s == message) return
        message = s
        recompute(resetPhase = true)
    }

    /** [resetPhase] true hanya saat teks berganti. Perubahan ukuran kecil (mis. lebar timer) tidak boleh mengulang animasi. */
    private fun recompute(resetPhase: Boolean) {
        val avail = width - paddingLeft - paddingRight
        textWidth = paint.measureText(message)
        needScroll = avail > 0 && textWidth > avail
        animate = animationsEnabled()
        staticText = if (needScroll && !animate) {
            TextUtils.ellipsize(message, paint, avail.toFloat(), TextUtils.TruncateAt.END).toString()
        } else ""
        if (needScroll) {
            period = textWidth + gapPx
            if (resetPhase) {
                offset = 0f
                startNanos = System.nanoTime()
            } else {
                // lanjutkan dari posisi sekarang dengan periode baru
                offset %= period
                startNanos = System.nanoTime() - (offset / speedPxPerSec * 1_000_000_000f).toLong()
            }
        } else {
            offset = 0f
        }
        updateTicker()
        invalidate()
    }

    /** Hormati pengaturan sistem "Remove animations" (skala durasi animasi 0). */
    private fun animationsEnabled(): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    } catch (_: Exception) { true }

    private fun shouldRun(): Boolean = isAttachedToWindow && isShown && windowVisibility == View.VISIBLE

    /** Jalankan frame callback hanya bila perlu berjalan DAN terlihat; selain itu hentikan. */
    private fun updateTicker() {
        val want = needScroll && animate && shouldRun()
        if (want && !running) {
            running = true
            // lanjut dari posisi terakhir (bukan mulai dari awal) setelah sempat berhenti
            startNanos = System.nanoTime() - (offset / speedPxPerSec * 1_000_000_000f).toLong()
            Choreographer.getInstance().postFrameCallback(frame)
        } else if (!want && running) {
            stopTicker()
        }
    }

    private fun stopTicker() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onDraw(canvas: Canvas) {
        if (!needScroll) { super.onDraw(canvas); return }
        val l = layout ?: return
        val left = paddingLeft.toFloat()
        val baseline = (compoundPaddingTop + l.getLineBaseline(0)).toFloat()
        paint.color = currentTextColor
        canvas.save()
        canvas.clipRect(left, 0f, (width - paddingRight).toFloat(), height.toFloat())
        if (animate) {
            canvas.drawText(message, left - offset, baseline, paint)
            canvas.drawText(message, left - offset + period, baseline, paint)
        } else {
            canvas.drawText(staticText, left, baseline, paint)
        }
        canvas.restore()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (ready && w != oldw) recompute(resetPhase = false)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready) updateTicker()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (ready) updateTicker()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        recompute(resetPhase = false)
    }

    override fun onDetachedFromWindow() {
        stopTicker()
        super.onDetachedFromWindow()
    }
}
