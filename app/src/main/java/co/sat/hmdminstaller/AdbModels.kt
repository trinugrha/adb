package co.sat.hmdminstaller

import java.io.File

/** Satu APK di daftar: file hasil pilihan user (disalin ke cache). */
data class ApkItem(val name: String, val file: File, val builtIn: Boolean = false) {
    val sizeText: String
        get() = Fmt.size(file.length())
}

/**
 * Status tiga izin com.hmdm.launcher di HP target. Tiap nilai: true = aktif, false = belum aktif,
 * null = tidak terbaca (mis. timeout).
 */
data class HmdmPermissions(
    /** Permission to install as device administrator (admin perangkat aktif). */
    val admin: Boolean?,
    /** Permission to overlay on top of other windows (SYSTEM_ALERT_WINDOW). */
    val overlay: Boolean?,
    /** Permission to access the use of history (akses penggunaan / GET_USAGE_STATS). */
    val usage: Boolean?
)

/** Status device owner di HP target. */
sealed class OwnerState {
    /** Belum terhubung / belum dicek / gagal dicek. */
    object Unknown : OwnerState()
    /** Tidak ada device owner. */
    object None : OwnerState()
    /** Device owner ada, tetapi aplikasi lain (bukan HMDM), mis. Kaspersky KES atau MDM lain. */
    data class Other(val pkg: String) : OwnerState()
    /** HMDM (com.hmdm.launcher) adalah device owner. */
    data class Hmdm(val pkg: String) : OwnerState()
}
