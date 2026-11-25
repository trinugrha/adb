package co.sat.hmdminstaller

import android.animation.ValueAnimator
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayout
import kotlin.math.abs

/**
 * Perilaku tab bar melayang di dasar layar: pindah tab dengan swipe, ikut turun saat keyboard muncul, sembunyi saat isi
 * digulir ke bawah, dan menutup keyboard saat layar digeser vertikal. Dipakai dari UI thread.
 *
 * [modeTabs] mengembalikan TabLayout Wireless/USB, atau null selama tampilan belum siap.
 */
internal class TabBarBehavior(
    private val activity: AppCompatActivity,
    private val modeTabs: () -> TabLayout?
) {

    /** Swipe horizontal di mana saja pindah ke tab sebelah (Wireless <-> USB), selama tab tujuan tidak terkunci. */
    private val swipeDetector by lazy {
        GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val tabs = modeTabs()
                if (e1 == null || tabs == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val d = activity.resources.displayMetrics.density
                if (abs(dx) < 80 * d || abs(dx) < 2 * abs(dy) || abs(vx) < 400 * d) return false
                val target = tabs.selectedTabPosition + if (dx < 0) 1 else -1
                val tab = tabs.getTabAt(target) ?: return false
                if (!tab.view.isEnabled) return false
                tab.select()
                return true
            }
        })
    }

    // Posisi tab bar = geser karena keyboard + geser karena disembunyikan saat gulir.
    private var kbOffset = 0f
    private var hideOffset = 0f
    private var hidden = false
    private var anim: ValueAnimator? = null

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var kbHandledForGesture = false

    private fun applyOffset() {
        activity.findViewById<View>(R.id.tabBar).translationY = kbOffset + hideOffset
    }

    /** Kembalikan posisi tab ke dasar layar (mis. setelah layar HP dibuka kembali). */
    fun resetKeyboardOffset() {
        kbOffset = 0f
        applyOffset()
    }

    private fun setHidden(hide: Boolean, bar: View) {
        if (hidden == hide) return
        hidden = hide
        val d = activity.resources.displayMetrics.density
        // Cukup jauh agar bar beserta bayangan dan jarak 16dp ke dasar layar ikut hilang.
        val target = if (hide) bar.height + 32 * d else 0f
        anim?.cancel()
        anim = ValueAnimator.ofFloat(hideOffset, target).apply {
            duration = 180
            addUpdateListener { hideOffset = it.animatedValue as Float; applyOffset() }
            start()
        }
    }

    /** Pasang pendengar tata letak (keyboard) dan gulir. Panggil sekali setelah setContentView. */
    fun attach() {
        // Tab melayang tidak ikut naik saat keyboard muncul: isi halaman tetap menyusut (adjustResize),
        // tetapi bar tab digeser turun sebesar penyusutan itu sehingga tetap di dasar layar, di balik keyboard.
        val root = activity.findViewById<View>(android.R.id.content)
        val bar = activity.findViewById<View>(R.id.tabBar)
        var fullH = 0
        var lastW = 0
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val w = root.width
            val h = root.height
            if (w != lastW) { lastW = w; fullH = 0 }
            if (h > fullH) fullH = h
            // Hanya penyusutan akibat keyboard yang dikompensasi. Penyusutan lain (layar dikunci/dibuka,
            // bar sistem berubah, overlay PIN) dianggap tinggi layar baru, sehingga tab tidak tertinggal
            // turun rapat ke dasar layar.
            val shrink = (fullH - h).coerceAtLeast(0)
            val imeVisible = ViewCompat.getRootWindowInsets(root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
            val keyboardShrink = imeVisible || shrink > fullH / 5
            if (!keyboardShrink) fullH = h
            kbOffset = if (keyboardShrink) shrink.toFloat() else 0f
            applyOffset()
        }

        // Tab melayang ikut sembunyi saat isi digulir ke bawah, dan muncul lagi saat digulir ke atas.
        val density = activity.resources.displayMetrics.density
        val slop = 4 * density
        var acc = 0f
        activity.findViewById<ScrollView>(R.id.pageScroll).setOnScrollChangeListener { _, _, y, _, oldY ->
            val dy = (y - oldY).toFloat()
            if (y <= 0) { acc = 0f; setHidden(false, bar); return@setOnScrollChangeListener }
            // Akumulasi searah agar getaran kecil tidak membuat tab kedip.
            acc = if (dy * acc < 0) dy else acc + dy
            if (acc > slop) setHidden(true, bar)
            else if (acc < -slop) setHidden(false, bar)
        }
    }

    /** Teruskan dari `dispatchTouchEvent`: swipe antar tab, dan geser layar ke atas/bawah menutup keyboard. */
    fun onTouch(ev: MotionEvent) {
        swipeDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
                kbHandledForGesture = false
            }
            MotionEvent.ACTION_MOVE -> if (!kbHandledForGesture) {
                val dx = abs(ev.x - touchDownX)
                val dy = abs(ev.y - touchDownY)
                if (dy > ViewConfiguration.get(activity).scaledTouchSlop && dy > dx) {
                    kbHandledForGesture = true
                    hideKeyboard()
                }
            }
        }
    }

    fun hideKeyboard() {
        val window = activity.window
        val visible = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        if (!visible) return
        // Tanpa fokus (mis. kolom input sudah disembunyikan), tetap tutup lewat token jendela.
        val focus = activity.currentFocus
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow((focus ?: window.decorView).windowToken, 0)
        focus?.clearFocus()
    }
}
