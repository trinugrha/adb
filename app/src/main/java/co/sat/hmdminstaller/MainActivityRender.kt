package co.sat.hmdminstaller

import android.content.res.ColorStateList
import android.view.View
import com.google.android.material.textfield.TextInputLayout

/*
 * Render tampilan (status koneksi, tombol, kartu informasi, daftar APK), dipisah dari MainActivity.
 * Dipakai sebagai fungsi ekstensi MainActivity.
 */

// ---------- UI helper ----------

/** Teks status koneksi berjalan seperti papan LED (lihat LedTickerView). Teks yang sama tidak mengulang animasi. */
internal fun MainActivity.setStatusText(text: String) = txtConn.setMessage(text)

internal fun MainActivity.refreshHeader() {
    val addr = target.connectedAddr
    val lost = lostAddr
    val on = addr != null && adb.isConnected
    when {
        on -> {
            val s = ((System.currentTimeMillis() - target.connectedSince) / 1000).coerceAtLeast(0)
            val dur = Fmt.hms(s)
            // Durasi berubah tiap detik; mengganti teks akan mengulang marquee dari awal. Jadi durasi hanya
            // disertakan bila seluruh teks muat (tidak perlu berjalan); bila tidak, teks tetap statis dan berjalan.
            val full = "Terhubung · $addr · $dur"
            val avail = txtConn.width - txtConn.paddingLeft - txtConn.paddingRight
            setStatusText(if (avail > 0 && txtConn.paint.measureText(full) <= avail) full else "Terhubung · $addr")
            statusDot.backgroundTintList = ColorStateList.valueOf(colorOf(R.color.status_ok))
        }
        lost != null -> {
            setStatusText("Terputus · $lost, sambungkan ulang")
            statusDot.backgroundTintList = ColorStateList.valueOf(colorOf(R.color.status_bad))
        }
        else -> {
            setStatusText("Belum terhubung, ${AdbMessages.HOW_TO_CONNECT}")
            statusDot.backgroundTintList = ColorStateList.valueOf(colorOf(R.color.status_idle))
        }
    }
    refreshOwner()
    refreshApps()
    refreshPerms()
    refreshTcpip()
    applyButtonState(busy)
}

/**
 * Kolom IP/port/kode Pair dan IP/port Connect dikunci selama ada koneksi aktif.
 * Isi kolom tetap bisa diubah lewat kode (mis. hasil Pair QR); yang terkunci hanya ketikan pengguna.
 */
internal fun MainActivity.applyConnectionFieldsState(connected: Boolean) {
    if (!edtPairIpReady) return
    listOf(edtPairIp, edtPairPort, edtPairCode, edtConnIp, edtConnPort).forEach { edt ->
        val on = !connected
        edt.isEnabled = on
        if (!on) edt.clearFocus()
        var p = edt.parent
        while (p != null && p !is TextInputLayout) p = p.parent
        (p as? TextInputLayout)?.let { it.isEnabled = on; it.alpha = if (on) 1f else 0.75f }
    }
}

/**
 * true bila "Cek Status" punya bahan untuk dicek: sedikitnya satu model di Pengaturan Model Perangkat DAN sedikitnya
 * satu aplikasi di Pengaturan Aplikasi dan Versi (dihitung dari semua jenis, karena jenis baru ditentukan dari model
 * HP target saat pengecekan). Salah satu kosong = false.
 */
internal fun hasCheckConfig(): Boolean =
    DeviceCatalog.DEVICE_MODELS.keys.any { ModelStore.models(it).isNotEmpty() } &&
        DeviceCatalog.DEVICE_PACKAGES.keys.any { VersionStore.packages(it).isNotEmpty() }

/**
 * Atur status tombol: semua tombol mati saat ada proses berjalan; tombol yang butuh device
 * (Pasang APK, set-device-owner, Otomatis, Hapus data & Uninstall Kaspersky) juga mati selama belum terhubung.
 */
internal fun MainActivity.applyButtonState(isBusy: Boolean) {
    val connected = isLinked()
    applyConnectionFieldsState(connected)
    buttons.forEach { b ->
        // "Pasang Aplikasi" dan "Otomatis (pasang + owner)" tidak bisa ditekan selama daftar APK masih kosong.
        // Hapus Data & Uninstall Kaspersky hanya aktif bila com.kaspersky.kes terdeteksi di HP target.
        val noKaspersky = (b.id == R.id.btnClearKaspersky || b.id == R.id.btnUninstallKaspersky) &&
            target.kasperskyInstalled != true
        // Aktifkan MDM Mode hanya aktif bila com.hmdm.launcher terdeteksi di HP target.
        val noHmdm = b.id == R.id.btnOwner && target.hmdmInstalled != true
        // "Aktifkan MDM Mode" dan "Pasang Aplikasi + Aktifkan MDM Mode" mati bila HMDM sudah menjadi device owner
        // (status "HMDM aktif ✓").
        val ownerActive = (b.id == R.id.btnOwner || b.id == R.id.btnAll) &&
            connected && target.ownerState is OwnerState.Hmdm
        val needsApk = (b.id == R.id.btnInstallOnly || b.id == R.id.btnAll) && apks.isEmpty()
        // "Pasang Aplikasi + Aktifkan MDM Mode" hanya aktif bila com.hmdm.launcher ada di daftar APK yang akan
        // dipasang.
        val noHmdmApk = b.id == R.id.btnAll && !hasHmdmApk()
        // "Aktifkan Mode Nirkabel" (adb tcpip) hanya untuk sesi yang berjalan lewat kabel USB.
        val notUsb = b.id == R.id.btnTcpip && connected && !adb.isViaUsb
        // Mode TCP/IP sudah aktif di HP target: tombol tidak perlu ditekan lagi.
        val tcpipAlready = b.id == R.id.btnTcpip && connected && target.tcpipActive == true
        val enabled = !isBusy && !notUsb && !tcpipAlready && !needsApk && !noHmdmApk &&
            !noKaspersky && !noHmdm && !ownerActive &&
            (connected || b !in needsConnection) &&
            !(connected && b in blockedWhenConnected)
        b.isEnabled = enabled
        // Beberapa gaya tombol (mis. DangerFilled) tidak punya tampilan "disabled" sendiri.
        b.alpha = if (enabled) 1f else 0.4f
    }
    // Area pilih file APK: hanya bisa ditekan saat sudah terhubung dan tidak sedang ada proses.
    val interactive = !isBusy && connected
    if (dropZoneReady) dropZone.setAvailable(interactive)
    // Tab metode koneksi: yang tidak sedang dipakai terkunci selama ada koneksi aktif
    // (wireless terhubung -> tab USB mati; USB terhubung -> tab Wireless mati).
    setModeTabsEnabled(wirelessEnabled = !(connected && adb.isViaUsb), usbEnabled = !(connected && !adb.isViaUsb))
    // Tombol hapus di daftar aplikasi: hanya bisa ditekan saat terhubung dan tidak sedang ada proses.
    setApkRemoveButtonsEnabled(interactive)
    // Ikon refresh Status MDM Mode & tombol Cek Status Semua Aplikasi: hanya bisa ditekan saat terhubung dan tidak
    // sibuk.
    // "Cek Status" juga mati bila daftar model atau daftar aplikasi masih kosong (tidak ada yang bisa dicek).
    if (btnRefreshInfoReady) btnRefreshInfo.setAvailable(interactive && hasCheckConfig())
    if (btnRefreshOwnerReady) btnRefreshOwner.setAvailable(interactive)
    // Ikon pengaturan (gerigi) & model perangkat (+) di kartu Informasi Status: terkunci selama ada proses berjalan,
    // supaya daftar aplikasi/versi/model tidak berubah di tengah proses.
    findViewById<View>(R.id.btnSettings)?.setAvailable(!isBusy)
    findViewById<View>(R.id.btnModels)?.setAvailable(!isBusy)
}

internal fun MainActivity.setModeTabsEnabled(wirelessEnabled: Boolean, usbEnabled: Boolean) {
    if (!tabModeReady) return
    listOf(0 to wirelessEnabled, 1 to usbEnabled).forEach { (pos, enabled) ->
        val tab = tabMode.getTabAt(pos) ?: return@forEach
        tab.view.setAvailable(enabled)
    }
    // Jaga-jaga: kalau tab yang terkunci sedang terbuka, pindah ke tab yang masih aktif.
    val sel = tabMode.selectedTabPosition
    if (sel == 1 && !usbEnabled) tabMode.getTabAt(0)?.select()
    else if (sel == 0 && !wirelessEnabled) tabMode.getTabAt(1)?.select()
}

internal fun MainActivity.setApkRemoveButtonsEnabled(enabled: Boolean) {
    if (!apkListBoxReady) return
    for (i in 0 until apkListBox.childCount) {
        apkListBox.getChildAt(i).findViewById<View>(R.id.apkRemoveTag)?.setAvailable(enabled, dimAlpha = 0.3f)
    }
}

/** Status gabungan tiga izin com.hmdm.launcher (admin perangkat, overlay, riwayat penggunaan). */
internal fun MainActivity.refreshPerms() {
    if (!txtPermAdminReady) return
    val connected = isLinked()
    // Hanya tampilkan hasil bila dicek untuk jenis perangkat yang sedang terceklis.
    val p = if (connected && target.permType == deviceType) target.hmdmPerms else null
    val grey = colorOf(R.color.status_idle)
    val green = colorOf(R.color.status_ok)
    val red = colorOf(R.color.status_bad)
    val amber = colorOf(R.color.status_warn)
    // Device owner otomatis juga admin perangkat aktif.
    val admin = if (p?.admin == true || (connected && target.ownerState is OwnerState.Hmdm)) true else p?.admin
    val values = listOf(admin, p?.overlay, p?.usage)
    val (text, color) = when {
        !connected -> "Belum terhubung" to grey
        p == null || values.any { it == null } -> "Belum diperiksa" to grey
        values.all { it == true } -> "Aktif ✓" to green
        values.none { it == true } -> "Belum aktif" to red
        else -> "Belum lengkap (${values.count { it == true }}/3)" to amber
    }
    txtPermAdmin.text = text
    permAdminDot.backgroundTintList = ColorStateList.valueOf(color)
}

internal fun MainActivity.refreshApps() {
    if (!txtAppsReady) return
    val connected = isLinked()
    val st = if (connected && target.appsType == deviceType) target.appsInstalled else null
    val pkgs = VersionStore.packages(deviceType)
    val total = pkgs.size
    val grey = colorOf(R.color.status_idle)
    val green = colorOf(R.color.status_ok)
    val amber = colorOf(R.color.status_warn)
    val red = colorOf(R.color.status_bad)
    val expected = VersionStore.expected(deviceType)
    val missing = if (st == null) emptyList() else pkgs.filter { st[it] == null }
    val wrong = if (st == null) emptyList() else pkgs.filter {
        st[it] != null && expected[it] != null && !DeviceCatalog.versionMatches(expected.getValue(it), st[it])
    }
    val okCount = total - missing.size - wrong.size
    // Belum terhubung selalu menang. Bila terhubung, tampil "Daftar aplikasi kosong" (tidak ada yang bisa dicek) bila:
    // - daftar aplikasi SEMUA jenis perangkat kosong (walau model belum dibaca), atau
    // - model HP target terbaca dan dikenali, tetapi daftar aplikasi jenis itu kosong.
    val allEmpty = DeviceCatalog.DEVICE_PACKAGES.keys.all { VersionStore.packages(it).isEmpty() }
    val knownType = if (connected) target.deviceModel?.second else null
    val typeListEmpty = knownType != null && VersionStore.packages(knownType).isEmpty()
    val (text, color) = when {
        !connected -> "Belum terhubung" to grey
        allEmpty || typeListEmpty -> "Daftar aplikasi kosong" to grey
        total == 0 -> "Belum diperiksa" to grey
        st == null -> "Belum diperiksa" to grey
        missing.isEmpty() && wrong.isEmpty() -> "Versi sesuai ✓ ($total/$total)" to green
        // Kuning: semua terpasang tapi ada versi yang berbeda. Merah: ada aplikasi yang belum terpasang.
        missing.isEmpty() -> "Versi belum sesuai ($okCount/$total)" to amber
        else -> "Belum sesuai ($okCount/$total)" to red
    }
    txtApps.text = text
    appsDot.backgroundTintList = ColorStateList.valueOf(color)
    val dm = if (connected) target.deviceModel else null
    // Kartu model perangkat. Belum terhubung selalu menang. Bila terhubung:
    // - daftar model SEMUA jenis kosong: "Daftar model kosong"
    // - ada daftar model tetapi model HP belum dibaca: "Belum diperiksa"
    // - model terbaca dan ada di daftar: "model · jenis"; tidak ada di daftar: "model · tidak dikenal"
    val allModelsEmpty = DeviceCatalog.DEVICE_MODELS.keys.all { ModelStore.models(it).isEmpty() }
    txtModel.text = when {
        !connected -> "Belum terhubung"
        allModelsEmpty -> "Daftar model kosong"
        dm == null -> "Belum diperiksa"
        dm.second != null -> "${dm.first} · ${dm.second}"
        else -> "${dm.first} · tidak dikenal"
    }
    // Rincian aplikasi bermasalah tidak ditampilkan di kartu; hanya ada di log.
    txtAppsDetail.text = ""
    txtAppsDetail.visibility = View.GONE
}

internal fun MainActivity.refreshTcpip() {
    if (!txtTcpipReady) return
    val connected = isLinked()
    val st = if (connected) target.tcpipActive else null
    val (text, color) = when (st) {
        true -> "Aktif ✓" to colorOf(R.color.status_ok)
        false -> "Belum aktif" to colorOf(R.color.status_bad)
        null -> (if (connected) "Belum diperiksa" else "Belum terhubung") to colorOf(R.color.status_idle)
    }
    txtTcpip.text = text
    tcpipDot.backgroundTintList = ColorStateList.valueOf(color)
}

internal fun MainActivity.refreshOwner() {
    val connected = isLinked()
    val st = if (connected) target.ownerState else OwnerState.Unknown
    val (text, color) = when (st) {
        is OwnerState.Hmdm -> "HMDM aktif ✓" to colorOf(R.color.status_ok)
        is OwnerState.Other -> "Dikelola ${st.pkg}" to colorOf(R.color.status_bad)
        OwnerState.None ->
            when (target.hmdmInstalled) {
                false -> "Belum diset (HMDM belum terpasang)" to colorOf(R.color.status_bad)
                true -> "Belum diset (HMDM sudah terpasang)" to colorOf(R.color.status_warn)
                null -> "Belum diperiksa" to colorOf(R.color.status_idle)
            }
        OwnerState.Unknown ->
            (if (connected) "Belum diperiksa" else "Belum terhubung") to colorOf(R.color.status_idle)
    }
    txtOwner.text = text
    ownerDot.backgroundTintList = ColorStateList.valueOf(color)
}

internal fun MainActivity.refreshApkList() {
    if (apks.isEmpty()) {
        txtApkEmpty.text = getString(R.string.apk_drop_title)
        txtDropSub.text = getString(R.string.apk_drop_sub)
        txtApkTitle.text = getString(R.string.apk_title)
    } else {
        txtApkEmpty.text = "${apks.size} APK dipilih"
        txtDropSub.text = "Ketuk untuk memilih ulang (daftar sebelumnya diganti)"
        txtApkTitle.text = "${getString(R.string.apk_title)} (${apks.size})"
    }
    renderApkList()
    refreshHeader()
}
