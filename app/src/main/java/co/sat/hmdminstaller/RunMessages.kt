package co.sat.hmdminstaller

/**
 * Judul proses yang dijalankan lewat tombol. Dipakai sebagai judul HEAD di log, judul popup hasil,
 * dan kunci untuk memilih teks di [RunMessages].
 */
object RunTitle {
    const val PAIR = "Pair"
    const val PAIR_QR = "Pair dengan QR"
    const val CONNECT = "Connect"
    const val CONNECT_USB = "Connect USB"
    const val DISCONNECT = "Disconnect"
    const val INSTALL = "Pasang Aplikasi"
    const val SET_OWNER = "Aktifkan MDM Mode"
    const val INSTALL_AND_OWNER = "Pasang Aplikasi + Aktifkan MDM Mode"
    const val CLEAR_KASPERSKY = "Hapus Data Aplikasi Kaspersky"
    const val UNINSTALL_KASPERSKY = "Uninstall Aplikasi Kaspersky"
    const val ENABLE_TCPIP = "Aktifkan Mode Nirkabel"
    const val CHECK_OWNER = "Cek Status MDM Mode"
    const val CHECK_STATUS = "Cek Status"

    /** Proses yang memasang APK ("Pasang Aplikasi" saja atau bersama "Aktifkan MDM Mode"). */
    fun isInstall(title: String) = title == INSTALL || title == INSTALL_AND_OWNER
}

/** Teks popup hasil proses. Murni (tanpa state), supaya [MainActivity] hanya mengurus tampilan. */
object RunMessages {

    /**
     * Peringatan bahwa MDM Mode tidak bisa dibatalkan. Satu sumber untuk popup konfirmasi "Aktifkan MDM Mode" dan
     * "Pasang Aplikasi + Aktifkan MDM Mode", supaya kalimatnya tidak berbeda.
     */
    const val OWNER_WARNING =
        "Perhatian: MDM Mode tidak bisa dibatalkan tanpa reset ke pengaturan pabrik. " +
            "Pastikan semua akun (Google dan lainnya) di HP target sudah dihapus."

    /**
     * Isi popup gagal untuk proses tanpa baris ✗ (Pair dengan QR habis waktu, tidak ada HP target di port USB):
     * butir "Penyebab", daftar "Periksa" ([checklist]), lalu [AdbMessages.SEE_LOG]. Satu bentuk dengan popup gagal biasa.
     */
    fun failureWithCause(cause: String, checklist: String): String =
        "Penyebab:\n• ${cause.replaceFirstChar { it.uppercase() }}\n\n$checklist\n\n${AdbMessages.SEE_LOG}"

    /** Daftar "Periksa" berbutir; satu bentuk untuk semua popup gagal. */
    private fun checklist(vararg items: String): String =
        "Periksa:\n" + items.joinToString("\n") { "• $it" }

    /** Daftar "Periksa" untuk Pair dengan QR; satu teks dipakai popup waktu habis dan popup proses gagal. */
    val QR_PAIR_CHECKLIST = checklist(
        "Layar \"Pair device with QR code\" di HP target masih terbuka dan kamera diarahkan ke QR",
        "HP target dan HP ini satu jaringan Wi-Fi (client isolation mati)"
    )

    /** Daftar "Periksa" saat tidak ada HP target di port USB (popup Connect USB sebelum proses dimulai). */
    val NO_USB_TARGET_CHECKLIST = checklist(
        "Kabel OTG terpasang kuat ke HP target",
        "USB debugging aktif di Opsi Developer HP target",
        "Mode USB di HP target: \"Transfer file\""
    )

    /**
     * Daftar "Periksa" pada popup gagal untuk proses [title]; kosong bila tidak perlu. [installFailed] hanya berlaku
     * untuk "Pasang Aplikasi + Aktifkan MDM Mode": false (APK terpasang semua) berarti yang gagal langkah MDM Mode,
     * jadi daftarnya memakai daftar "Aktifkan MDM Mode".
     */
    fun failureHint(title: String, installFailed: Boolean = true): String = when (title) {
        RunTitle.PAIR_QR -> QR_PAIR_CHECKLIST
        RunTitle.PAIR -> checklist(
            "IP, port pairing, dan kode 6 digit sesuai layar \"Pair device with pairing code\" di HP target",
            "Kode belum kedaluwarsa (muncul ulang bila layar ditutup)",
            "HP target dan HP ini satu jaringan Wi-Fi"
        )
        RunTitle.CONNECT_USB -> checklist(
            "Kabel USB terpasang kuat dan tidak terlepas",
            "Izin akses USB di layar HP ini dipilih \"Izinkan\"",
            "Dialog \"Izinkan USB debugging?\" di HP target disetujui (centang \"Selalu izinkan\")",
            "Mode USB di HP target: \"Transfer file\""
        )
        RunTitle.CONNECT -> checklist(
            "IP dan port sesuai layar Wireless debugging di HP target",
            "HP target dan HP ini satu jaringan Wi-Fi",
            "Pairing sudah dilakukan bila diminta"
        )
        RunTitle.INSTALL_AND_OWNER ->
            if (installFailed) failureHint(RunTitle.INSTALL) else failureHint(RunTitle.SET_OWNER)
        RunTitle.INSTALL -> checklist(
            "Layar HP target menyala dan koneksi masih tersambung",
            "Penyimpanan HP target cukup",
            "Versi APK tidak lebih rendah dari yang sudah terpasang",
            "Dialog di HP target (bila muncul) disetujui"
        )
        RunTitle.SET_OWNER -> checklist(
            "Semua akun (Google dan lainnya) di HP target sudah dihapus",
            "HP target baru atau baru direset ke pengaturan pabrik",
            "Belum ada MDM Mode dari aplikasi lain di HP target"
        )
        RunTitle.CLEAR_KASPERSKY -> checklist(
            "Koneksi ke HP target masih tersambung",
            "Aplikasi Kaspersky masih terpasang di HP target"
        )
        RunTitle.UNINSTALL_KASPERSKY -> checklist(
            "Aplikasi Admin Kaspersky sudah dimatikan di HP target (Langkah 2)",
            "Aplikasi Kaspersky masih terpasang di HP target"
        )
        RunTitle.ENABLE_TCPIP -> checklist(
            "Kabel USB masih terpasang kuat",
            "Layar HP target menyala dan USB debugging aktif"
        )
        RunTitle.DISCONNECT -> ""
        else -> checklist(
            "Koneksi ke HP target masih tersambung"
        )
    }

    /**
     * Isi popup berhasil untuk proses [title]; null bila prosesnya tidak punya popup. [connNow] alamat tersambung,
     * [inst] ringkasan instalasi terakhir.
     */
    fun success(title: String, connNow: String?, inst: AdbController.InstallSummary?): String? {
        val nextAfterConnect = "Langkah berikutnya: ${AdbMessages.NEXT_AFTER_CONNECT}."
        val installed =
            if (inst != null && inst.total > 0) "${inst.total} APK berhasil dipasang" else "Semua APK berhasil dipasang"
        return when (title) {
            RunTitle.PAIR ->
                "Pairing dengan HP target berhasil.\n\nLangkah berikutnya: ${AdbMessages.NEXT_AFTER_PAIR}."
            RunTitle.PAIR_QR ->
                if (connNow != null) "Pairing berhasil dan sudah terhubung ke $connNow.\n\n$nextAfterConnect"
                else "Pairing berhasil, tetapi port connect tidak ditemukan otomatis.\n\nLangkah berikutnya: ${AdbMessages.CONNECT_MANUALLY}."
            RunTitle.CONNECT -> "Berhasil terhubung ke ${connNow ?: "HP target"}.\n\n$nextAfterConnect"
            RunTitle.CONNECT_USB -> "Berhasil terhubung ke HP target lewat kabel USB.\n\n$nextAfterConnect"
            RunTitle.DISCONNECT -> "Koneksi ke HP target sudah diputus.\n\nLangkah berikutnya: sambungkan ulang lewat ${AdbMessages.HOW_TO_CONNECT}."
            RunTitle.INSTALL -> "$installed ke HP target."
            RunTitle.SET_OWNER -> "HMDM berhasil dijadikan MDM Mode di HP target."
            RunTitle.INSTALL_AND_OWNER -> "$installed dan HMDM berhasil dijadikan MDM Mode di HP target."
            RunTitle.CLEAR_KASPERSKY -> "Data dan pengaturan aplikasi Kaspersky berhasil dihapus dari HP target."
            RunTitle.UNINSTALL_KASPERSKY -> "Aplikasi Kaspersky berhasil di-uninstall dari HP target."
            // Sesi USB masih tersambung (connNow ada) = mode nirkabel sudah aktif sebelumnya dan tidak diubah; sama dengan
            // baris ✓ di log ("sudah aktif ... tidak perlu diubah lagi"). Setelah diaktifkan, sesi USB putus (connNow null).
            RunTitle.ENABLE_TCPIP -> {
                val head =
                    if (connNow != null) "Mode nirkabel (TCP/IP) sudah aktif di HP target, tidak perlu diubah lagi."
                    else "Mode nirkabel (TCP/IP) berhasil diaktifkan di HP target."
                "$head\n\nLangkah berikutnya: ${AdbMessages.nextAfterTcpip(AdbController.TCPIP_PORT)}."
            }
            else -> null
        }
    }
}
