package co.sat.hmdminstaller

import co.sat.hmdminstaller.AdbController.Companion.USB_CONNECT_ATTEMPTS

// Connect lewat kabel USB (setara `adb -d`), dipisah dari AdbController.

/** Daftar HP target di port USB (setara `adb devices`). Ringan, aman dipanggil dari UI thread. */
fun AdbController.listUsbTargets(): List<UsbAdbBridge.Target> =
    try { usbBridge.findTargets() } catch (e: Throwable) { emptyList() }

/** Setara `adb connect` lewat kabel USB ke perangkat yang dipilih. Panggil dari thread latar. Mengembalikan label, atau null bila gagal. */
fun AdbController.connectUsb(t: UsbAdbBridge.Target): String? {
    val label = "USB · ${t.label}"
    logCmd("adb -d usb (USB debugging)")
    if (!listUsbTargets().any { it.device.deviceName == t.device.deviceName }) {
        logErr("${RunTitle.CONNECT_USB} gagal: $label sudah tidak terpasang. Colok ulang kabel, lalu ketuk \"Pilih Perangkat\" lagi")
        return null
    }
    logStep("HP target: $label")
    if (!usbBridge.hasPermission(t.device)) logWarn("Izinkan akses USB pada dialog yang muncul di layar HP ini")
    if (!usbBridge.requestPermission(t.device)) {
        logErr("${RunTitle.CONNECT_USB} gagal: izin akses USB ditolak atau tidak dijawab. Ketuk \"Pilih Perangkat\" lagi, lalu pilih \"Izinkan\"")
        return null
    }
    val port = try { usbBridge.start(t.device) } catch (e: Throwable) {
        logErr(
            "${RunTitle.CONNECT_USB} gagal: jalur USB ke HP target tidak bisa dibuka. " +
                "Colok ulang kabel, matikan adb server di PC bila berjalan, lalu coba lagi"
        )
        logCause(e)
        return null
    }
    logWarn("Bila muncul dialog \"Izinkan USB debugging?\" di HP target: centang \"Selalu izinkan\", lalu ketuk \"OK\"")
    // Percobaan pertama bisa habis waktu selagi dialog di HP target belum disetujui; coba lagi setelah itu.
    var ok = false
    for (attempt in 1..USB_CONNECT_ATTEMPTS) {
        if (connect("127.0.0.1:$port", verbose = false, label = label)) { ok = true; break }
        if (attempt < USB_CONNECT_ATTEMPTS) {
            logWarn("Percobaan $attempt/$USB_CONNECT_ATTEMPTS gagal ($connectReasonOrDefault), mencoba lagi…")
        }
    }
    if (!ok) {
        val pumpErr = usbBridge.lastPumpError
        usbBridge.stop()
        viaUsb = false
        logErr("${RunTitle.CONNECT_USB} gagal: $connectReasonOrDefault. Pastikan USB debugging aktif dan dialog otorisasi di HP target disetujui")
        // Penyebab teknis selalu di bawah baris ✗.
        if (lastConnectDetail.isNotEmpty()) logDetail(lastConnectDetail)
        if (pumpErr.isNotEmpty()) logDetail("Jembatan USB: $pumpErr")
        return null
    }
    viaUsb = true
    keepAlive(true)
    return label
}
