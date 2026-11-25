package co.sat.hmdminstaller

import android.net.Uri

// Memilih, menyalin, dan memulihkan file APK di cache, dipisah dari MainActivity.

/**
 * Hasil pemilih file APK. Di UI thread hanya membaca nama (cepat) dan menyaring duplikat / non-APK;
 * penyalinan file dikerjakan di thread latar.
 */
internal fun MainActivity.onApksPicked(uris: List<Uri>?) {
    if (uris.isNullOrEmpty()) return
    // Di UI thread: hanya baca nama (cepat) dan saring duplikat / non-APK.
    // Setiap pemilihan baru mereset daftar sebelumnya (APK lama diganti).
    val seen = mutableSetOf<String>()
    val todo = mutableListOf<Pair<Uri, String>>()
    var skipped = 0
    uris.forEach { uri ->
        val name = displayName(uri)
        when {
            !name.endsWith(".apk", ignoreCase = true) -> skipped++
            !seen.add(name.lowercase()) -> skipped++
            else -> todo.add(uri to name)
        }
    }
    if (skipped > 0) log(LogLevel.WARN, "$skipped file dilewati (bukan .apk atau duplikat)")
    if (todo.isEmpty()) return
    // Penyalinan file (bisa puluhan-ratusan MB) di thread latar. Tandai sibuk selama menyalin: tombol Pasang dan pilih
    // file mati, jadi daftar tidak dipakai saat APK lama sudah dihapus.
    val old = apks.toList()
    busy = true
    setBusy(true, "Menyalin APK…")
    io.execute {
        val items = mutableListOf<ApkItem>()
        var failed = 0
        try {
            // Hapus file APK lama dari cache dulu (sebelum menyalin, agar nama yang sama tidak ikut terhapus).
            old.filter { !it.builtIn }.forEach { it.file.delete() }
            todo.forEach { (uri, name) ->
                val item = copyToCache(uri, name)
                if (item == null) failed++ else items.add(item)
            }
            // Baca nama paket tiap APK di thread latar, supaya UI thread tidak perlu mem-parsing file besar.
            items.forEach { apkPackage(it) }
        } finally {
            runOnUiThread {
                apks.clear()
                apks.addAll(items)
                if (failed > 1) {
                    log(LogLevel.ERR, "Salin APK gagal: $failed dari ${todo.size} file. Pilih ulang file yang gagal")
                }
                busy = false
                setBusy(false, if (items.isEmpty()) "Belum ada APK" else "${items.size} APK siap dipasang")
                refreshApkList()
            }
        }
    }
}

/** Apakah daftar APK yang dipilih berisi com.hmdm.launcher (hanya membaca cache, aman di UI thread). */
internal fun MainActivity.hasHmdmApk(): Boolean = apks.any { apkStore.cachedPackage(it) == AdbController.HMDM_PACKAGE }

internal fun MainActivity.apkPackage(item: ApkItem): String = apkStore.packageName(item)

/** Muat ulang APK yang sudah disalin ke cache (mis. setelah proses aplikasi dimatikan sistem). */
internal fun MainActivity.restorePickedApks() {
    apks.addAll(apkStore.restore())
    // Parse nama paket di thread latar (bukan UI thread), lalu segarkan status tombol.
    val snapshot = apks.toList()
    io.execute {
        snapshot.forEach { apkPackage(it) }
        runOnUiThread { if (!isFinishing) refreshHeader() }
    }
}

internal fun MainActivity.displayName(uri: Uri): String = apkStore.displayName(uri)

/** Salin ke cache. Dipanggil dari thread latar (bukan UI thread). */
internal fun MainActivity.copyToCache(uri: Uri, name: String): ApkItem? = apkStore.copyToCache(uri, name) { e ->
    runOnUiThread {
        log(LogLevel.ERR, "Salin $name gagal: file tidak bisa dibaca atau disimpan. Pilih ulang file APK")
        log(LogLevel.DETAIL, Fmt.cause(e))
    }
}
