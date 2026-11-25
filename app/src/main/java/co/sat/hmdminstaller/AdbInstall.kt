package co.sat.hmdminstaller

import co.sat.hmdminstaller.AdbController.Companion.CONNECT_RETRY_DELAY_MS
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Pemasangan APK ke HP target (setara `adb install -r`), termasuk pengaman macet. Dipisah dari AdbController.

/** Percobaan install per APK (satu kali ulang bila macet atau gagal sementara). */
private const val INSTALL_ATTEMPTS = 2
/** Jeda minimum antar laporan progres pengiriman APK. */
private const val PROGRESS_INTERVAL_MS = 100L
/** Ukuran paket kirim APK lewat Wi-Fi. */
private const val WIFI_CHUNK_BYTES = 32 * 1024
/** Cara kirim APK lewat USB: paket 4 KB dengan ZLP (seperti adb di PC). */
private const val USB_CHUNK_BYTES = 4 * 1024
/** Batas tanpa kemajuan saat APK dikirim, dan batas menunggu hasil pm setelah APK terkirim penuh. */
private const val STALL_SENDING_MS = 20_000L
private const val STALL_REPLY_MS = 180_000L
/** Hasil installOne bila pengiriman macet; dipakai untuk mengenali kasus ini tanpa mencocokkan teks lagi. */
private const val STALL_ERROR = "Error: pengiriman macet (tidak ada kemajuan)"
/** Hasil installOne bila HP target hilang dari jaringan saat pengiriman (mis. Wi-Fi HP target mati). */
private const val LOST_ERROR = "Error: koneksi ke HP target terputus saat pengiriman"
/** Tanpa kemajuan selama ini, mulai cek apakah HP target masih terjangkau di jaringan. */
private const val SUSPECT_IDLE_MS = 6_000L
private const val REACH_CHECK_INTERVAL_MS = 2_000L
/** Cek jangkauan gagal beruntun sebanyak ini = HP target dinyatakan hilang dari jaringan. */
private const val REACH_FAILS_TO_LOST = 2

/** Pasang semua APK berurutan; yang gagal tidak menghentikan yang lain. */
internal fun AdbController.install(apks: List<ApkItem>): Boolean {
    lastInstall = null
    if (!ensureConnected()) return false
    if (apks.isEmpty()) {
        logWarn("Belum ada APK. Pilih file APK terlebih dahulu")
        return false
    }
    var ok = 0
    val failed = mutableListOf<Pair<String, String>>() // nama APK -> alasan
    apks.forEachIndexed { i, apk ->
        logStep("[${i + 1}/${apks.size}] ${apk.name} (${apk.sizeText})")
        if (!apk.file.exists()) {
            logErr("Gagal: file tidak ditemukan di penyimpanan sementara. Pilih ulang file APK")
            failed += apk.name to "file tidak ditemukan"
            return@forEachIndexed
        }
        if (!libConnected && !tryReconnect()) {
            failed += apk.name to "koneksi terputus"
            return@forEachIndexed
        }
        logCmd("adb install -r ${apk.name}")
        onProgress?.invoke(i + 1, apks.size, apk.name, 0L, apk.file.length())
        val t0 = System.currentTimeMillis()
        // Bila macet / gagal sementara: satu kali ulang.
        var tries = 0
        var out = ""
        while (true) {
            out = installTry(apk.file, i + 1, apks.size, apk.name)
            tries++
            if (isSuccess(out) || !AdbMessages.isTransient(out) || tries >= INSTALL_ATTEMPTS) break
            // Alasan singkat saja: saran lengkap baru ditampilkan di baris ✗ bila akhirnya tetap gagal.
            val reason = installFailureReason(out, advice = false)
            logWarn("Percobaan $tries/$INSTALL_ATTEMPTS gagal ($reason), mencoba lagi…")
            if (libConnected) Thread.sleep(CONNECT_RETRY_DELAY_MS) else tryReconnect()
            if (!libConnected) break
        }
        if (isSuccess(out)) {
            ok++
            logOk("Terpasang · ${Fmt.duration(System.currentTimeMillis() - t0)}")
        } else {
            val reason = installFailureReason(out)
            // Ringkasan memakai alasan singkat; saran lengkap sudah tampil di baris ✗ di atas.
            failed += apk.name to installFailureReason(out, advice = false)
            logErr("Gagal: $reason")
            if (out != STALL_ERROR && out != LOST_ERROR) logRaw(out, reason)
        }
    }
    lastInstall = AdbController.InstallSummary(apks.size, ok, failed.toList())
    // Satu APK: hasilnya sudah jelas dari baris di atas dan baris penutup, tak perlu ringkasan.
    if (apks.size > 1) {
        logSummary("Ringkasan: $ok berhasil, ${failed.size} gagal dari ${apks.size} APK")
        failed.forEach { (name, reason) -> logErr("$name: $reason") }
    }
    return failed.isEmpty()
}

internal fun AdbController.installAndOwner(apks: List<ApkItem>): Boolean {
    if (!ensureConnected()) return false
    // Walau sebagian APK gagal, tetap coba set device owner (HMDM mungkin sudah terpasang).
    val installOk = install(apks)
    logStep("Lanjut: ${RunTitle.SET_OWNER}")
    val ownerOk = setDeviceOwner()
    return installOk && ownerOk
}

private fun AdbController.installFailureReason(out: String, advice: Boolean = true): String =
    AdbMessages.installFailureReason(
        out, advice, stalled = out == STALL_ERROR, viaUsb = isViaUsb, lost = out == LOST_ERROR
    )

private fun AdbController.installTry(file: File, idx: Int, total: Int, name: String): String {
    val out = try { installOne(file, idx, total, name) } catch (e: Throwable) { shellError(e) }
    // Kegagalan kirim (soket putus, dst) padahal HP target sudah tidak terjangkau = jaringan HP target hilang.
    if (!isSuccess(out) && out.startsWith("Error:") && out != STALL_ERROR && !isTargetReachable()) return LOST_ERROR
    return out
}

/**
 * Setara `adb install -r`: stream APK ke `cmd package install -S <ukuran>` lewat service exec.
 *
 * Pengiriman berjalan di thread tersendiri, sedangkan thread ini hanya mengawasi. Alasannya: bila Wi-Fi HP target mati,
 * soket TCP tidak putus (tanpa RST), `write()` tertahan di soket mati, dan `stream.close()` ikut tertahan oleh lock yang
 * sama. Dulu pengawas ada di thread yang sama dengan pengirim, sehingga install menggantung tanpa pesan apa pun.
 * Sekarang thread ini selalu kembali dengan hasil (sukses, macet, atau terputus) walau thread pengirim tertahan.
 */
private fun AdbController.installOne(file: File, idx: Int, count: Int, name: String): String {
    val total = file.length()
    if (isViaUsb) {
        usbBridge.sendZlp = true
        usbBridge.resetStats()
    }
    val lastActivity = AtomicLong(System.currentTimeMillis())
    val sendingDone = AtomicBoolean(false)
    val aborted = AtomicBoolean(false)
    val sent = AtomicLong(0L)
    val streamRef = AtomicReference<AdbStream?>(null)
    val result = AtomicReference<String?>(null)
    val done = CountDownLatch(1)
    Thread {
        try {
            result.set(installIo(file, idx, count, name, total, lastActivity, sendingDone, aborted, sent, streamRef))
        } catch (e: Throwable) {
            result.set(shellError(e))
        } finally {
            done.countDown()
        }
    }.apply { isDaemon = true; start() }

    try {
        var unreachable = 0
        var lastCheck = 0L
        while (!done.await(1, TimeUnit.SECONDS)) {
            val now = System.currentTimeMillis()
            val idle = now - lastActivity.get()
            val limit = if (sendingDone.get()) STALL_REPLY_MS else STALL_SENDING_MS
            var lost = false
            if (idle >= SUSPECT_IDLE_MS) {
                if (now - lastCheck >= REACH_CHECK_INTERVAL_MS) {
                    lastCheck = now
                    if (isTargetReachable()) unreachable = 0 else {
                        unreachable++
                        if (unreachable == 1) logWarn("HP target tidak menjawab di jaringan, memeriksa ulang…")
                    }
                }
                lost = unreachable >= REACH_FAILS_TO_LOST
            } else {
                unreachable = 0
            }
            if (lost) {
                aborted.set(true)
                logWarn("Koneksi ke HP target hilang saat pengiriman (terkirim ${Fmt.size(sent.get())} dari ${Fmt.size(total)})")
                abortSession("HP target tidak terjangkau di jaringan (Wi-Fi HP target mati atau di luar jangkauan)")
                return LOST_ERROR
            }
            if (idle > limit) {
                aborted.set(true)
                logStall(sendingDone.get(), sent.get(), total)
                // Wi-Fi: putuskan soket sungguhan supaya pengirim yang tertahan ikut lepas. USB: cukup tutup stream.
                if (!isViaUsb) abortSession("HP target tidak menjawab")
                return STALL_ERROR
            }
        }
        return result.get() ?: shellError(IllegalStateException("pengiriman berhenti tanpa hasil"))
    } finally {
        // close() bisa tertahan di soket mati, jadi tidak boleh dijalankan di thread ini.
        daemon { streamRef.get()?.close() }
    }
}

/** Isi pengiriman APK + membaca jawaban pm. Dijalankan di thread terpisah oleh [installOne]. */
private fun AdbController.installIo(
    file: File, idx: Int, count: Int, name: String, total: Long,
    lastActivity: AtomicLong, sendingDone: AtomicBoolean, aborted: AtomicBoolean,
    sent: AtomicLong, streamRef: AtomicReference<AdbStream?>
): String {
    // Lewat USB, adbd di HP lama (Android 7/8, mis. SUNMI) membatasi payload per paket (4 KB).
    // Paket WRTE yang lebih besar membuat transfer macet di 0%. Lewat Wi-Fi 32 KB aman.
    // Tanpa ZLP sebagian HP macet di 0%, jadi lewat USB ZLP selalu dikirim.
    val chunk = if (isViaUsb) USB_CHUNK_BYTES else WIFI_CHUNK_BYTES
    val stream = manager.openStream("exec:cmd package install -r -S $total")
    streamRef.set(stream)
    if (aborted.get()) return ""
    var writeError: IOException? = null
    try {
        val os = stream.openOutputStream()
        var lastReport = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(chunk)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                os.write(buf, 0, n)
                val s = sent.addAndGet(n.toLong())
                if (aborted.get()) return ""
                val now = System.currentTimeMillis()
                lastActivity.set(now)
                if (now - lastReport >= PROGRESS_INTERVAL_MS || s >= total) {
                    lastReport = now
                    onProgress?.invoke(idx, count, name, s, total)
                }
            }
        }
        os.flush()
        sendingDone.set(true)
        lastActivity.set(System.currentTimeMillis())
    } catch (e: IOException) {
        // Device menutup stream lebih awal (mis. install ditolak). Tetap baca pesan aslinya.
        writeError = e
    }
    if (aborted.get()) return ""
    // Jangan tunggu EOF: device sering tidak menutup stream walau "Success" sudah tercetak (install terlihat
    // menggantung).
    val out = readAll(stream.openInputStream(), pmDone)
    // Exception masuk lewat jalur yang sama dengan kegagalan shell lain ("Error: <Kelas>: <pesan>"), bukan e.message
    // mentah, supaya baris DETAIL-nya seragam dan nama kelas tidak hilang.
    if (out.isBlank() && writeError != null) return shellError(writeError)
    return out
}

private fun AdbController.logStall(sendingDone: Boolean, sent: Long, total: Long) {
    // Setelah APK terkirim penuh, "terkirim X dari X" tidak berguna; cukup sebut ukurannya.
    if (sendingDone) {
        logWarn(
            "Pengiriman macet: tidak ada jawaban dari HP target selama ${STALL_REPLY_MS / 1000} dtk " +
                "setelah APK terkirim penuh (${Fmt.size(total)})"
        )
    } else {
        logWarn(
            "Pengiriman macet: tidak ada kemajuan selama ${STALL_SENDING_MS / 1000} dtk " +
                "(terkirim ${Fmt.size(sent)} dari ${Fmt.size(total)})"
        )
    }
    if (isViaUsb) logDetail("USB: ${usbBridge.stats()}")
}
