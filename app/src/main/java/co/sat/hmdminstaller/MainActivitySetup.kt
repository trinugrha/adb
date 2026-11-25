package co.sat.hmdminstaller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import co.sat.hmdminstaller.MainActivity.Companion.PREF_DEVICE_TYPE
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText

/*
 * Pemasangan listener tombol, tab, dan kartu status, dipisah dari MainActivity.
 * Dipakai sebagai fungsi ekstensi MainActivity.
 */

/** Ikon refresh di kartu status: status MDM Mode (cepat) dan Informasi Status (lengkap). Tidak ada refresh lewat ketuk kartu. */
internal fun MainActivity.setupStatusRefresh() {
    btnRefreshOwner.setOnClickListener {
        if (!canStartManualRefresh()) return@setOnClickListener
        // Hanya status MDM Mode yang dibaca; instalasi, launcher, dan izin tidak ikut diperbarui.
        runSkippingInfo(RunTitle.CHECK_OWNER) {
            val st = adb.checkDeviceOwner()
            if (st != OwnerState.Unknown) target.ownerState = st
            st != OwnerState.Unknown
        }
    }
    btnRefreshInfo.setOnClickListener {
        if (!hasCheckConfig()) return@setOnClickListener
        if (!canStartManualRefresh()) return@setOnClickListener
        // Hasil cek manual sudah dipakai kartu; jangan dibaca ulang diam-diam (bisa menimpa dengan hasil terpotong).
        runSkippingInfo(RunTitle.CHECK_STATUS) { checkDeviceInfo() }
    }
}

/** Refresh manual hanya jalan bila tidak sibuk dan ADB terhubung; bila belum terhubung, beri tahu lewat log. */
internal fun MainActivity.canStartManualRefresh(): Boolean {
    if (busy) return false
    if (!adb.isConnected) { log(LogLevel.WARN, AdbController.MSG_NOT_CONNECTED); return false }
    return true
}

/**
 * Isi kartu Informasi Status: baca model HP target, pilih jenis perangkat otomatis bila model ada di daftar,
 * lalu cek instalasi aplikasi dan izin launcher. Mengembalikan false bila semua pembacaan gagal.
 */
internal fun MainActivity.checkDeviceInfo(): Boolean {
    // Baca model HP target; bila ada di daftar POS / PDA / TABLET, jenis perangkat dipilih otomatis.
    val dm = adb.queryDeviceModel()
    if (dm != null) {
        target.deviceModel = dm
        val detected = dm.second
        if (detected != null) {
            runOnUiThread { log(LogLevel.OK, "Model perangkat: ${dm.first}, jenis $detected") }
            if (detected != deviceType) {
                deviceType = detected
                uiPrefs.edit().putString(PREF_DEVICE_TYPE, deviceType).apply()
            }
        } else {
            runOnUiThread { log(LogLevel.WARN, AdbMessages.unknownModel(dm.first)) }
        }
    } else {
        runOnUiThread { log(LogLevel.WARN, "Model perangkat tidak terbaca") }
    }
    // Model tidak dikenal / tidak terbaca: jenis perangkat tidak bisa ditentukan, jadi perintah cek Status Instalasi
    // dan Status Izin tidak dijalankan sama sekali; kartu tetap "Belum diperiksa" (hasil lama dibuang).
    if (!target.modelRecognized()) {
        clearAppChecks()
        return true
    }
    if (target.modelAppListEmpty()) {
        // Model dikenali tetapi daftar aplikasi jenisnya kosong: stop periksa (instalasi dan izin tidak dijalankan).
        runOnUiThread { log(LogLevel.WARN, AdbMessages.emptyAppList(deviceType)) }
        clearAppChecks()
        return true
    }
    val type = deviceType
    val apps = adb.checkRequiredPackages(type)
    if (apps != null) { target.appsInstalled = apps; target.appsType = type }
    val perms = adb.checkHmdmPermissions()
    if (perms != null) { target.hmdmPerms = perms; target.permType = type }
    return apps != null || perms != null
}

/** Buang hasil cek instalasi dan izin lama (kartu kembali "Belum diperiksa"). */
internal fun MainActivity.clearAppChecks() {
    target.appsInstalled = null
    target.appsType = null
    target.hmdmPerms = null
    target.permType = null
}

internal fun MainActivity.setupMoreToggle() {
    val moreContainer: View = findViewById(R.id.moreContainer)
    val txtMoreToggle: TextView = findViewById(R.id.txtMoreToggle)
    val icMoreToggle: View = findViewById(R.id.icMoreToggle)
    findViewById<View>(R.id.btnMoreToggle).setOnClickListener {
        val show = moreContainer.visibility != View.VISIBLE
        moreContainer.visibility = if (show) View.VISIBLE else View.GONE
        txtMoreToggle.text = getString(if (show) R.string.more_hide else R.string.more_show)
        icMoreToggle.rotation = if (show) 180f else 0f
    }
}

/** Tab Wireless / USB: tukar panel yang tampil dan judul kartu koneksi. */
internal fun MainActivity.setupModeTabs() {
    tabMode = findViewById(R.id.tabMode)
    val tabWireless: View = findViewById(R.id.tabWireless)
    val tabUsb: View = findViewById(R.id.tabUsb)
    val txtConnTitle: TextView = findViewById(R.id.txtConnTitle)
    val cardTcpip: View = findViewById(R.id.cardTcpip)
    tabMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            // Pindah tab (swipe atau ketuk): tutup keyboard dulu, sebelum kolom input di tab lama disembunyikan.
            tabBar.hideKeyboard()
            val usb = tab.position == 1
            tabWireless.visibility = if (usb) View.GONE else View.VISIBLE
            tabUsb.visibility = if (usb) View.VISIBLE else View.GONE
            txtConnTitle.text = getString(if (usb) R.string.conn_title_usb else R.string.conn_title_wireless)
            cardTcpip.visibility = if (usb) View.VISIBLE else View.GONE
        }
        override fun onTabUnselected(tab: TabLayout.Tab) {}
        override fun onTabReselected(tab: TabLayout.Tab) {}
    })
}

/** Pair, Pair QR, Connect, Connect USB, dan Disconnect. */
internal fun MainActivity.setupConnectionButtons() {
    val btnPair = button(R.id.btnPair)
    val btnConnect = button(R.id.btnConnect)
    btnPair.setOnClickListener {
        if (isLinked()) return@setOnClickListener
        val addr = addr(edtPairIp.text.toString(), edtPairPort.text.toString())
        val code = clean(edtPairCode.text.toString())
        run(RunTitle.PAIR) { doPair(addr, code) }
    }
    // Tombol "Done" di keyboard pada kolom kode pairing = tekan tombol Pair; pada kolom port Connect = tekan Connect.
    clickOnImeDone(edtPairCode, btnPair)
    clickOnImeDone(edtConnPort, btnConnect)
    button(R.id.btnPairQr).setOnClickListener {
        if (isLinked()) return@setOnClickListener
        showQrPairing()
    }
    btnConnect.setOnClickListener {
        if (isLinked()) return@setOnClickListener
        val addr = addr(edtConnIp.text.toString(), edtConnPort.text.toString())
        run(RunTitle.CONNECT) { doConnect(addr) }
    }
    button(R.id.btnConnectUsb).setOnClickListener {
        if (busy || isLinked()) return@setOnClickListener
        connectUsb()
    }
    button(R.id.btnDisconnect).setOnClickListener {
        run(RunTitle.DISCONNECT) { doDisconnect() }
    }
}

/** Tombol "Done" di keyboard pada [field] menutup keyboard lalu menekan [target]. */
internal fun MainActivity.clickOnImeDone(field: TextInputEditText, target: View) {
    field.setOnEditorActionListener { v, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
            target.performClick()
            true
        } else false
    }
}

/** Cari HP target di port USB: tidak ada → popup bantuan; ada → daftar pilihan lalu sambung. */
internal fun MainActivity.connectUsb() {
    val targets = adb.listUsbTargets()
    if (targets.isEmpty()) {
        val t0 = System.currentTimeMillis()
        logHead(RunTitle.CONNECT_USB, t0)
        log(
            LogLevel.ERR,
            "${RunTitle.CONNECT_USB} gagal: ${AdbMessages.NO_USB_TARGET}. " +
                "Colok lewat kabel OTG, aktifkan USB debugging di Opsi Developer HP target, dan pilih mode USB \"Transfer file\""
        )
        logEnd(RunTitle.CONNECT_USB, false, t0)
        showConnProblem(
            "${RunTitle.CONNECT_USB} Gagal",
            RunMessages.failureWithCause(AdbMessages.NO_USB_TARGET, RunMessages.NO_USB_TARGET_CHECKLIST)
        )
        return
    }
    var usbDlg: AlertDialog? = null
    val usbList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    for ((i, t) in targets.withIndex()) {
        usbList.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = t.label + "\n" + t.device.deviceName
                setAllCaps(false)
                setTextSizeRes(R.dimen.text_body)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = px(R.dimen.list_item_height)
                setOnClickListener {
                    usbDlg?.dismiss()
                    showUsbDevice(t.label)
                    run(RunTitle.CONNECT_USB) { doConnectUsb(t) }
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) topMargin = px(R.dimen.space_sm) }
        )
    }
    usbDlg = showPopup(
        "Pilih Perangkat USB", "Ketuk HP target yang ingin disambungkan.",
        icon = R.drawable.ic_usb, content = usbList, positive = null, negative = getString(R.string.action_cancel)
    )
}

/** Pilih APK, pasang, jadikan MDM Mode, Kaspersky, dan Mode Nirkabel. Selesai tiap proses, Informasi Status tidak ikut dicek. */
internal fun MainActivity.setupActionButtons() {
    dropZone.setOnClickListener {
        if (busy || !isLinked()) return@setOnClickListener
        pickApks.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
    }
    button(R.id.btnInstallOnly).setOnClickListener {
        val list = apks.toList()
        runSkippingInfo(RunTitle.INSTALL) { adb.install(list) }
    }
    button(R.id.btnOwner).setOnClickListener {
        showConfirm(
            "Aktifkan MDM Mode?",
            "HMDM akan dijadikan MDM Mode di HP target.\n\n${RunMessages.OWNER_WARNING}",
            "Aktifkan"
        ) {
            runSkippingInfo(RunTitle.SET_OWNER) { doSetOwner() }
        }
    }
    button(R.id.btnClearKaspersky).setOnClickListener {
        showConfirm(
            "Hapus Data Aplikasi Kaspersky?",
            "Semua data dan pengaturan aplikasi Kaspersky di HP target akan dihapus.\n\n" +
                "Langkah berikutnya: matikan Aplikasi Admin Kaspersky di HP target secara manual (Langkah 2).",
            "Hapus"
        ) {
            runSkippingInfo(RunTitle.CLEAR_KASPERSKY) { adb.clearKasperskyData() }
        }
    }
    button(R.id.btnUninstallKaspersky).setOnClickListener {
        showConfirm(
            "Uninstall Aplikasi Kaspersky?",
            "Aplikasi Kaspersky akan di-uninstall dari HP target.\n\n" +
                "Perhatian: pastikan Langkah 2 (mematikan Aplikasi Admin Kaspersky) sudah dilakukan. Bila belum, uninstall akan ditolak.",
            "Uninstall"
        ) {
            runSkippingInfo(RunTitle.UNINSTALL_KASPERSKY) { adb.uninstallKaspersky() }
        }
    }
    button(R.id.btnTcpip).setOnClickListener {
        runSkippingInfo(RunTitle.ENABLE_TCPIP) { doEnableTcpip() }
    }
    button(R.id.btnAll).setOnClickListener {
        // Otomatis hanya: install APK + set device owner. Pair & Connect dilakukan manual sebelumnya.
        val list = apks.toList()
        showConfirm(
            "Pasang Aplikasi + Aktifkan MDM Mode?",
            "APK akan dipasang, lalu HMDM dijadikan MDM Mode di HP target.\n\n${RunMessages.OWNER_WARNING}",
            "Lanjutkan"
        ) {
            runSkippingInfo(RunTitle.INSTALL_AND_OWNER) { adb.installAndOwner(list) }
        }
    }
}

internal fun MainActivity.setupLogButtons() {
    button(R.id.btnCopyLog).setOnClickListener {
        if (txtLog.length() > 0) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", txtLog.text))
            Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Log masih kosong", Toast.LENGTH_SHORT).show()
        }
    }
    button(R.id.btnClearLog).setOnClickListener {
        txtLog.text = ""
    }
}
