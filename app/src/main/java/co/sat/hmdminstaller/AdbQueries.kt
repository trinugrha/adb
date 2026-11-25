package co.sat.hmdminstaller

import co.sat.hmdminstaller.AdbController.Companion.HMDM_PACKAGE
import co.sat.hmdminstaller.AdbController.Companion.KASPERSKY_PACKAGE
import co.sat.hmdminstaller.AdbController.Companion.QUERY_LONG_MS
import co.sat.hmdminstaller.AdbController.Companion.QUERY_SHORT_MS

/*
 * Pembacaan status HP target (device owner, model, aplikasi, izin) yang dipisah dari AdbController.
 * Fungsi ekstensi AdbController: dipanggil seperti biasa lewat `adb.queryDeviceOwner()` dan sejenisnya.
 * Semua query berjalan di thread latar dan gagal-baca dikembalikan sebagai null / Unknown, bukan exception.
 */

/**
 * Cek siapa device owner di HP target (tanpa menulis log). Dipanggil dari thread latar.
 * Memakai `dpm list-owners`; bila tidak didukung, fallback ke `dumpsys device_policy`.
 */
fun AdbController.queryDeviceOwner(): OwnerState {
    if (!libConnected) return OwnerState.Unknown
    return try {
        // Quiet: ini cek latar belakang, jangan memenuhi log dengan peringatan timeout.
        val a = shell("dpm list-owners", QUERY_SHORT_MS, quiet = true)
        AdbMessages.parseOwner(a)?.let { return it }
        // Bila dpm menggantung (device_policy sibuk), dumpsys hampir pasti ikut menggantung: jangan tunggu lagi.
        if (lastShellTimedOut && a.isBlank()) return OwnerState.Unknown
        val b = shell("dumpsys device_policy", 6_000, quiet = true)
        AdbMessages.parseOwner(b)?.let { return it }
        if (a.isBlank() && b.isBlank()) OwnerState.Unknown else OwnerState.None
    } catch (e: Throwable) {
        OwnerState.Unknown
    }
}

/**
 * Cek diam-diam (tanpa log) apakah [KASPERSKY_PACKAGE] terpasang di HP target (`pm list packages`).
 * true = terpasang, false = tidak ada, null = tidak terbaca (mis. koneksi bermasalah).
 */
fun AdbController.queryKasperskyInstalled(): Boolean? = queryPackageInstalled(KASPERSKY_PACKAGE)

/** Cek diam-diam apakah [pkg] terpasang di HP target: true / false / null (tidak terbaca). */
fun AdbController.queryHmdmInstalled(): Boolean? = queryPackageInstalled(HMDM_PACKAGE)

/**
 * Cek apakah adbd HP target sudah dalam mode TCP/IP (setara hasil `adb tcpip`):
 * properti service.adb.tcp.port / persist.adb.tcp.port berisi port > 0.
 * null = belum bisa dipastikan.
 */
fun AdbController.queryTcpipActive(): Boolean? {
    if (!libConnected) return null
    return try {
        val out = shell("getprop service.adb.tcp.port; getprop persist.adb.tcp.port", QUERY_SHORT_MS, quiet = true)
        if (out.isBlank() && lastShellTimedOut) return null
        out.lineSequence().any { (it.trim().toIntOrNull() ?: 0) > 0 }
    } catch (e: Throwable) {
        null
    }
}

/**
 * Baca model HP target (ro.product.model; cadangan ro.product.device / ro.product.name).
 * Hasil: Pair(model yang ditampilkan, jenis POS/PDA/TABLET atau null bila tidak ada di daftar). null = tidak terbaca.
 */
fun AdbController.queryDeviceModel(): Pair<String, String?>? {
    if (!libConnected) return null
    return try {
        val out = shell(
            "getprop ro.product.model; getprop ro.product.device; getprop ro.product.name",
            QUERY_SHORT_MS,
            quiet = true
        )
        val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        val hit = lines.firstNotNullOfOrNull { l -> DeviceCatalog.detectType(l)?.let { l to it } }
        hit ?: (lines.first() to null)
    } catch (e: Throwable) {
        null
    }
}

/**
 * Cek diam-diam (tanpa log) versi paket milik jenis perangkat [type] saja (bukan semua jenis). Hasil: paket ->
 * versionName; null = tidak terpasang; "" = terpasang tapi versi tidak terbaca. Versi hanya dibaca untuk paket di
 * [DeviceCatalog.EXPECTED_VERSIONS] jenis itu. Seluruh hasil null = daftar tidak terbaca utuh (kosong / timeout),
 * supaya tidak salah menyimpulkan "belum terpasang".
 */
fun AdbController.queryRequiredPackages(type: String): Map<String, String?>? {
    if (type !in DeviceCatalog.DEVICE_PACKAGES) return null
    val wanted = VersionStore.packages(type)
    if (!libConnected) return null
    return try {
        val out = shell("pm list packages --user 0", QUERY_LONG_MS, quiet = true)
        if (lastShellTimedOut || !out.contains("package:")) return null
        val installed = out.lineSequence().map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .toSet()
        // Di mode WiFi daftar paket bisa terpotong. Paket yang tidak muncul dikonfirmasi satu per satu;
        // bila ada yang tidak terbaca, hasil dianggap tidak utuh (null) supaya status lama tidak tertimpa.
        val present = LinkedHashMap<String, Boolean>()
        for (pkg in wanted) {
            if (pkg in installed) { present[pkg] = true; continue }
            present[pkg] = queryPackageInstalled(pkg) ?: return null
        }
        val withVersion = VersionStore.expected(type)
        val versions = queryVersions(wanted.filter { withVersion.containsKey(it) && present[it] == true })
        val result = LinkedHashMap<String, String?>()
        for ((pkg, ok) in present) result[pkg] = if (ok) (versions[pkg] ?: "") else null
        result
    } catch (e: Throwable) {
        null
    }
}

/** Baca versionName tiap paket di [pkgs] dengan satu perintah shell. Paket yang tidak terbaca tidak masuk hasil. */
private fun AdbController.queryVersions(pkgs: List<String>): Map<String, String> {
    val safe = pkgs.filter(::isValidPackageName)
    if (safe.isEmpty()) return emptyMap()
    val cmd = safe.joinToString("; ") { p ->
        "echo \"PKG|$p|\$(dumpsys package $p | grep -m1 versionName=)\""
    }
    val out = shell(cmd, 30_000, quiet = true)
    val res = LinkedHashMap<String, String>()
    for (line in out.lineSequence()) {
        val t = line.trim()
        if (!t.startsWith("PKG|")) continue
        val parts = t.split("|", limit = 3)
        if (parts.size < 3) continue
        val v = parts[2].substringAfter("versionName=", "").trim().substringBefore(' ')
        if (v.isNotEmpty()) res[parts[1]] = v
    }
    return res
}

/** Cek manual (ketuk Cek Status): versi aplikasi di HP target ditulis ke log. [type] = jenis perangkat yang dipilih. */
fun AdbController.checkRequiredPackages(type: String): Map<String, String?>? {
    if (!ensureConnected()) return null
    logCmd("adb shell dumpsys package <paket> | grep versionName")
    val all = queryRequiredPackages(type)
    if (all == null) {
        logErr("Baca versi aplikasi gagal: HP target tidak menjawab. Cek koneksi, lalu coba lagi")
        return null
    }
    val pkgs = VersionStore.packages(type)
    val expected = VersionStore.expected(type)
    if (pkgs.isEmpty()) {
        logWarn(AdbMessages.emptyAppList(type))
        return all
    }
    val missing = pkgs.filter { all[it] == null }
    val wrong = pkgs.filter {
        all[it] != null && expected[it] != null && !DeviceCatalog.versionMatches(expected.getValue(it), all[it])
    }
    if (missing.isEmpty() && wrong.isEmpty()) {
        logOk("Semua aplikasi terpasang dan versinya sesuai (${pkgs.size}/${pkgs.size})")
    } else {
        // Nama paket mentah tidak masuk baris WARN (bisa tampil di popup); rinciannya per aplikasi di baris DETAIL.
        if (missing.isNotEmpty()) logWarn("Belum terpasang ${missing.size} dari ${pkgs.size} aplikasi")
        if (wrong.isNotEmpty()) logWarn("Versi belum sesuai di ${wrong.size} aplikasi")
        val details = missing.map { "$it: belum terpasang" } + wrong.map { pkg ->
            val v = all[pkg]?.takeIf { it.isNotBlank() } ?: "tidak terbaca"
            "$pkg: terpasang $v, seharusnya ${expected[pkg]}"
        }
        details.take(10).forEach { logDetail(it) }
        if (details.size > 10) logDetail(Fmt.omitted(details.size - 10))
    }
    return all
}

/**
 * Cek diam-diam tiga izin [HMDM_PACKAGE]: admin perangkat (dumpsys device_policy),
 * overlay (appops SYSTEM_ALERT_WINDOW) dan akses riwayat penggunaan (appops GET_USAGE_STATS).
 * null = belum terhubung.
 */
fun AdbController.queryHmdmPermissions(): HmdmPermissions? {
    if (!libConnected) return null
    return try {
        HmdmPermissions(
            admin = queryHmdmAdminActive(),
            overlay = queryAppOpAllowed("SYSTEM_ALERT_WINDOW", "android.permission.SYSTEM_ALERT_WINDOW"),
            usage = queryAppOpAllowed("GET_USAGE_STATS", "android.permission.PACKAGE_USAGE_STATS")
        )
    } catch (e: Throwable) {
        null
    }
}

/** Admin perangkat aktif bila HMDM tercatat di daftar device admin (device owner otomatis termasuk). */
private fun AdbController.queryHmdmAdminActive(): Boolean? {
    val out = shell("dumpsys device_policy | grep -F 'ComponentInfo{$HMDM_PACKAGE/'", QUERY_LONG_MS, quiet = true)
    return when {
        out.contains("$HMDM_PACKAGE/") -> true
        out.isBlank() && lastShellTimedOut -> null
        else -> false
    }
}

/**
 * Cek mode appops [op] milik [HMDM_PACKAGE]: "allow" = aktif; "ignore/deny/errored" = belum aktif.
 * Bila mode masih default, status diambil dari izin [permName] di `dumpsys package` (granted=true).
 */
private fun AdbController.queryAppOpAllowed(op: String, permName: String): Boolean? {
    val out = shell("appops get $HMDM_PACKAGE $op", QUERY_SHORT_MS, quiet = true)
    if (out.isBlank() && lastShellTimedOut) return null
    val line = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$op:") }
    if (line != null) {
        val mode = line.removePrefix("$op:").trim()
        if (mode.startsWith("allow")) return true
        if (mode.startsWith("ignore") || mode.startsWith("deny") || mode.startsWith("errored")) return false
    }
    val dump = shell("dumpsys package $HMDM_PACKAGE | grep -F $permName", QUERY_LONG_MS, quiet = true)
    if (dump.isBlank() && lastShellTimedOut) return null
    return dump.contains("$permName: granted=true")
}

/** Cek manual (ketuk status): hasilnya ditulis ke log. */
fun AdbController.checkHmdmPermissions(): HmdmPermissions? {
    if (!ensureConnected()) return null
    logCmd("adb shell dumpsys device_policy")
    logCmd("adb shell appops get $HMDM_PACKAGE SYSTEM_ALERT_WINDOW")
    logCmd("adb shell appops get $HMDM_PACKAGE GET_USAGE_STATS")
    val r = queryHmdmPermissions() ?: run {
        logErr("Baca izin gagal: HP target tidak menjawab. Cek koneksi, lalu coba lagi")
        return null
    }
    fun line(name: String, v: Boolean?) = when (v) {
        true -> logOk("$name: aktif")
        false -> logWarn("$name: belum aktif")
        null -> logWarn("$name: tidak terbaca")
    }
    line("Admin perangkat", r.admin)
    line("Tampil di atas aplikasi lain", r.overlay)
    line("Akses riwayat penggunaan", r.usage)
    return r
}

private fun AdbController.queryPackageInstalled(pkg: String): Boolean? {
    if (!libConnected) return null
    return try {
        val out = shell("pm list packages --user 0 $pkg", QUERY_SHORT_MS, quiet = true)
        when {
            out.lineSequence().any { it.trim() == "package:$pkg" } -> true
            // Kosong + timeout = HP target belum sempat menjawab, bukan "tidak terpasang".
            out.isBlank() && lastShellTimedOut -> null
            out.contains("package:") || out.isBlank() -> false
            else -> null
        }
    } catch (e: Throwable) {
        null
    }
}

/** Cek manual (tombol Status MDM Mode): hasilnya ditulis ke log. Unknown bila tidak terbaca. */
fun AdbController.checkDeviceOwner(): OwnerState {
    if (!ensureConnected()) return OwnerState.Unknown
    logCmd("adb shell dpm list-owners")
    val st = queryDeviceOwner()
    when (st) {
        is OwnerState.Hmdm -> logOk("MDM Mode aktif: HMDM")
        is OwnerState.None -> logWarn("Belum ada MDM Mode di HP target")
        is OwnerState.Other -> {
            // Nama paket mentah tidak masuk baris WARN (bisa tampil di popup); cukup sebagai baris DETAIL.
            logWarn(
                "HP target sudah memakai MDM Mode dari aplikasi lain. " +
                    "Lepas atau reset HP target sebelum mengaktifkan MDM Mode HMDM"
            )
            logDetail("Device owner saat ini: ${st.pkg}")
        }
        is OwnerState.Unknown -> logErr("Baca MDM Mode gagal: HP target tidak menjawab. Cek koneksi, lalu coba lagi")
    }
    return st
}
