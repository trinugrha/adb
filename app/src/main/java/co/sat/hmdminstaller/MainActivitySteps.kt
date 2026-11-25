package co.sat.hmdminstaller

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import co.sat.hmdminstaller.MainActivity.Companion.QR_PAIR_TIMEOUT_MS
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Pair dengan QR dan langkah koneksi ADB (jalan di thread kerja), dipisah dari MainActivity.
 * Dipakai sebagai fungsi ekstensi MainActivity.
 */

// ---------- Pair dengan QR ----------

/** Tampilkan QR; HP target memindainya, lalu aplikasi menemukan layanan pairing via mDNS dan pair + connect otomatis. */
internal fun MainActivity.showQrPairing() {
    if (busy) return
    val session = AdbDiscovery.newQrSession()
    val cancel = AtomicBoolean(false)
    val size = px(R.dimen.qr_size)
    val img = ImageView(this).apply {
        setImageBitmap(AdbDiscovery.qrBitmap(session.payload, size))
        layoutParams = LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }
    val hint2 = TextView(this).apply {
        text = "HP ini dan HP target harus satu jaringan Wi-Fi."
        setTextSizeRes(R.dimen.text_body)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.3f)
        setTextColor(colorOf(R.color.muted))
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(img)
        addView(
            hint2,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(R.dimen.space_md) }
        )
    }
    val dialog = showPopup(
        "Scan QR untuk Pair",
        "HP target: Opsi Developer › Wireless debugging › Pair device with QR code, lalu arahkan kamera ke QR ini.",
        icon = R.drawable.ic_wifi, content = box, positive = null, negative = getString(R.string.action_cancel),
        onDismiss = { cancel.set(true) }
    )
    val shownAt = System.currentTimeMillis()
    io.execute {
        val found = AdbDiscovery.find(this, AdbDiscovery.TYPE_PAIRING, QR_PAIR_TIMEOUT_MS, cancel) { name, _, _ ->
            name == session.serviceName
        }
        if (found == null) {
            if (!cancel.get()) runOnUiThread {
                dialog?.dismiss()
                logHead(RunTitle.PAIR_QR, shownAt)
                log(
                    LogLevel.ERR,
                    "${RunTitle.PAIR_QR} gagal: ${AdbMessages.qrPairTimeout(QR_PAIR_TIMEOUT_MS / 1000)}. Pastikan satu Wi-Fi, lalu coba lagi"
                )
                logEnd(RunTitle.PAIR_QR, false, shownAt)
                showConnProblem(
                    "${RunTitle.PAIR_QR} Gagal",
                    RunMessages.failureWithCause(
                        AdbMessages.qrPairTimeout(QR_PAIR_TIMEOUT_MS / 1000), RunMessages.QR_PAIR_CHECKLIST
                    )
                )
            }
            return@execute
        }
        runOnUiThread {
            dialog?.dismiss()
            if (busy) return@runOnUiThread
            edtPairIp.setText(found.host)
            edtPairPort.setText(found.port.toString())
            edtPairCode.setText(session.password)
            run(RunTitle.PAIR_QR) { doPairQr(found, session.password) }
        }
    }
}

internal fun MainActivity.doPairQr(f: AdbDiscovery.Found, password: String): Boolean {
    if (!doPair(f.addr, password, showNext = false)) return false
    // Setelah pair, cari port connect HP yang sama lalu sambung otomatis.
    // Beri waktu adbd di HP target siap menerima koneksi TLS setelah pairing.
    try { Thread.sleep(2_000) } catch (e: InterruptedException) { }
    val ca = adb.findConnectAddr(f.host)
    if (ca == null) {
        log(LogLevel.WARN, "Port connect tidak ditemukan otomatis")
        log(LogLevel.STEP, "Berikutnya: ${AdbMessages.CONNECT_MANUALLY}")
        return true
    }
    val (ip, port) = ca.substringBeforeLast(':') to ca.substringAfterLast(':')
    runOnUiThread { edtConnIp.setText(ip); edtConnPort.setText(port) }
    // Hasil connect menentukan penutup proses: jangan "✓ Selesai" tepat setelah "✗ Connect gagal".
    return doConnect(ca)
}

// ---------- Langkah (jalan di thread kerja) ----------

/** [showNext] false untuk Pair dengan QR: connect dilanjutkan otomatis, jadi petunjuk "Berikutnya" tidak perlu. */
internal fun MainActivity.doPair(addr: String, code: String, showNext: Boolean = true): Boolean {
    val ok = adb.pair(addr, code)
    if (ok) {
        // Pairing berhasil: IP connect otomatis diisi sama dengan IP pairing (HP target sama).
        // Port connect berbeda dari port pairing, jadi hanya IP yang diisi; port diisi manual.
        val pairIp = addr.substringBeforeLast(':').trim()
        runOnUiThread {
            if (pairIp.isNotEmpty()) edtConnIp.setText(pairIp)
            if (showNext && pairIp.isNotEmpty()) {
                log(LogLevel.STEP, "IP connect diisi otomatis: $pairIp")
                log(LogLevel.STEP, "Berikutnya: ${AdbMessages.NEXT_AFTER_PAIR}")
                // Arahkan input ke kolom port Connect dan buka keyboard, supaya port bisa langsung diketik.
                edtConnPort.requestFocus()
                edtConnPort.postDelayed({
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(edtConnPort, InputMethodManager.SHOW_IMPLICIT)
                }, 300)
            }
            refreshHeader()
        }
    }
    return ok
}

internal fun MainActivity.doConnect(addr: String): Boolean {
    val ok = adb.connect(addr)
    if (ok) {
        markConnected(addr)
        showUsbDevice(null)
    } else {
        // Connect gagal: koneksi lama sudah diputus, jangan tampilkan alamat lama sebagai terhubung.
        forgetTarget()
    }
    runOnUiThread { refreshHeader() }
    return ok
}

/** Tampilkan nama device yang dipilih di tab USB (null = sembunyikan). Aman dipanggil dari thread mana pun. */
internal fun MainActivity.showUsbDevice(label: String?) = runOnUiThread {
    findViewById<TextView>(R.id.txtUsbDevice).apply {
        text = if (label == null) "" else "Perangkat: $label"
        visibility = if (label == null) View.GONE else View.VISIBLE
    }
}

internal fun MainActivity.doConnectUsb(t: UsbAdbBridge.Target): Boolean {
    val label = adb.connectUsb(t)
    if (label != null) {
        markConnected(label)
    } else {
        forgetTarget()
        showUsbDevice(null)
    }
    runOnUiThread { refreshHeader() }
    return label != null
}

/** Catat sambungan baru ke [label] lalu baca status awal HP target (panggil dari thread kerja). */
internal fun MainActivity.markConnected(label: String) {
    target.connectedAddr = label
    target.connectedSince = System.currentTimeMillis()
    adb.sessionLabel = label
    adb.sessionSince = target.connectedSince
    lostAddr = null
    lastProbe = target.connectedSince
    skipInfoRefresh = true
    updateOwner()
}

/** Cek ulang device owner di HP target (panggil dari thread kerja). */
internal fun MainActivity.updateOwner() {
    target.ownerState = adb.queryDeviceOwner()
    // Informasi Status (launcher default, instalasi aplikasi, izin) sengaja TIDAK dibaca di sini:
    // baru dibaca saat pengguna mengetuk kartu Informasi Status.
    target.kasperskyInstalled = adb.queryKasperskyInstalled()
    target.hmdmInstalled = adb.queryHmdmInstalled()
    target.tcpipActive = adb.queryTcpipActive()
    runOnUiThread { refreshHeader() }
}

/** `adb tcpip 5555`: adbd di HP target restart dalam mode TCP, sesi USB ini otomatis putus. */
internal fun MainActivity.doEnableTcpip(): Boolean {
    // Bila TCP/IP sudah aktif di HP target, sesi USB dibiarkan tetap tersambung (tidak ada yang diubah).
    val already = adb.queryTcpipActive() == true
    val ok = adb.enableTcpip()
    if (ok && already) { target.tcpipActive = true; runOnUiThread { refreshHeader() }; return true }
    if (ok) { forgetTarget(); lostAddr = null; showUsbDevice(null) }
    runOnUiThread { refreshHeader() }
    return ok
}

/** Lupakan sambungan dan semua status yang pernah dibaca dari HP target (koneksi putus atau gagal). */
internal fun MainActivity.forgetTarget() = target.reset()

internal fun MainActivity.doDisconnect(): Boolean {
    val ok = adb.disconnect()
    if (ok) { forgetTarget(); lostAddr = null; showUsbDevice(null) }
    runOnUiThread { refreshHeader() }
    return ok
}

internal fun MainActivity.doSetOwner(): Boolean {
    val ok = adb.setDeviceOwner()
    runOnUiThread { refreshHeader() }
    return ok
}
