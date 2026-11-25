package co.sat.hmdminstaller

import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

// Popup hasil proses, progres install, daftar APK, dan keluar aplikasi: bagian dari MainActivity yang dipisah supaya
// file utamanya ringkas.

/** Minta konfirmasi sebelum keluar; peringatkan bila masih ada proses berjalan. */
internal fun MainActivity.confirmExit() {
    if (isFinishing) return
    val warn = if (busy) "\n\nPerhatian: masih ada proses yang berjalan dan akan ikut terhenti." else ""
    showConfirm(
        "Keluar dari Aplikasi?",
        "Koneksi ADB akan diputus, layanan latar dihentikan, dan daftar APK dikosongkan.$warn",
        "Keluar", icon = R.drawable.ic_logout, tone = PopupTone.NAVY
    ) { exitApp() }
}

/** Keluar penuh: putuskan ADB, hentikan ConnectionService (lewat adb.close()), bersihkan daftar APK, lalu tutup aplikasi. */
internal fun MainActivity.exitApp() {
    findViewById<View>(R.id.btnLogout).isEnabled = false
    val adbCtl = (application as App).adb
    val done = AtomicBoolean(false)
    val finishOnce = Runnable { if (done.compareAndSet(false, true)) finishAndRemoveTask() }
    // close() bisa tertahan bila soket sudah mati, jadi jalan di thread latar.
    Thread {
        quietly { adbCtl.close() }
        clearPickedApks()
        runOnUiThread(finishOnce)
    }.start()
    // Pengaman: tetap tutup aplikasi walau pemutusan macet lebih dari 3 detik.
    window.decorView.postDelayed(finishOnce, 3000)
}

/**
 * Perbarui status HP target (device owner, Kaspersky, launcher, TCP/IP) setelah tiap proses. Bila [skipInfo] false dan
 * model dikenali, instalasi dan izin ikut dibaca. Dipanggil dari thread kerja; gagal dibaca = dibiarkan.
 */
internal fun MainActivity.refreshTargetState(skipInfo: Boolean) {
    quietly {
        if (adb.isConnected) {
            val st = adb.queryDeviceOwner()
            if (st != OwnerState.Unknown) target.ownerState = st
            adb.queryKasperskyInstalled()?.let { target.kasperskyInstalled = it }
            adb.queryHmdmInstalled()?.let { target.hmdmInstalled = it }
            adb.queryTcpipActive()?.let { target.tcpipActive = it }
            if (!skipInfo && target.modelRecognized() && !target.modelAppListEmpty()) {
                val t = deviceType
                adb.queryRequiredPackages(t)?.let { target.appsInstalled = it; target.appsType = t }
                adb.queryHmdmPermissions()?.let { target.hmdmPerms = it; target.permType = t }
            }
        }
    }
}

/** Popup hasil proses [title] di UI thread: ringkasan APK gagal, daftar penyebab + saran, atau pesan berhasil. */
internal fun MainActivity.showRunResult(title: String, ok: Boolean) {
    val inst = adb.lastInstall
    val isInstall = RunTitle.isInstall(title)
    if (!ok && isInstall && inst != null && inst.total > 1 && inst.failed.isNotEmpty()) {
        val shown = inst.failed.take(8).joinToString("\n") { (name, why) -> "• $name: ${plainReason(why)}" }
        val more = if (inst.failed.size > 8) "\n• ${inst.failed.size - 8} APK lainnya" else ""
        showConnProblem(
            if (inst.ok == 0) "Semua APK Gagal Dipasang" else "${inst.failed.size} APK Gagal Dipasang",
            "${inst.ok} dari ${inst.total} APK berhasil dipasang, ${inst.failed.size} gagal.\n\n" +
                "Penyebab:\n$shown$more\n\n" +
                "Langkah berikutnya: perbaiki penyebabnya, lalu pasang ulang APK yang gagal." +
                (if (inst.ok > 0) " APK yang berhasil tidak perlu dipasang ulang." else "") +
                (if (title == RunTitle.INSTALL_AND_OWNER) "\n\nCatatan: langkah \"Aktifkan MDM Mode\" tetap dijalankan, hasilnya ada di log." else "") +
                "\n\n${AdbMessages.SEE_LOG}"
        )
    } else if (!ok) {
        val popupTitle = "$title Gagal"
        val hint = RunMessages.failureHint(title, installFailed = inst == null || inst.failed.isNotEmpty())
        // Tanpa baris ✗, pakai baris ! terakhir (bukan pesan percobaan ulang) sebagai penyebab. Baris ✗ dipecah: alasan
        // singkat jadi butir "Penyebab", saran (bila ada) jadi "Langkah berikutnya". Baris ! (fallback) dipecah dengan
        // cara yang sama, supaya "Belum terhubung ke HP target. Hubungkan ..." tidak jadi satu butir panjang.
        val errs = logPanel.runErrors.distinct().takeLast(3).map { splitAdvice(errReason(it)) }
            .ifEmpty {
                logPanel.runWarns.distinct().filter { !it.contains("mencoba lagi") }.takeLast(2).map { splitAdvice(it) }
            }
        val reasons = errs.joinToString("\n") { "• ${it.first.trimEnd('.')}" }
        val advice = errs.lastOrNull { it.second != null }?.second
        val msg = buildString {
            if (reasons.isNotEmpty()) append("Penyebab:\n").append(reasons)
            else append("Proses tidak berhasil dan tidak ada penyebab yang tercatat.")
            if (advice != null) append("\n\nLangkah berikutnya: ").append(advice.trimEnd('.')).append('.')
            if (hint.isNotEmpty()) append("\n\n").append(hint)
            append("\n\n").append(AdbMessages.SEE_LOG)
        }
        showConnProblem(popupTitle, msg)
    } else {
        val connNow = target.connectedAddr?.takeIf { adb.isConnected }
        val okMsg = RunMessages.success(title, connNow, inst)
        if (okMsg != null) showConnProblem("$title Berhasil", okMsg, ok = true)
    }
}

internal fun MainActivity.showInstallProgress(idx: Int, total: Int, name: String, sent: Long, size: Long) {
    if (isFinishing || !busy) return
    val box = findViewById<View>(R.id.progBox)
    val bar = findViewById<ProgressBar>(R.id.barInstall)
    val n = findViewById<TextView>(R.id.txtProgName)
    val info = findViewById<TextView>(R.id.txtProgInfo)
    val pctView = findViewById<TextView>(R.id.txtProgPct)
    val speedView = findViewById<TextView>(R.id.txtProgSpeed)
    val now = SystemClock.elapsedRealtime()
    if (idx != progIdx || sent == 0L) { progIdx = idx; progStartMs = now }
    if (!progShown) {
        progShown = true
        box.visibility = View.VISIBLE
        // Halaman tidak digulir otomatis; progres juga tampil di kartu loading.
    }
    n.text = if (total > 1) "Memasang APK $idx dari $total: $name" else "Memasang APK: $name"
    loading.update(if (total > 1) "Memasang APK $idx dari $total" else "Memasang APK")
    if (size > 0 && sent >= size) {
        bar.isIndeterminate = true
        pctView.text = "100%"
        speedView.text = ""
        info.text = "Terkirim ${Fmt.mb(size)}. Menunggu HP target memasang…"
    } else {
        bar.isIndeterminate = false
        val pct = if (size > 0) (sent * 100 / size).toInt() else 0
        bar.progress = if (size > 0) (sent * 1000 / size).toInt() else 0
        pctView.text = "$pct%"
        val el = now - progStartMs
        speedView.text = if (el >= 500 && sent > 0) Fmt.speed(sent, el) else ""
        info.text = "${Fmt.mb(sent)} dari ${Fmt.mb(size)}"
    }
}

internal fun MainActivity.hideInstallProgress() {
    if (!progShown) return
    progShown = false
    progIdx = -1
    findViewById<View>(R.id.progBox)?.visibility = View.GONE
}

/** Tampilkan daftar APK yang akan dipasang (urut sesuai nama) lengkap dengan ukuran & tombol hapus. */
internal fun MainActivity.renderApkList() {
    apkListBox.removeAllViews()
    if (apks.isEmpty()) { apkListBox.visibility = View.GONE; return }
    apkListBox.visibility = View.VISIBLE

    apkListBox.addView(TextView(this).apply {
        text = "DAFTAR APLIKASI YANG AKAN DIPASANG (${apks.size})"
        setTextSizeRes(R.dimen.text_caption)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        setTextColor(colorOf(R.color.muted))
        setPadding(px(R.dimen.space_xs), 0, 0, px(R.dimen.space_sm))
    })

    val canRemove = !busy && isLinked()
    apks.forEachIndexed { i, item ->
        apkListBox.addView(apkRow(i, item, canRemove) {
            if (busy || !isLinked()) return@apkRow
            apks.remove(item)
            if (!item.builtIn) io.execute { item.file.delete() }
            refreshApkList()
        })
    }
}
