package co.sat.hmdminstaller

import co.sat.hmdminstaller.AdbController.Companion.KASPERSKY_PACKAGE
import co.sat.hmdminstaller.AdbController.Companion.OWNER_COMPONENT
import co.sat.hmdminstaller.AdbController.Companion.TCPIP_PORT

// Perintah `pm` / `dpm` / `tcpip` satu kali jalan di HP target: MDM Mode (device owner), Kaspersky, dan mode nirkabel.

/** dpm selesai bila satu baris hasil lengkap: Success, atau kegagalan (Error / Exception / Failure). Tanpa ini, kegagalan menunggu timeout. */
private val DPM_DONE: (String) -> Boolean = { out ->
    out.endsWith("\n") && listOf("Success", "Error", "Exception", "Failure").any { out.contains(it) }
}

fun AdbController.setDeviceOwner(): Boolean {
    if (!ensureConnected()) return false
    logCmd("adb shell dpm set-device-owner $OWNER_COMPONENT")
    val out = shellOrError("dpm set-device-owner $OWNER_COMPONENT", doneWhen = DPM_DONE)
    if (isSuccess(out)) {
        logOk("MDM Mode aktif: HMDM")
        return true
    }
    val reason = AdbMessages.ownerFailureReason(out)
    logErr("${RunTitle.SET_OWNER} gagal: $reason")
    logRaw(out, reason)
    return false
}

/** Setara `adb shell pm clear com.kaspersky.kes`: hapus semua data aplikasi Kaspersky di device. */
fun AdbController.clearKasperskyData(): Boolean {
    if (!ensureConnected()) return false
    logCmd("adb shell pm clear $KASPERSKY_PACKAGE")
    val out = shellOrError("pm clear $KASPERSKY_PACKAGE", doneWhen = pmDone)
    if (!isSuccess(out)) {
        logErr("${RunTitle.CLEAR_KASPERSKY} gagal: ${AdbMessages.KASPERSKY_COMMAND_FAILED}")
        logRaw(out, AdbMessages.KASPERSKY_COMMAND_FAILED)
        return false
    }
    logOk("Data aplikasi Kaspersky dihapus")
    return true
}

/** Setara `adb shell pm uninstall -k --user 0 com.kaspersky.kes`: uninstall untuk user 0, data tetap disimpan (-k). */
fun AdbController.uninstallKaspersky(): Boolean {
    if (!ensureConnected()) return false
    logCmd("adb shell pm uninstall -k --user 0 $KASPERSKY_PACKAGE")
    val out = shellOrError("pm uninstall -k --user 0 $KASPERSKY_PACKAGE", doneWhen = pmDone)
    if (!isSuccess(out)) {
        val reason = when {
            out.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER") ->
                "Kaspersky masih aktif sebagai aplikasi admin perangkat. " +
                    "Ulangi Langkah 2: Pengaturan › Keamanan › Aplikasi admin perangkat › Kaspersky › Nonaktifkan"
            out.contains("DELETE_FAILED_USER_RESTRICTED") || out.contains("OWNER_BLOCKED") ->
                "diblokir kebijakan device owner atau profile owner. Lepas HP target dari konsol KES/MDM, lalu coba lagi"
            out.contains("Unknown package", ignoreCase = true) || out.contains("not installed", ignoreCase = true) ->
                "Kaspersky sudah tidak terpasang"
            else -> AdbMessages.KASPERSKY_COMMAND_FAILED
        }
        logErr("${RunTitle.UNINSTALL_KASPERSKY} gagal: $reason")
        logRaw(out, reason)
        return false
    }
    logOk("Aplikasi Kaspersky di-uninstall")
    return true
}

/**
 * Setara `adb tcpip 5555`: adbd di HP target restart dalam mode TCP di port 5555.
 * Setelah itu sesi USB ini pasti putus, jadi sesi dibersihkan di sini tanpa log error.
 */
fun AdbController.enableTcpip(): Boolean {
    if (!ensureConnected()) return false
    if (!viaUsb) {
        logErr(
            "${RunTitle.ENABLE_TCPIP} gagal: perintah ini hanya untuk koneksi USB. " +
                "Gunakan tab \"USB\", lalu ketuk \"Pilih Perangkat\""
        )
        return false
    }
    if (queryTcpipActive() == true) {
        logOk("Mode nirkabel (TCP/IP) sudah aktif di HP target, tidak perlu diubah lagi")
        return true
    }
    logCmd("adb tcpip $TCPIP_PORT")
    val out = shellOrError("$TCPIP_PORT", timeoutMs = 6_000, quiet = true, service = "tcpip") {
        listOf("restarting", "error", "failed").any { k -> it.contains(k, ignoreCase = true) }
    }
    val failed = out.contains("error", ignoreCase = true) || out.contains("failed", ignoreCase = true)
    val restarted = out.contains("restarting", ignoreCase = true)
    if (failed || (!restarted && out.isNotBlank())) {
        val reason = "HP target menolak perintah tcpip. Cek koneksi USB, lalu coba lagi"
        logErr("${RunTitle.ENABLE_TCPIP} gagal: $reason")
        logRaw(out, reason)
        return false
    }
    // Tanpa output = adbd sudah restart dan menutup stream lebih dulu, tetap dianggap berhasil.
    logOk("Mode nirkabel (TCP/IP) aktif di port $TCPIP_PORT, koneksi USB terputus otomatis")
    logStep("Berikutnya: ${AdbMessages.nextAfterTcpip(TCPIP_PORT)}")
    closeSessionAfterTcpip()
    return true
}
