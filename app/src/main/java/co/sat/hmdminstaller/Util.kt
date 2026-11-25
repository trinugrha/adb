package co.sat.hmdminstaller

import android.content.Context
import android.view.View
import java.io.File
import org.json.JSONObject

/**
 * Jalankan [block] dan abaikan semua kegagalannya. Untuk pembersihan "best effort"
 * (menutup soket, melepas receiver, dst) yang tidak boleh sampai mematikan aplikasi.
 */
inline fun quietly(block: () -> Unit) {
    try { block() } catch (ignored: Throwable) { }
}

/** Jalankan [block] di thread daemon (tidak menahan proses), kegagalannya diabaikan seperti [quietly]. */
inline fun daemon(crossinline block: () -> Unit) {
    Thread { quietly { block() } }.apply { isDaemon = true; start() }
}

/** Nama sub-folder cache tempat APK pilihan pengguna disalin. */
private const val PICKED_DIR = "picked"

/** Folder cache tempat APK pilihan pengguna disalin. */
fun Context.pickedApksDir(): File = File(cacheDir, PICKED_DIR)

/** Hapus semua APK pilihan yang sudah disalin ke cache. */
fun Context.clearPickedApks() = quietly { pickedApksDir().deleteRecursively() }

/** Aktif/nonaktifkan view sekaligus meredupkannya ([dimAlpha] saat nonaktif), supaya tampilannya konsisten. */
fun View.setAvailable(enabled: Boolean, dimAlpha: Float = 0.4f) {
    isEnabled = enabled
    isClickable = enabled
    alpha = if (enabled) 1f else dimAlpha
}

/** Nama paket Android yang sah: segmen dipisah titik, tiap segmen diawali huruf, minimal dua segmen. */
private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

/** Satu-satunya validator nama paket. Nama paket disambung ke perintah shell, jadi selalu validasi lewat ini. */
fun isValidPackageName(s: String): Boolean = PACKAGE_NAME_REGEX.matches(s)

/** Buang spasi di tepi, entri kosong, dan duplikat (urutan kemunculan pertama dipertahankan). */
fun Iterable<String>.cleaned(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Baca [text] sebagai objek JSON; melempar [IllegalArgumentException] bila bukan JSON yang valid. */
fun parseJsonObject(text: String): JSONObject = try {
    JSONObject(text)
} catch (e: Exception) {
    throw IllegalArgumentException("bukan file JSON yang valid")
}
