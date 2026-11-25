package co.sat.hmdminstaller

import android.content.Context
import io.github.muntashirakon.adb.AdbStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Padanan fungsi ADB di versi Windows: pair, connect, disconnect,
 * install -r, dan dpm set-device-owner. Dipanggil dari thread latar.
 */
class AdbController(context: Context, @Volatile var sink: (LogLevel, String) -> Unit) {

    internal fun logStep(s: String) = sink(LogLevel.STEP, s)
    internal fun logCmd(s: String) = sink(LogLevel.CMD, s)
    internal fun logOk(s: String) = sink(LogLevel.OK, s)
    internal fun logWarn(s: String) = sink(LogLevel.WARN, s)
    internal fun logErr(s: String) = sink(LogLevel.ERR, s)
    internal fun logDetail(s: String) = sink(LogLevel.DETAIL, s)
    internal fun logSummary(s: String) = sink(LogLevel.SUMMARY, s)

    /** Penyebab teknis (nama exception + pesan) sebagai baris DETAIL di bawah baris ✗ / !, bukan di dalamnya. */
    internal fun logCause(e: Throwable) = logDetail(Fmt.cause(e))

    companion object {
        const val OWNER_COMPONENT = "com.hmdm.launcher/.AdminReceiver"
        const val KASPERSKY_PACKAGE = "com.kaspersky.kes"
        const val TCPIP_PORT = 5555
        /** Batas waktu perintah shell cepat (getprop, appops, dst) dan yang lebih berat (dumpsys, pm list). */
        internal const val QUERY_SHORT_MS = 5_000L
        internal const val QUERY_LONG_MS = 8_000L
        /** Jumlah percobaan Connect USB (percobaan pertama bisa habis waktu selagi dialog otorisasi belum disetujui). */
        internal const val USB_CONNECT_ATTEMPTS = 3
        /** Batas menunggu jawaban uji koneksi (probe), dalam detik. */
        private const val PROBE_TIMEOUT_S = 6L
        /** Percobaan connect, jeda antar percobaan, dan percobaan sambung ulang otomatis. */
        private const val CONNECT_ATTEMPTS = 2
        internal const val CONNECT_RETRY_DELAY_MS = 1_500L
        private const val RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 2_000L
        /** Wi-Fi HP ini hilang: jumlah pengecekan jangkauan HP target dan jeda sebelum tiap pengecekan. */
        private const val WIFI_LOST_CHECKS = 3
        private const val WIFI_LOST_DELAY_MS = 2_000L
        const val HMDM_PACKAGE = "com.hmdm.launcher"
        /** Penyebab putus bila soket ditutup pihak lain. */
        const val DROP_CLOSED = "koneksi ditutup oleh HP target atau jaringan"
        /** Satu-satunya teks "belum terhubung", dipakai semua tempat agar tidak berbeda-beda. */
        const val MSG_NOT_CONNECTED = "Belum terhubung ke HP target. Hubungkan terlebih dahulu lewat ${AdbMessages.HOW_TO_CONNECT}"
    }

    private val appContext = context.applicationContext
    @Volatile private var mgr: AdbManager? = null

    /** Dibuat saat pertama dipakai (pembuatan kunci RSA bisa lambat), aman dipanggil dari banyak thread. */
    internal val manager: AdbManager
        get() = mgr ?: synchronized(this) {
            mgr ?: AdbManager(appContext).also {
                mgr = it
                it.keyResetReason?.let { r ->
                    logWarn("Kunci ADB dibuat ulang: $r. HP target yang sudah di-pair harus di-pair ulang")
                }
            }
        }

    /** Panggil dari thread latar saat aplikasi dibuka agar kunci siap sebelum dipakai. */
    fun warmUp() {
        try { manager } catch (e: Throwable) { logWarn("Kunci ADB belum siap"); logCause(e) }
    }

    /** Dipakai agar probe dari thread pemantau tidak tumpang tindih dengan operasi di thread kerja. */
    val probeLock = Any()

    /** Laporan kemajuan install: (nomor APK, jumlah APK, nama, byte terkirim, ukuran file). Dipanggil dari thread kerja. */
    @Volatile var onProgress: ((Int, Int, String, Long, Long) -> Unit)? = null

    private val powerLocks = PowerLocks(appContext)

    /** Tahan Wi-Fi (performa tinggi) + CPU tetap aktif selama operasi berjalan. Panggil dengan false setelah selesai. */
    fun holdLocks(on: Boolean) = powerLocks.hold(on)

    /** Alamat connect terakhir yang berhasil, dipakai untuk sambung ulang otomatis bila koneksi putus saat install. */
    @Volatile private var lastAddr: String? = null
    @Volatile internal var viaUsb = false
    /** true jika sesi aktif berjalan lewat kabel USB (false = ADB wireless atau tidak terhubung). */
    val isViaUsb: Boolean get() = viaUsb

    /** Label & waktu sesi, disimpan di sini (bukan di Activity) agar tampilan bisa dipulihkan bila Activity dibuat ulang. */
    @Volatile var sessionLabel: String? = null
    @Volatile var sessionSince: Long = 0L

    /** Jaga proses tetap hidup selama koneksi aktif (tanpa ini Android mematikan proses saat aplikasi ditutup/di background). */
    internal fun keepAlive(on: Boolean) {
        quietly { if (on) ConnectionService.start(appContext) else ConnectionService.stop(appContext) }
    }

    internal val usbBridge by lazy { UsbAdbBridge(appContext) }

    /**
     * Wi-Fi HP ini hilang. Sesi wireless tidak mungkin hidup lagi, jadi soket ditutup di thread latar.
     * Dilewati untuk sesi USB dan loopback (127.0.0.1). Pemantau di Activity lalu menandai koneksi terputus.
     */
    fun onWifiLost() {
        if (viaUsb || !connCache) return
        if (lastAddr?.startsWith("127.0.0.1:") == true) return
        // onLost juga terpanggil saat HP ini hanya berpindah AP / jaringan Wi-Fi. Jangan langsung menutup sesi:
        // beri waktu, lalu tutup hanya bila HP target tetap tidak terjangkau.
        if (!wifiLostChecking.compareAndSet(false, true)) return
        daemon {
            try {
                repeat(WIFI_LOST_CHECKS) {
                    Thread.sleep(WIFI_LOST_DELAY_MS)
                    // Sesi sudah berubah (putus sendiri, pindah ke USB, dst): tidak ada lagi yang perlu ditutup.
                    if (viaUsb || !connCache) return@daemon
                    if (isTargetReachable()) return@daemon
                }
                if (viaUsb || !connCache) return@daemon
                forcedDown = true
                connCache = false
                setDrop("Wi-Fi HP ini hilang")
                logWarn("Wi-Fi terputus, koneksi ADB ditutup")
                mgr?.disconnect()
            } finally {
                wifiLostChecking.set(false)
            }
        }
    }

    private val wifiLostChecking = AtomicBoolean(false)

    /**
     * Sesi dinyatakan mati dari thread pengawas (mis. Wi-Fi HP target mati saat install). Soket ditutup di thread latar
     * supaya thread yang tertahan di soket mati ikut lepas. Alasannya ditampilkan di popup "Koneksi Terputus".
     */
    internal fun abortSession(reason: String) {
        forcedDown = true
        connCache = false
        setDrop(reason)
        daemon { mgr?.disconnect() }
    }

    /** Tes TCP cepat ke HP target. Selalu true untuk USB / loopback atau bila belum ada alamat. */
    internal fun isTargetReachable(): Boolean {
        val a = lastAddr ?: return true
        if (viaUsb || a.startsWith("127.0.0.1:")) return true
        return reachable(a)
    }

    /** True bila koneksi lewat USB tetapi kabel sudah dicabut / perangkat hilang dari port. */
    val usbCableGone: Boolean
        get() = viaUsb && !usbBridge.isRunning

    /** Bersihkan sisa koneksi USB setelah kabel dicabut (tanpa menulis log). */
    fun dropUsb() {
        lastAddr = null
        connCache = false
        quietly { usbBridge.stop() }
        viaUsb = false
        quietly { mgr?.disconnect() }
        sessionLabel = null
        keepAlive(false)
    }

    internal fun tryReconnect(): Boolean {
        val addr = lastAddr ?: return false
        logWarn("Koneksi terputus, menyambung ulang ke ${sessionLabel ?: addr}…")
        // verbose = false: tanpa "> adb connect" dan "Percobaan ..." per percobaan, cukup satu hasil akhir.
        repeat(RECONNECT_ATTEMPTS) { i ->
            if (i > 0) Thread.sleep(RECONNECT_DELAY_MS)
            // HP target hilang dari jaringan: gagal cepat, jangan menunggu timeout connect library yang lama.
            if (!isTargetReachable()) {
                lastConnectReason = "HP target tidak terjangkau di jaringan (Wi-Fi HP target mati atau di luar jangkauan)"
                lastConnectDetail = "Koneksi TCP ke $addr gagal"
                return@repeat
            }
            if (connect(addr, verbose = false, label = sessionLabel)) return true
        }
        val hint = if (viaUsb) "Pastikan kabel USB masih terpasang dan USB debugging di HP target aktif"
                   else "Pastikan Wireless debugging di HP target masih aktif dan satu jaringan Wi-Fi"
        // Bentuk sama dengan "Connect gagal: alasan. saran".
        logErr("Sambung ulang gagal: $lastConnectReason. $hint")
        if (lastConnectDetail.isNotEmpty()) logDetail(lastConnectDetail)
        return false
    }

    /** Tutup koneksi & lepas resource (dipanggil saat Activity dihancurkan). */
    fun close() {
        connCache = false
        keepAlive(false)
        holdLocks(false)
        quietly { usbBridge.stop() }
        lastAddr = null
        viaUsb = false
        sessionLabel = null
        // Kosongkan mgr: bila proses tetap hidup setelah Keluar, koneksi berikutnya memakai manager baru,
        // bukan manager yang sudah di-close (kunci ADB dibaca ulang dari file, tidak dibuat ulang).
        val m = mgr
        mgr = null
        if (m != null) {
            quietly { m.disconnect() }
            quietly { m.close() }
        }
    }

    /** Menanyakan library langsung. Bisa tertahan lock library saat soket mati: JANGAN dipanggil dari main thread. */
    internal val libConnected: Boolean
        get() = !forcedDown && try { mgr?.isConnected ?: false } catch (e: Throwable) { false }

    /** true setelah Wi-Fi hilang: sesi dianggap putus walau library belum sadar (soket mati diam-diam). Dihapus saat connect baru. */
    @Volatile private var forcedDown = false

    @Volatile private var connCache = false

    /**
     * Status koneksi untuk UI / main thread: hanya membaca cache, tidak menyentuh library (tidak bisa hang).
     * Cache diperbarui oleh [syncConnected] (thread pemantau) dan tiap connect/disconnect.
     */
    val isConnected: Boolean get() = connCache

    /** Cek library lalu perbarui cache. Panggil dari thread latar saja. */
    fun syncConnected(): Boolean {
        val c = libConnected
        if (!c && connCache && !forcedDown) setDrop(DROP_CLOSED)
        connCache = c
        return c
    }

    /** Alasan probe/sesi terakhir gagal, untuk diagnosis. Ditampilkan di log saat koneksi dinyatakan putus. */
    @Volatile var lastDropReason: String = ""
    /** Rincian teknis penyebab putus (nama exception, jawaban mentah). Hanya untuk baris DETAIL di log, tidak untuk popup. */
    @Volatile var lastDropDetail: String = ""

    private fun setDrop(reason: String, detail: String = "") { lastDropReason = reason; lastDropDetail = detail }

    /**
     * Cek ringan apakah koneksi benar-benar hidup (bukan sekadar flag): jalankan `echo` di device.
     * Dipanggil dari thread pemantau; menunggu paling lama 6 detik.
     */
    fun probe(addr: String? = lastAddr): Boolean {
        if (!libConnected) { setDrop(DROP_CLOSED); return false }
        val ok = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val holder = AtomicReference<AdbStream?>(null)
        val why = AtomicReference("")
        val whyDetail = AtomicReference("")
        // openStream() sendiri bisa menggantung di soket yang mati, jadi seluruhnya dijalankan di thread terpisah
        // dan pemanggil (thread pemantau) menunggu paling lama 6 detik.
        Thread {
            try {
                // Tes TCP langsung ke adbd (tanpa library): gagal seketika bila Wi-Fi mati / HP target tak terjangkau.
                // Sesi USB lewat jembatan loopback: JANGAN buka soket uji ke 127.0.0.1:<port>. Jembatan menganggapnya
                // sebagai klien baru dan menutup soket ADB yang sedang aktif, sehingga probe selalu gagal.
                val loopback = addr?.startsWith("127.0.0.1:") == true
                if (loopback || reachable(addr)) {
                    val s = manager.openStream("shell:echo ok")
                    holder.set(s)
                    val out = readAll(s.openInputStream())
                    ok.set(out.contains("ok"))
                    if (!ok.get()) {
                        why.set("HP target memberi jawaban tak terduga")
                        whyDetail.set("Jawaban: \"${Fmt.clip(out.trim(), 40)}\"")
                    }
                } else {
                    why.set("HP target tidak terjangkau di jaringan")
                    whyDetail.set("Koneksi TCP ke $addr gagal")
                }
            } catch (e: Throwable) {
                why.set("pemeriksaan koneksi gagal"); whyDetail.set(Fmt.cause(e))
            } finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        val finished = done.await(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        daemon { holder.get()?.close() }
        if (!finished) { why.set("HP target tidak menjawab dalam $PROBE_TIMEOUT_S dtk"); whyDetail.set("") }
        val result = finished && ok.get()
        if (!result) setDrop(why.get().ifEmpty { "pemeriksaan koneksi gagal" }, whyDetail.get())
        return result
    }

    private fun reachable(addr: String?): Boolean {
        val (h, p) = AdbMessages.splitHostPort(addr ?: return true) ?: return true
        return try {
            Socket().use { it.connect(InetSocketAddress(h, p), 2_000) }
            true
        } catch (e: Throwable) { false }
    }

    /** Sesi dinyatakan mati (probe gagal berulang): tandai putus dan tutup soket di thread latar. */
    fun markDead() {
        logWarn("Pemeriksaan koneksi gagal berulang, koneksi ditutup")
        forcedDown = true
        connCache = false
        daemon { mgr?.disconnect() }
    }

    fun pair(addr: String, code: String): Boolean {
        if (addr.isEmpty() || code.isEmpty()) {
            logWarn("Isi IP:Port pairing dan kode pairing terlebih dahulu")
            return false
        }
        val (host, port) = AdbMessages.splitHostPort(addr) ?: run {
            logErr("${RunTitle.PAIR} gagal: alamat $addr tidak valid. Gunakan format IP:Port")
            return false
        }
        logCmd("adb pair $addr ******")
        return try {
            // Ditampung sebagai Any? agar aman baik pair() mengembalikan Boolean maupun void.
            val result: Any? = manager.pair(host, port, code)
            if (result == false) {
                logErr("${RunTitle.PAIR} gagal: ditolak HP target. Cek kode dan port pairing")
                false
            } else {
                logOk("Pairing berhasil: $addr")
                true
            }
        } catch (e: Throwable) {
            logErr("${RunTitle.PAIR} gagal: proses pairing tidak selesai. Cek kode dan port pairing, lalu coba lagi")
            logCause(e)
            false
        }
    }

    /** Cari port connect HP target (layanan mDNS `_adb-tls-connect`) pada [host] yang sama. Null bila tidak ketemu. */
    fun findConnectAddr(host: String, timeoutMs: Long = 15_000): String? {
        logStep("Mencari port connect $host lewat mDNS…")
        return AdbDiscovery.find(appContext, AdbDiscovery.TYPE_CONNECT, timeoutMs, AtomicBoolean(false)) { _, h, p ->
            h == host && reachable("$h:$p")
        }?.addr
    }

    @Volatile private var lastConnectReason = ""
    internal val connectReasonOrDefault: String get() = lastConnectReason.ifBlank { "tidak terhubung" }
    /**
     * Rantai exception teknis dari percobaan connect terakhir; dicetak sebagai DETAIL oleh pemanggil yang menahan log
     * (USB, sambung ulang).
     */
    @Volatile internal var lastConnectDetail = ""

    /**
     * [verbose] false = jangan cetak perintah dan kegagalan akhir (dipakai pemanggil yang mengulang sendiri, mis. USB).
     * [label] = nama yang ditampilkan di baris ✓ menggantikan alamat
     * (USB memakai port loopback internal yang tidak berarti bagi teknisi).
     */
    fun connect(addr: String, verbose: Boolean = true, label: String? = null): Boolean {
        if (addr.isEmpty()) {
            logWarn("Isi IP:Port connect terlebih dahulu")
            return false
        }
        val (host, port) = AdbMessages.splitHostPort(addr) ?: run {
            logErr("${RunTitle.CONNECT} gagal: alamat $addr tidak valid. Gunakan format IP:Port")
            return false
        }
        // connect() di library langsung "sukses" bila masih ada koneksi lama (mis. ke IP/port lain),
        // jadi putuskan dulu agar benar-benar menyambung ke alamat yang diminta.
        if (forcedDown || libConnected) {
            quietly { manager.disconnect() }
        }
        if (viaUsb && !addr.startsWith("127.0.0.1:")) { usbBridge.stop(); viaUsb = false }
        var ok = false
        var reason = ""   // singkat, tampil di baris percobaan ulang
        var advice = ""   // saran perbaikan, hanya di baris ✗ akhir
        var detail = ""   // rantai exception teknis, hanya sebagai baris rincian
        forcedDown = false
        if (verbose) logCmd("adb connect $addr")
        for (attempt in 1..CONNECT_ATTEMPTS) {
            if (attempt > 1) {
                if (verbose) logWarn("Percobaan ${attempt - 1}/$CONNECT_ATTEMPTS gagal ($reason), mencoba lagi…")
                Thread.sleep(CONNECT_RETRY_DELAY_MS)
            }
            detail = ""
            try {
                // Jembatan USB hanya menerima koneksi yang disiapkan di sini (lihat UsbAdbBridge.armNextConnection).
                if (host == "127.0.0.1" && port == usbBridge.localPort) usbBridge.armNextConnection()
                val result: Any? = manager.connect(host, port)
                if (result == false || !manager.isConnected) {
                    reason = "tidak terhubung"
                    advice = "Pastikan Wireless debugging aktif di HP target dan port connect benar"
                } else if (probe(addr)) {
                    // Terbukti: device menjawab perintah shell, bukan sekadar flag.
                    ok = true
                    lastAddr = addr
                    keepAlive(true)
                    break
                } else {
                    reason = "HP target tidak menjawab"
                    advice = "Pastikan sudah di-pair, Wireless debugging aktif, dan port connect benar"
                    quietly { manager.disconnect() }
                }
            } catch (e: Throwable) {
                android.util.Log.e("AdbConnect", "connect $addr", e)
                reason = AdbMessages.connectErrorReason(e)
                advice = "Pastikan satu jaringan Wi-Fi dan Wireless debugging aktif. " +
                    "Port connect berubah setiap Wireless debugging dinyalakan ulang, jadi cek port terbaru"
                detail = generateSequence(e) { it.cause }.take(4)
                    .joinToString(" <- ") { Fmt.cause(it) }
                quietly { manager.disconnect() }
            }
        }
        lastConnectReason = reason
        lastConnectDetail = detail
        syncConnected()
        if (ok) {
            logOk("Terhubung: ${label ?: addr}")
        } else if (verbose) {
            logErr("${RunTitle.CONNECT} gagal: $reason. $advice")
            if (detail.isNotEmpty()) logDetail(detail)
        }
        return ok
    }

    fun disconnect(): Boolean {
        logCmd("adb disconnect")
        lastAddr = null
        sessionLabel = null
        keepAlive(false)
        return try {
            manager.disconnect()
            connCache = false
            if (viaUsb) { usbBridge.stop(); viaUsb = false }
            logOk("Koneksi diputus")
            true
        } catch (e: Throwable) {
            logErr(
                "${RunTitle.DISCONNECT} gagal: koneksi tidak bisa ditutup dengan bersih. " +
                    "Cabut kabel atau matikan Wireless debugging di HP target, lalu cek status koneksi"
            )
            logCause(e)
            false
        }
    }

    /**
     * true selama satu operasi (install, set owner, dst) berjalan di thread kerja. Hidup di level aplikasi, bukan di
     * Activity, supaya Activity baru (setelah yang lama dihancurkan sistem) tidak memulai operasi kedua di sambungan
     * yang sama.
     */
    @Volatile var opRunning = false

    /** Hasil install terakhir (untuk popup ringkasan di UI). */
    class InstallSummary(val total: Int, val ok: Int, val failed: List<Pair<String, String>>)
    @Volatile var lastInstall: InstallSummary? = null

    /**
     * Bersihkan sesi setelah `adb tcpip`: adbd restart sehingga sesi USB pasti putus (tanpa log error).
     * Dipakai oleh [enableTcpip] di AdbCommands.kt.
     */
    internal fun closeSessionAfterTcpip() {
        lastAddr = null
        sessionLabel = null
        keepAlive(false)
        quietly { manager.disconnect() }
        connCache = false
        quietly { usbBridge.stop() }
        viaUsb = false
    }

    // ---------- internal ----------

    internal fun ensureConnected(): Boolean {
        if (libConnected) return true
        logWarn(MSG_NOT_CONNECTED)
        return false
    }

    /** true bila perintah shell terakhir kehabisan waktu (dipakai pemanggil untuk membedakan "kosong" dari "tidak menjawab"). */
    @Volatile internal var lastShellTimedOut = false
}
