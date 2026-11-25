package co.sat.hmdminstaller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import co.sat.hmdminstaller.MainActivity.Companion.PREF_NOTIF_ASKED
import java.io.File

// Langkah awal saat Activity dibuat: sambung ke App/ADB, izin notifikasi, dan jejak crash sebelumnya.

/** Hubungkan Activity ke controller ADB milik [App]: log, progres, service latar, dan pemulihan sesi lama. */
internal fun MainActivity.attachToApp() {
    val app = application as App
    // Pantau penggeseran dari recent apps (putus koneksi + kosongkan daftar APK).
    quietly { startService(Intent(this, CleanupService::class.java)) }
    adb = app.adb
    // Log (UI thread) → controller memakai callback ini dari thread kerja.
    val logCb: (LogLevel, String) -> Unit = { level, line -> runOnUiThread { log(level, line) } }
    logListenerRef = logCb
    app.logListener = logCb
    // Koneksi bisa masih hidup dari Activity sebelumnya: pulihkan tampilan, jangan sambung ulang.
    if (adb.isConnected && adb.sessionLabel != null) {
        target.connectedAddr = adb.sessionLabel
        target.connectedSince = adb.sessionSince
        lastProbe = System.currentTimeMillis()
    }
    requestNotificationPermissionOnce()
    val progCb: (Int, Int, String, Long, Long) -> Unit = { idx, total, name, sent, size ->
        runOnUiThread { showInstallProgress(idx, total, name, sent, size) }
    }
    progressRef = progCb
    adb.onProgress = progCb

    // Siapkan kunci ADB di thread latar (bukan UI thread).
    io.execute { adb.warmUp() }
    showPreviousCrash()
}

/**
 * Izin notifikasi (Android 13+) agar notifikasi "koneksi aktif" terlihat. Hanya diminta sekali;
 * bila ditolak tidak diminta lagi tiap aplikasi dibuka.
 */
internal fun MainActivity.requestNotificationPermissionOnce() {
    val prefs = uiPrefs
    if (Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        !prefs.getBoolean(PREF_NOTIF_ASKED, false)) {
        prefs.edit().putBoolean(PREF_NOTIF_ASKED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
    }
}

/** Bila aplikasi sempat crash sebelumnya, tampilkan jejaknya di log (lalu hapus) agar penyebabnya bisa dibaca / disalin. */
internal fun MainActivity.showPreviousCrash() {
    io.execute {
        val f = File(filesDir, App.CRASH_FILE)
        val txt = try { if (f.exists()) f.readText() else null } catch (e: Throwable) { null }
        if (txt != null) {
            f.delete()
            val trace = txt.lines().filter { it.isNotBlank() }.map { it.replace("\t", "  ") }
            runOnUiThread {
                log(LogLevel.ERR, "Aplikasi berhenti tiba-tiba pada sesi sebelumnya, jejaknya ada di bawah")
                trace.take(14).forEach { log(LogLevel.DETAIL, it) }
                if (trace.size > 14) log(LogLevel.DETAIL, Fmt.omitted(trace.size - 14))
            }
        }
    }
}
