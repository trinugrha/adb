package co.sat.hmdminstaller

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Format ukuran, durasi, dan jam untuk log dan progress.
 *
 * Selalu memakai Locale.US supaya titik desimal sama di semua tempat, juga di HP berbahasa
 * Indonesia (tanpa ini ukuran tampil "12,3 MB" sementara durasi "4.2 dtk").
 */
object Fmt {
    /** 12.3 MB / 840 KB / 512 B */
    fun size(bytes: Long): String = when {
        bytes >= 1_048_576 -> mb(bytes)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** Selalu dalam MB, satu desimal: 12.3 MB */
    fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

    /** 850 ms / 4.2 dtk */
    fun duration(ms: Long): String =
        if (ms < 1000) "$ms ms" else String.format(Locale.US, "%.1f dtk", ms / 1000.0)

    /** 05:09 atau 1:02:03 (jam hanya bila sudah sejam). Untuk timer sesi dan lama koneksi. */
    fun hms(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    /** 3.2 MB/dtk, dari [bytes] yang terkirim dalam [ms] milidetik. */
    fun speed(bytes: Long, ms: Long): String =
        String.format(Locale.US, "%.1f MB/dtk", bytes / 1_048_576.0 / (ms.coerceAtLeast(1) / 1000.0))

    /** Panjang maksimum satu baris DETAIL di log; selebihnya dipotong dan diakhiri "…". */
    const val DETAIL_MAX = 240

    /** Potong satu baris teks panjang dan akhiri dengan "…" supaya jelas bahwa teksnya tidak utuh. */
    fun clip(s: String, max: Int = DETAIL_MAX): String =
        if (s.length > max) s.take(max).trimEnd() + "…" else s

    /** Penutup daftar baris yang dipotong: "… 3 baris lain tidak ditampilkan". Satu sumber untuk semua log DETAIL. */
    fun omitted(n: Int): String = "… $n baris lain tidak ditampilkan"

    /** "IOException: pesan"; tanpa ": " menggantung bila exception tidak punya pesan. Untuk baris DETAIL. */
    fun cause(e: Throwable): String {
        val msg = e.message?.takeIf { it.isNotBlank() }
        return e.javaClass.simpleName + (msg?.let { ": $it" } ?: "")
    }

    /** 14:02:11 */
    fun clock(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(epochMs))

    /** 2026-11-17 14:02:11. Untuk catatan yang dibaca di lain hari (mis. jejak crash). */
    fun stamp(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
}
