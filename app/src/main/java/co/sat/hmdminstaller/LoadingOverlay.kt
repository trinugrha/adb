package co.sat.hmdminstaller

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin

/**
 * Kartu "sedang memproses" yang melayang di bawah layar selama [MainActivity] sibuk
 * (connect, pair, install, set owner, menyalin APK, dll).
 *
 * Lebar kartu menyesuaikan isi teks (maks 320dp), diletakkan di tengah.
 * Isi kartu: spinner dua busur, teks status, dan stopwatch.
 * Tidak memblokir sentuhan, jadi log tetap bisa digulir; tombol sudah dimatikan oleh setBusy().
 */
class LoadingOverlay(private val activity: Activity) {

    /** Kartu selebar isi teks, tetapi tidak melebihi [R.dimen.overlay_max_width]. */
    private val card = object : LinearLayout(activity) {
        private val maxW = activity.px(R.dimen.overlay_max_width)
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val mode = MeasureSpec.getMode(widthSpec)
            val size = MeasureSpec.getSize(widthSpec)
            val capped = if (mode == MeasureSpec.UNSPECIFIED) maxW else minOf(size, maxW)
            super.onMeasure(MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST), heightSpec)
        }
    }
    private val spinner = SpinnerView(activity)
    private val txtTitle = TextView(activity)
    private val txtTime = TextView(activity)
    private var startedAt = 0L
    private var lastSec = -1L
    private var shown = false

    private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val t = it.animatedValue as Float
            spinner.phase = t
            val sec = (SystemClock.elapsedRealtime() - startedAt) / 1000
            if (sec != lastSec) {
                lastSec = sec
                txtTime.text = Fmt.hms(sec)
            }
        }
    }

    init {
        val ctx: Context = activity
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(
            ctx.px(R.dimen.space_lg), ctx.px(R.dimen.space_md),
            ctx.px(R.dimen.space_lg), ctx.px(R.dimen.space_md)
        )
        card.elevation = ctx.px(R.dimen.overlay_elevation).toFloat()
        card.background = GradientDrawable().apply {
            cornerRadius = ctx.resources.getDimension(R.dimen.radius_xl)
            setColor(ctx.colorOf(R.color.surface))
            setStroke(ctx.px(R.dimen.stroke_width), ctx.colorOf(R.color.stroke))
        }

        card.addView(
            spinner,
            LinearLayout.LayoutParams(ctx.px(R.dimen.icon_size_large), ctx.px(R.dimen.icon_size_large))
        )

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        txtTitle.setTextColor(ctx.colorOf(R.color.text))
        txtTitle.setTextSizeRes(R.dimen.text_body)
        txtTitle.setTypeface(txtTitle.typeface, android.graphics.Typeface.BOLD)
        txtTitle.maxLines = 1
        txtTitle.ellipsize = TextUtils.TruncateAt.END
        txtTime.setTextColor(ctx.colorOf(R.color.muted))
        txtTime.setTextSizeRes(R.dimen.text_caption)
        txtTime.typeface = android.graphics.Typeface.MONOSPACE
        col.addView(txtTitle)
        col.addView(txtTime)
        card.addView(col, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = ctx.px(R.dimen.space_md) })

        card.visibility = View.GONE
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = activity.px(R.dimen.tab_bar_clearance)  // di atas tab melayang
            marginStart = activity.px(R.dimen.space_xl)
            marginEnd = activity.px(R.dimen.space_xl)
        }
        activity.findViewById<ViewGroup>(android.R.id.content).addView(card, lp)
    }

    fun show(text: String) {
        txtTitle.text = text
        if (shown) return
        shown = true
        startedAt = SystemClock.elapsedRealtime()
        lastSec = -1
        clock.start()
        card.animate().cancel()
        card.visibility = View.VISIBLE
        card.alpha = 0f
        card.translationY = slide.toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun update(text: String) { if (shown) txtTitle.text = text }

    fun hide() {
        if (!shown) return
        shown = false
        card.animate().cancel()
        card.animate().alpha(0f).translationY(slide.toFloat()).setDuration(180)
            .withEndAction {
                if (!shown) { card.visibility = View.GONE; clock.cancel() }
            }.start()
    }

    fun release() {
        shown = false
        clock.cancel()
        card.animate().cancel()
        (card.parent as? ViewGroup)?.removeView(card)
    }

    private val slide get() = activity.px(R.dimen.space_2xl)

    /** Spinner: busur luar berputar dan memanjang-memendek, busur dalam berputar berlawanan. */
    private class SpinnerView(ctx: Context) : View(ctx) {
        var phase = 0f
            set(v) { field = v; invalidate() }
        private val accent = ctx.colorOf(R.color.accent_text)
        private val d = ctx.resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * d
            color = accent
            alpha = 40
        }
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * d
            strokeCap = Paint.Cap.ROUND
            color = accent
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * d
            strokeCap = Paint.Cap.ROUND
            color = accent
            alpha = 140
        }
        private val oval = RectF()

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            val pad = 2 * d
            val r = c - pad
            oval.set(pad, pad, width - pad, height - pad)
            canvas.drawCircle(c, c, r, track)
            val sweep = 60f + 200f * (0.5f + 0.5f * sin(phase * 2 * Math.PI - Math.PI / 2).toFloat())
            canvas.drawArc(oval, phase * 720f - 90f, sweep, false, outer)
            val ip = pad + 6 * d
            oval.set(ip, ip, width - ip, height - ip)
            canvas.drawArc(oval, -phase * 540f, 100f, false, inner)
        }
    }
}
