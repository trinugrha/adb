package co.sat.hmdminstaller

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Layar utama. Logika dipecah menjadi fungsi ekstensi di file bernama MainActivity*.kt:
 * Bind (view & tombol), Startup (sambung ke App, izin, jejak crash), Setup (listener tombol/tab, Informasi Status),
 * Steps (langkah koneksi ADB, Pair QR), Apks (pilih/salin APK), Live (kunci PIN, pemantau koneksi),
 * Render (tampilan header/tombol/kartu), Results (popup hasil, progres install, keluar).
 */
class MainActivity : AppCompatActivity() {

    /** Perilaku tab bar melayang: swipe antar tab, ikut keyboard, sembunyi saat gulir, dan tutup keyboard. */
    internal val tabBar = TabBarBehavior(this) { if (::tabMode.isInitialized) tabMode else null }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        tabBar.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    internal lateinit var edtPairIp: TextInputEditText
    internal val edtPairIpReady get() = ::edtPairIp.isInitialized
    internal lateinit var edtPairPort: TextInputEditText
    internal lateinit var edtPairCode: TextInputEditText
    internal lateinit var edtConnIp: TextInputEditText
    internal lateinit var edtConnPort: TextInputEditText
    internal lateinit var txtLog: TextView
    internal lateinit var logScroll: ScrollView
    internal lateinit var txtConn: LedTickerView
    internal lateinit var txtStatus: TextView
    internal lateinit var txtApkTitle: TextView
    internal lateinit var txtApkEmpty: TextView
    internal lateinit var txtDropSub: TextView
    internal lateinit var dropZone: View
    internal val dropZoneReady get() = ::dropZone.isInitialized
    internal lateinit var btnRefreshOwner: View
    internal val btnRefreshOwnerReady get() = ::btnRefreshOwner.isInitialized
    internal lateinit var apkListBox: LinearLayout
    internal val apkListBoxReady get() = ::apkListBox.isInitialized
    internal lateinit var tabMode: TabLayout
    internal val tabModeReady get() = ::tabMode.isInitialized
    internal lateinit var statusDot: View
    internal lateinit var ownerDot: View
    internal lateinit var txtOwner: TextView
    internal lateinit var tcpipDot: View
    internal lateinit var txtTcpip: TextView
    internal val txtTcpipReady get() = ::txtTcpip.isInitialized
    internal lateinit var appsPill: View
    internal lateinit var btnRefreshInfo: View
    internal val btnRefreshInfoReady get() = ::btnRefreshInfo.isInitialized
    internal lateinit var appsDot: View
    internal lateinit var txtApps: TextView
    internal val txtAppsReady get() = ::txtApps.isInitialized
    internal lateinit var txtAppsDetail: TextView
    internal lateinit var permAdminDot: View
    internal lateinit var txtPermAdmin: TextView
    internal val txtPermAdminReady get() = ::txtPermAdmin.isInitialized
    internal lateinit var progress: ProgressBar
    internal lateinit var loading: LoadingOverlay
    internal lateinit var logPanel: LogPanel
    /** Popup hasil proses yang sedang tampil; diganti (bukan ditumpuk) tiap ada hasil baru. */
    private var connDialog: AlertDialog? = null
    private var lastConnProblem: Triple<String, String, Boolean>? = null
    /** Popup hasil yang tertahan selama layar terkunci PIN; ditampilkan setelah PIN benar. */
    private var pendingConnProblem: Triple<String, String, Boolean>? = null
    /** Semua popup yang sedang terbuka, supaya bisa ditutup saat layar terkunci. */
    internal val openPopups = mutableListOf<AlertDialog>()

    /** Tutup semua popup yang terbuka (dipanggil saat terkunci). Popup hasil yang masih tampil ditahan untuk nanti. */
    internal fun hidePopupsForLock() {
        if (connDialog?.isShowing == true) pendingConnProblem = lastConnProblem
        connDialog?.dismiss()
        openPopups.toList().forEach { quietly { it.dismiss() } }
        openPopups.clear()
    }

    /** Tampilkan popup hasil yang tertahan selama terkunci (dipanggil setelah PIN benar). */
    internal fun showPendingConnProblem() {
        val p = pendingConnProblem ?: return
        pendingConnProblem = null
        showConnProblem(p.first, p.second, p.third)
    }
    internal lateinit var buttons: List<MaterialButton>
    // Tombol yang butuh koneksi ADB aktif: nonaktif (tidak bisa ditekan) selama belum terhubung.
    internal lateinit var needsConnection: Set<MaterialButton>
    /** Tombol yang membuka sambungan baru: mati selama masih ada sambungan aktif (harus Disconnect dulu). */
    internal lateinit var blockedWhenConnected: Set<MaterialButton>

    private val worker = Executors.newSingleThreadExecutor()
    // Penyalinan APK ke cache dipisah dari worker ADB agar tidak antre di belakang install, dan tidak membebani UI
    // thread.
    internal val io = Executors.newSingleThreadExecutor()
    /** Pendengar yang dipasang Activity ini di App/AdbController; dilepas di onDestroy hanya bila masih milik kita. */
    internal var logListenerRef: ((LogLevel, String) -> Unit)? = null
    internal var progressRef: ((Int, Int, String, Long, Long) -> Unit)? = null
    internal val apks = mutableListOf<ApkItem>()
    internal lateinit var adb: AdbController
    /** Status HP target yang sudah dibaca (koneksi, MDM Mode, aplikasi, izin, model). */
    internal val target = TargetState()
    @Volatile internal var busy = false
    internal var lostAddr: String? = null
    /**
     * True sesaat setelah Connect berhasil: Informasi Status (instalasi, launcher, izin) jangan dibaca otomatis; baru
     * dibaca saat kartunya diketuk.
     */
    @Volatile internal var skipInfoRefresh = false
    /** Jenis perangkat yang dipilih untuk ceklist instalasi: POS / TABLET / PDA (disimpan di preferensi). */
    internal var deviceType = "POS"
    internal lateinit var txtModel: TextView

    // Pemantau status koneksi (live): jalan hanya saat layar terlihat.
    private val poller = Executors.newSingleThreadScheduledExecutor()
    private var pollTask: ScheduledFuture<*>? = null
    internal var lastProbe = 0L
    internal var probeFails = 0

    // ---- Kunci PIN ----
    internal lateinit var pinStore: PinStore
    internal var lockView: PinLockView? = null
    internal val session = SessionTimer(LOCK_TIMEOUT_MS)
    internal lateinit var txtSession: TextView
    internal val backWhileLocked = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { moveTaskToBack(true) }
    }

    /** Penerima hasil pilih file untuk Export/Import versi aplikasi; diisi oleh dialog Setting saat tombolnya diketuk. */
    internal var onExportPicked: ((Uri) -> Unit)? = null
    internal var onImportPicked: ((Uri) -> Unit)? = null
    internal val exportVersionsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) onExportPicked?.invoke(uri)
    }
    internal val importVersionsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportPicked?.invoke(uri)
    }

    internal val pickApks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onApksPicked(uris)
    }

    /** Preferensi UI ringan: jenis perangkat terakhir dan penanda izin notifikasi sudah ditanyakan. */
    internal val uiPrefs get() = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    /** Ada sambungan ADB aktif ke HP target. */
    internal fun isLinked(): Boolean = target.connectedAddr != null && adb.isConnected

    /** Jalankan [work] lewat [run] dengan Informasi Status (instalasi, default launcher, izin) tidak ikut dibaca otomatis sesudahnya. */
    internal fun runSkippingInfo(title: String, work: () -> Boolean) = run(title) { skipInfoRefresh = true; work() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tangkapan layar diizinkan (FLAG_SECURE sengaja tidak dipasang).
        setContentView(R.layout.activity_main)

        tabBar.attach()
        bindViews()
        bindButtonGroups()
        attachToApp()

        // Log punya scroll sendiri di dalam ScrollView halaman: cegah halaman merebut gesture.
        logScroll.setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }

        setupStatusRefresh()
        setupMoreToggle()
        setupModeTabs()
        setupConnectionButtons()
        setupActionButtons()
        setupLogButtons()
        setupPin()

        // savedInstanceState != null = Activity dibuat ulang setelah proses dimatikan sistem (pengguna kembali dari
        // recent apps): daftar APK dipulihkan. null = aplikasi dibuka baru (mis. setelah digeser dari recent apps atau
        // Keluar): sisa APK dibuang, karena CleanupService tidak selalu sempat berjalan (sistem menghentikan service
        // latar yang menganggur).
        if (savedInstanceState != null) restorePickedApks() else io.execute { clearPickedApks() }
        refreshApkList()
        refreshHeader()
    }

    // Pemasangan listener tombol/tab dan pengecekan Informasi Status (setup*, checkDeviceInfo, connectUsb): lihat
    // MainActivitySetup.kt.

    override fun onStart() {
        super.onStart()
        // Kunci ulang bila aplikasi ditinggalkan lebih lama dari batas waktu (koneksi ADB tetap berjalan).
        if (lockView == null && !busy && sessionRemainingMs() <= 0) showLock()
        // Pemantau berhenti saat aplikasi di latar belakang; jangan hitung jeda itu sebagai waktu sibuk.
        session.skipGap()
        // Setelah layar HP dibuka kembali, kembalikan posisi tab ke tempat semula.
        tabBar.resetKeyboardOffset()
        pollTask?.cancel(false)
        pollTask = poller.scheduleWithFixedDelay({ pollConnection() }, 0, 1, TimeUnit.SECONDS)
    }

    override fun onStop() {
        super.onStop()
        pollTask?.cancel(false)
        pollTask = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideInstallProgress()
        loading.release()
        connDialog?.dismiss()
        worker.shutdown()
        io.shutdown()
        poller.shutdownNow()
        // Koneksi ADB sengaja TIDAK ditutup di sini: ia hidup di App + ConnectionService.
        // Hanya lepas pendengar log milik Activity ini.
        val app = application as App
        if (app.logListener === logListenerRef) app.logListener = null
        if (adb.onProgress === progressRef) adb.onProgress = null
    }

    /**
     * Popup hasil proses (gagal, putus, atau berhasil). [ok] true = ikon centang navy; gagal = ikon merah. Popup lama
     * diganti agar tidak bertumpuk.
     */
    internal fun showConnProblem(title: String, message: String, ok: Boolean = false) {
        // Saat terkunci PIN, popup tidak boleh tampil di atas layar kunci (isinya memuat alamat HP target).
        // Hasil terbaru ditahan dan ditampilkan setelah PIN benar.
        if (lockView != null) {
            pendingConnProblem = Triple(title, message, ok)
            return
        }
        lastConnProblem = Triple(title, message, ok)
        connDialog?.dismiss()
        connDialog = showPopup(
            title, message,
            icon = if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning,
            tone = if (ok) PopupTone.NAVY else PopupTone.DANGER
        )
    }

    /** Popup konfirmasi: judul, isi, Batal + tombol aksi. */
    internal fun showConfirm(
        title: String, message: String, positive: String,
        icon: Int = R.drawable.ic_warning, tone: PopupTone = PopupTone.DANGER,
        onConfirm: () -> Unit
    ) {
        showPopup(
            title, message, icon, tone,
            positive = positive,
            negative = getString(R.string.action_cancel),
            onPositive = { onConfirm(); true }
        )
    }

    // Kunci PIN dan pemantau status koneksi live: lihat MainActivityLive.kt.
    // Pair dengan QR dan langkah koneksi ADB (do*): lihat MainActivitySteps.kt.

    internal fun run(title: String, work: () -> Boolean) {
        if (busy) return
        if (adb.opRunning) {
            log(LogLevel.WARN, "Proses sebelumnya masih berjalan di latar belakang. Tunggu sampai selesai")
            return
        }
        adb.opRunning = true
        busy = true
        setBusy(true, "Sedang berjalan: $title…")
        val startedAt = System.currentTimeMillis()
        logPanel.runErrors.clear()
        logPanel.runWarns.clear()
        adb.lastInstall = null
        logHead(title, startedAt)
        worker.execute {
            // Tunggu probe yang sedang berjalan (bila ada) selesai sebelum memakai koneksi.
            synchronized(adb.probeLock) { }
            var ok = false
            try { ok = work() } catch (e: Throwable) {
                runOnUiThread {
                    log(LogLevel.ERR, "$title gagal: kesalahan tak terduga. Coba lagi, dan laporkan bila berulang")
                    log(LogLevel.DETAIL, Fmt.cause(e))
                }
            }
            // Perbarui status device owner setelah setiap operasi (install / set owner / dll).
            // Tepat setelah Connect, Informasi Status (launcher, instalasi, izin) tidak ikut dibaca.
            val skipInfo = skipInfoRefresh
            skipInfoRefresh = false
            refreshTargetState(skipInfo)
            adb.opRunning = false
            runOnUiThread {
                logEnd(title, ok, startedAt)
                busy = false
                setBusy(false, if (ok) "Selesai: $title" else "Gagal: $title, lihat log")
                refreshHeader()
                showRunResult(title, ok)
            }
        }
    }

    // ---------- Progress install (inline di halaman, di atas log) ----------

    internal var progShown = false
    internal var progIdx = -1
    internal var progStartMs = 0L

    internal fun setBusy(on: Boolean, status: String) {
        if (!on) hideInstallProgress()
        adb.holdLocks(on)
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyButtonState(on)
        apkListBox.alpha = if (on) 0.5f else 1f
        progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) loading.show(status) else loading.hide()
        txtStatus.text = status
    }

    // Render tampilan (refreshHeader, status tombol, kartu informasi): lihat MainActivityRender.kt.

    internal val apkStore by lazy { ApkStore(this) }

    /** Tulis satu entri ke panel log (lihat [LogPanel.log]). */
    internal fun log(level: LogLevel, s: String) = logPanel.log(level, s)

    internal fun logHead(title: String, at: Long = System.currentTimeMillis()) = logPanel.logHead(title, at)

    internal fun logEnd(title: String, ok: Boolean, startedAt: Long) = logPanel.logEnd(title, ok, startedAt)

    companion object {
        private const val UI_PREFS = "ui_prefs"
        internal const val PREF_DEVICE_TYPE = "device_type"
        internal const val PREF_NOTIF_ASKED = "notif_asked"
        /** Aplikasi dikunci lagi bila ditinggalkan lebih dari ini (ms). */
        private const val LOCK_TIMEOUT_MS = 30 * 60_000L
        /** Batas menunggu HP target memindai QR pairing. */
        internal const val QR_PAIR_TIMEOUT_MS = 120_000L
    }
}
