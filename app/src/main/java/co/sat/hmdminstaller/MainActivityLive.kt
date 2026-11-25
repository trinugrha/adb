package co.sat.hmdminstaller

import android.view.View
import android.view.ViewGroup

/*
 * Kunci PIN dan pemantau status koneksi live, dipisah dari MainActivity.
 * Dipakai sebagai fungsi ekstensi MainActivity.
 */

// ---------- Kunci PIN ----------

internal fun MainActivity.setupPin() {
    pinStore = PinStore(this)
    pinStore.ensureDefaultAsync(PinStore.DEFAULT_PIN)
    onBackPressedDispatcher.addCallback(this, backWhileLocked)
    txtSession = findViewById(R.id.txtSession)
    findViewById<View>(R.id.btnLogout).setOnClickListener { confirmExit() }
    findViewById<View>(R.id.btnSettings).setOnClickListener { showVersionSettings() }
    findViewById<View>(R.id.btnModels).setOnClickListener { showModelSettings() }
    showLock()
}

internal fun MainActivity.showLock() {
    if (isFinishing || lockView != null) return
    val v = PinLockView(this, pinStore) { removeLock() }
    lockView = v
    hidePopupsForLock()
    addContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    backWhileLocked.isEnabled = true
    currentFocus?.clearFocus()
}

/** Sisa waktu sesi (ms) sebelum aplikasi terkunci otomatis. Hitung mundur dimulai saat login dan TIDAK direset oleh sentuhan layar. */
internal fun MainActivity.sessionRemainingMs() = session.remainingMs()

/** Dipanggil tiap detik dari pemantau. Saat operasi (install dll) berjalan, timer DITAHAN (pause) di sisa waktu terakhir, tidak direset. */
internal fun MainActivity.updateSessionTimer() {
    if (lockView != null) return
    session.tick(busy)
    val left = sessionRemainingMs()
    if (left <= 0) { showLock(); return }
    val sec = SessionTimer.seconds(left)
    txtSession.text = SessionTimer.label(sec)
    txtSession.setTextColor(
        colorOf(if (sec <= SessionTimer.WARN_SECONDS) R.color.header_warning else R.color.header_subtext)
    )
}

internal fun MainActivity.removeLock() {
    lockView?.let { (it.parent as? ViewGroup)?.removeView(it) }
    lockView = null
    session.restart()
    updateSessionTimer()
    showPendingConnProblem()
}

// ---------- Status koneksi live ----------

/** Jalan di thread pemantau tiap 1 detik. */
internal fun MainActivity.pollConnection() {
    quietly {
        val wasBusy = busy
        var alive = adb.syncConnected()
        var cableGone = false
        // Kabel USB dicabut: terdeteksi seketika (tanpa menunggu probe 3x gagal).
        if (!wasBusy && adb.usbCableGone) {
            adb.dropUsb()
            alive = false
            cableGone = true
            probeFails = 0
        }
        val now = System.currentTimeMillis()
        // Probe sungguhan tiap ±10 detik (hanya saat tidak ada operasi lain) untuk mendeteksi koneksi mati diam-diam.
        if (alive && !wasBusy && now - lastProbe >= 10_000) {
            lastProbe = now
            // Cek busy lagi di dalam lock: worker menunggu lock yang sama sebelum mulai bekerja.
            synchronized(adb.probeLock) {
                if (!busy) {
                    alive = adb.probe()
                    // Toleransi: HP target yang sibuk (dexopt/verifikasi APK) bisa telat membalas; putus baru
                    // dinyatakan setelah 3x gagal beruntun.
                    if (alive) probeFails = 0 else {
                        val n = ++probeFails
                        if (n < 3) alive = true else { probeFails = 0; if (!adb.isViaUsb) adb.markDead() }
                    }
                }
            }
        }
        runOnUiThread { applyLiveState(alive, wasBusy, cableGone) }
    }
}

internal fun MainActivity.applyLiveState(alive: Boolean, polledWhileBusy: Boolean, cableGone: Boolean = false) {
    if (isFinishing) return
    updateSessionTimer()
    val addr = target.connectedAddr
    // Saat operasi berjalan (mis. connect/install) jangan simpulkan putus: hasilnya bisa basi.
    if (addr != null && !alive && !polledWhileBusy && !busy) {
        val cause = if (cableGone) " karena kabel USB dicabut" else ""
        log(LogLevel.ERR, "Koneksi ke $addr terputus$cause · ${Fmt.clock(System.currentTimeMillis())}")
        adb.lastDropReason.takeIf { it.isNotEmpty() }?.let { log(LogLevel.DETAIL, "Penyebab: $it") }
        adb.lastDropDetail.takeIf { it.isNotEmpty() }?.let { log(LogLevel.DETAIL, it) }
        val reasonBullet = adb.lastDropReason.takeIf { it.isNotEmpty() }
            ?.let { "\n\nPenyebab:\n• ${it.replaceFirstChar { c -> c.uppercase() }}" }
            .orEmpty()
        showConnProblem(
            "Koneksi Terputus",
            "Koneksi ke $addr terputus$cause." +
                reasonBullet +
                "\n\nLangkah berikutnya: sambungkan ulang lewat ${AdbMessages.HOW_TO_CONNECT}." +
                "\n\n${AdbMessages.SEE_LOG}"
        )
        showUsbDevice(null)
        lostAddr = addr
        adb.sessionLabel = null
        forgetTarget()
    }
    refreshHeader()
}
