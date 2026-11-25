package co.sat.hmdminstaller

/**
 * Penyaring output mentah dan teks alasan gagal untuk ADB. Murni (tanpa state), dipakai [AdbController]
 * supaya logika teks terpisah dari logika koneksi.
 */
object AdbMessages {
    private val stackFrameRe = Regex("^at [\\w.$<>/]+\\(.*\\)$")
    private val moreRe = Regex("^\\.\\.\\. \\d+ more$")

    fun isRawNoise(l: String) =
        l.startsWith("Performing ") || stackFrameRe.matches(l) || moreRe.matches(l)

    fun restates(line: String, shown: String): Boolean {
        if (shown.isEmpty()) return false
        val body = line.removePrefix("Error:").trim()
        if (body.isNotEmpty() && shown.contains(body, ignoreCase = true)) return true
        val code = Regex("^Failure \\[([A-Z_]+)").find(line)?.groupValues?.get(1)
        return code != null && shown.contains(code)
    }

    private val installReasons = mapOf(
        "INSTALL_FAILED_ALREADY_EXISTS" to "sudah terpasang",
        "INSTALL_FAILED_VERSION_DOWNGRADE" to "versi lebih rendah dari yang terpasang",
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "tanda tangan berbeda dengan versi terpasang",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "penyimpanan HP target penuh",
        "INSTALL_FAILED_INVALID_APK" to "file APK rusak atau tidak valid",
        "INSTALL_FAILED_NO_MATCHING_ABIS" to "arsitektur CPU tidak cocok",
        "INSTALL_FAILED_OLDER_SDK" to "versi Android HP target terlalu lama",
        "INSTALL_FAILED_USER_RESTRICTED" to "pemasangan dibatasi kebijakan HP target",
        "INSTALL_FAILED_TEST_ONLY" to "APK bertanda test-only"
    )

    /** Saran perbaikan per kode install; hanya dipakai di baris ✗ akhir, ditulis sebagai kalimat sendiri. */
    private val installAdvice = mapOf(
        "INSTALL_FAILED_VERSION_DOWNGRADE" to "Pasang APK dengan versi yang sama atau lebih baru, atau uninstall versi yang terpasang dulu",
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "Uninstall versi yang terpasang dulu, lalu pasang ulang",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "Kosongkan penyimpanan HP target, lalu coba lagi",
        "INSTALL_FAILED_INVALID_APK" to "Pilih ulang file APK yang utuh",
        "INSTALL_FAILED_NO_MATCHING_ABIS" to "Gunakan APK yang cocok dengan arsitektur CPU HP target",
        "INSTALL_FAILED_OLDER_SDK" to "Gunakan APK yang mendukung versi Android HP target",
        "INSTALL_FAILED_USER_RESTRICTED" to "Lepas pembatasan pemasangan di HP target, lalu coba lagi",
        "INSTALL_FAILED_TEST_ONLY" to "Gunakan APK rilis, bukan APK test-only"
    )

    /** Alasan singkat dari keluaran `cmd package install` yang gagal. [advice] = sertakan saran perbaikan (untuk baris ✗ akhir). */
    fun installFailureReason(
        out: String, advice: Boolean, stalled: Boolean, viaUsb: Boolean, lost: Boolean = false
    ): String {
        val code = Regex("INSTALL_[A-Z_]+").find(out)?.value
        val friendly = code?.let { installReasons[it] }
        return when {
            lost ->
                if (!advice) "koneksi ke HP target terputus"
                else "koneksi ke HP target terputus, kemungkinan Wi-Fi HP target mati atau di luar jangkauan. " +
                    "Nyalakan Wi-Fi dan Wireless debugging di HP target, ketuk \"Connect\" lagi, lalu pasang ulang APK"
            stalled ->
                if (!advice) "pengiriman macet"
                else if (viaUsb) "pengiriman macet. Cabut-colok kabel USB, lalu ketuk \"Pilih Perangkat\" lagi"
                else "pengiriman macet. Pastikan HP target menyala dan satu jaringan Wi-Fi, lalu ketuk \"Connect\" lagi"
            friendly != null ->
                "$friendly ($code)" + (if (advice) code?.let { installAdvice[it] }?.let { ". $it" }.orEmpty() else "")
            code != null -> code
            // Kegagalan kirim tanpa kode install (stream putus, exception): teks mentahnya (sering berbahasa Inggris,
            // mis. "stream closed") hanya tampil di baris DETAIL, bukan di baris ✗.
            out.startsWith("Error:") ->
                if (advice) "pengiriman APK gagal. Cek koneksi ke HP target, lalu coba lagi" else "pengiriman APK gagal"
            out.isBlank() -> "tidak ada balasan dari HP target"
            // Baris pertama yang bukan noise ("Performing Streamed Install", stack trace) dan tidak kosong.
            else -> out.lines().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !isRawNoise(it) }
                ?.removePrefix("Error:")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { Fmt.clip(it, 120) }
                ?: "tidak diketahui"
        }
    }

    /** Alasan singkat dari keluaran `dpm set-device-owner` yang gagal. */
    fun ownerFailureReason(out: String): String {
        val l = out.lowercase()
        return when {
            "already has a device owner" in l || "device owner is already set" in l ->
                "HP target sudah memiliki device owner. Reset HP target ke pengaturan pabrik bila ingin menggantinya"
            "accounts" in l -> "masih ada akun (Google dan lainnya) di HP target. Hapus akun atau reset HP target"
            "provisioned" in l || "already been set up" in l -> "HP target sudah melewati setup awal. Reset HP target ke pengaturan pabrik"
            "unknown admin" in l || "does not exist" in l || "can't find admin" in l || ("not found" in l && "admin" in l) ->
                "HMDM belum terpasang di HP target. Jalankan \"Pasang Aplikasi\" terlebih dahulu"
            else -> "perintah ditolak HP target. Pastikan HP target baru di-reset dan belum ada akun (Google dan lainnya)"
        }
    }

    /** Ambil device owner dari output: Hmdm, Other (aplikasi lain), atau null bila tidak ada penanda device owner. */
    fun parseOwner(out: String): OwnerState? {
        val adminRe = Regex("admin=(?:ComponentInfo\\{)?([\\w.]+)/")
        val markerRe = Regex("device\\s*owner", RegexOption.IGNORE_CASE)
        // 1) `dpm list-owners`: satu baris berisi admin=pkg/.Cls dan penanda DeviceOwner.
        var pkg: String? = null
        for (line in out.lineSequence()) {
            if (markerRe.containsMatchIn(line)) {
                pkg = adminRe.find(line)?.groupValues?.get(1)
                if (pkg != null) break
            }
        }
        // 2) `dumpsys device_policy`: header "Device Owner:" lalu detail di baris-baris berikutnya.
        if (pkg == null) {
            val m = Regex("device\\s+owner\\s*:", RegexOption.IGNORE_CASE).find(out) ?: return null
            val tail = out.substring(m.range.first, minOf(out.length, m.range.first + 400))
            pkg = Regex("ComponentInfo\\{([^/}\\s]+)/").find(tail)?.groupValues?.get(1)
                ?: adminRe.find(tail)?.groupValues?.get(1)
                ?: Regex("package=([\\w.]+)").find(tail)?.groupValues?.get(1)
                ?: return null
        }
        val hmdmPkg = AdbController.OWNER_COMPONENT.substringBefore('/')
        return if (pkg == hmdmPkg) OwnerState.Hmdm(pkg) else OwnerState.Other(pkg)
    }

    /** Kegagalan tanpa pesan dari pm (stream putus), bukan penolakan install seperti INSTALL_FAILED_*. */
    fun isTransient(out: String): Boolean =
        out.isBlank() || (out.startsWith("Error:") && !out.contains("INSTALL_", ignoreCase = true))

    /** Peringatan daftar aplikasi suatu jenis perangkat kosong. Satu sumber teks untuk semua pemanggil. */
    fun emptyAppList(type: String): String =
        "Daftar aplikasi untuk $type kosong, status instalasi dan izin tidak dicek. " +
            "Tambahkan aplikasi lewat ikon gerigi di kartu \"Informasi Status\""

    /** Cara menyambung ke HP target. Satu sumber untuk log, popup, dan teks status header. */
    const val HOW_TO_CONNECT = "\"Pair\" lalu \"Connect\", atau \"Pilih Perangkat\" di tab \"USB\""

    /** Langkah setelah berhasil terhubung (Pair dengan QR, Connect, Connect USB). Satu sumber untuk popup; bentuknya sama dengan [NEXT_AFTER_PAIR]. */
    const val NEXT_AFTER_CONNECT = "pilih file APK lalu ketuk \"Pasang Aplikasi\", atau ketuk \"Aktifkan MDM Mode\""

    /** Langkah setelah Pair manual berhasil: IP connect terisi otomatis, tinggal port. Satu sumber untuk log dan popup. */
    const val NEXT_AFTER_PAIR = "isi port connect (layar utama Wireless debugging HP target), lalu ketuk \"Connect\""

    /** Langkah bila port connect tidak ditemukan otomatis (Pair dengan QR). Satu sumber untuk log dan popup. */
    const val CONNECT_MANUALLY = "isi IP dan port connect (layar utama Wireless debugging HP target), lalu ketuk \"Connect\""

    /** Alasan Pair dengan QR habis waktu. Satu sumber untuk baris ✗ di log dan isi popup. */
    fun qrPairTimeout(seconds: Long) =
        "HP target tidak memindai QR dalam $seconds dtk, atau layanan pairing tidak ditemukan"

    /** Petunjuk setelah mode nirkabel (TCP/IP) aktif. Tanpa Pair: port tetap 5555, cukup Connect. Satu sumber untuk log dan popup. */
    fun nextAfterTcpip(port: Int) =
        "cabut kabel, buka tab \"Wireless\", isi IP HP target dan port $port, lalu ketuk \"Connect\""

    /** Alasan + saran bila perintah Kaspersky ditolak tanpa kode yang dikenali; satu teks untuk hapus data dan uninstall. */
    const val KASPERSKY_COMMAND_FAILED = "perintah tidak berhasil. Pastikan aplikasi Kaspersky terpasang di HP target"

    /** Alasan tidak ada HP target di port USB. Satu sumber untuk baris ✗ di log dan butir "Penyebab" di popup. */
    const val NO_USB_TARGET = "tidak ada HP target di port USB"

    /** Penutup semua popup gagal. */
    const val SEE_LOG = "Rincian lengkap ada di log."

    /** Peringatan model HP target belum ada di daftar; menunjuk letak ikon pengaturannya, sama seperti [emptyAppList]. */
    fun unknownModel(model: String): String =
        "Model perangkat: $model tidak ada di daftar POS / PDA / TABLET, status instalasi dan izin tidak dicek. " +
            "Tambahkan model lewat ikon + di kartu \"Informasi Status\", lalu ketuk \"Cek Status\" lagi"

    /** Pecah "host:port" menjadi pasangan host dan port; null bila bukan bentuk itu. */
    fun splitHostPort(addr: String): Pair<String, Int>? {
        val i = addr.lastIndexOf(':')
        if (i <= 0) return null
        val port = addr.substring(i + 1).toIntOrNull() ?: return null
        return addr.substring(0, i) to port
    }

    /** Alasan singkat (tanpa saran) dari exception saat connect. Rantai teknis lengkap dicetak terpisah sebagai DETAIL. */
    fun connectErrorReason(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(4).toList()
        val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            chain.any { it is java.net.SocketTimeoutException } || "timed out" in text ->
                "waktu habis, HP target tidak menjawab"
            chain.any { it is java.net.ConnectException } || "refused" in text -> "koneksi ditolak oleh HP target"
            chain.any { it is java.net.UnknownHostException || it is java.net.NoRouteToHostException } || "unreachable" in text ->
                "HP target tidak terjangkau"
            chain.any { it is javax.net.ssl.SSLException } || "handshake" in text || "certificate" in text ->
                "jabat tangan TLS gagal"
            else -> "kesalahan koneksi tak terduga"
        }
    }
}
