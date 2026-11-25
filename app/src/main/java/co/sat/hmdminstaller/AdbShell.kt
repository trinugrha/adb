package co.sat.hmdminstaller

import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/*
 * Menjalankan perintah shell di HP target dan membaca outputnya, dipisah dari AdbController.
 * Fungsi ekstensi AdbController: dipanggil seperti biasa lewat `shell(...)`, `shellOrError(...)`, dst.
 */

/** Hasil `pm` dianggap lengkap setelah satu baris Success / Failure / Error selesai dibaca. */
private val PM_DONE: (String) -> Boolean = { out ->
    out.endsWith("\n") && (out.contains("Success") || out.contains("Failure") || out.contains("Error"))
}

internal val AdbController.pmDone: (String) -> Boolean get() = PM_DONE

internal fun AdbController.isSuccess(out: String) = out.contains("Success", ignoreCase = true)

/**
 * Exception saat menjalankan perintah shell, dalam bentuk "Error: <Kelas>: <pesan>" supaya lewat jalur output mentah
 * (baris DETAIL).
 */
internal fun AdbController.shellError(e: Throwable) = "Error: ${Fmt.cause(e)}"

/** Seperti [shell], tetapi exception dikembalikan sebagai teks [shellError] supaya ikut jalur output mentah. */
internal fun AdbController.shellOrError(
    cmd: String,
    timeoutMs: Long = 15_000,
    quiet: Boolean = false,
    service: String = "shell",
    doneWhen: ((String) -> Boolean)? = null
): String = try {
    shell(cmd, timeoutMs, quiet, service, doneWhen)
} catch (e: Throwable) {
    shellError(e)
}

/**
 * Output mentah device sebagai baris rincian (maks. 6 baris) untuk diagnosis.
 * Dibuang: baris "Performing ...", stack trace Java ("at ...", "... N more"), dan baris yang
 * hanya mengulang alasan yang sudah tampil di baris [shown] (mis. "Failure: INSTALL_FAILED_...").
 */
internal fun AdbController.logRaw(out: String, shown: String = "", max: Int = 6) {
    val lines = out.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !AdbMessages.isRawNoise(it) && !AdbMessages.restates(it, shown) }
        .distinct()
    lines.take(max).forEach { logDetail(it) }
    if (lines.size > max) logDetail(Fmt.omitted(lines.size - max))
}

/**
 * Jalankan `shell:<cmd>` dengan batas waktu. Output dikumpulkan bertahap; bila [doneWhen]
 * terpenuhi, pembacaan dihentikan tanpa menunggu EOF (device kadang tidak segera menutup
 * stream walau perintah sudah selesai dan "Success" sudah tercetak).
 * Peringatan timeout hanya dicatat bila memang belum ada output sama sekali.
 */
internal fun AdbController.shell(
    cmd: String,
    timeoutMs: Long = 15_000,
    quiet: Boolean = false,
    service: String = "shell",
    doneWhen: ((String) -> Boolean)? = null
): String {
    val bos = ByteArrayOutputStream()
    val done = CountDownLatch(1)
    val stream = AtomicReference<AdbStream?>(null)
    val error = AtomicReference<Throwable?>(null)
    // Diset setelah timeout: bila openStream() baru selesai sesudahnya, stream langsung ditutup oleh thread-nya sendiri.
    val abandoned = AtomicBoolean(false)
    Thread {
        try {
            val s = manager.openStream("$service:$cmd")
            stream.set(s)
            if (abandoned.get()) { quietly { s.close() }; return@Thread }
            val input = s.openInputStream()
            // Output ditulis ke bos langsung, jadi yang sudah terbaca tetap ada bila stream ditutup atau waktu habis.
            readAll(input, doneWhen, bos)
        } catch (e: Throwable) {
            error.set(e)
        } finally {
            done.countDown()
        }
    }.apply { isDaemon = true; start() }

    val finished = done.await(timeoutMs, TimeUnit.MILLISECONDS)
    lastShellTimedOut = !finished
    if (!finished) abandoned.set(true)
    quietly { stream.get()?.close() }
    if (!finished) {
        done.await(1, TimeUnit.SECONDS)
        if (!quiet && bos.size() == 0) {
            logWarn("HP target belum menjawab setelah ${timeoutMs / 1000} dtk, dilanjutkan")
        }
    }
    error.get()?.let { throw it }
    return bos.toString("UTF-8")
}

/**
 * Baca sampai EOF atau sampai [doneWhen] terpenuhi. Bila stream ditutup device (IOException), kembalikan yang sudah
 * terbaca. Isi ditulis ke [bos], supaya pemanggil yang membacanya dari thread lain tetap melihat hasil sebagian.
 */
internal fun AdbController.readAll(
    input: InputStream,
    doneWhen: ((String) -> Boolean)? = null,
    bos: ByteArrayOutputStream = ByteArrayOutputStream()
): String {
    val buf = ByteArray(4096)
    try {
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            bos.write(buf, 0, n)
            if (doneWhen != null && doneWhen(bos.toString("UTF-8"))) break
        }
    } catch (e: IOException) {
        // "Stream closed" saat EOF itu normal: device menutup stream setelah perintah selesai.
    }
    return bos.toString("UTF-8")
}
