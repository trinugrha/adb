package co.sat.hmdminstaller

import co.sat.hmdminstaller.MainActivity.Companion.PREF_DEVICE_TYPE
import com.google.android.material.button.MaterialButton

// Mengikat view dan kelompok tombol dari layout activity_main, dipisah dari MainActivity.

/** Ambil semua view dari layout dan baca jenis perangkat terakhir yang dipilih. */
internal fun MainActivity.bindViews() {
    edtPairIp = findViewById(R.id.edtPairIp)
    edtPairPort = findViewById(R.id.edtPairPort)
    edtPairCode = findViewById(R.id.edtPairCode)
    edtConnIp = findViewById(R.id.edtConnIp)
    edtConnPort = findViewById(R.id.edtConnPort)
    txtLog = findViewById(R.id.txtLog)
    logScroll = findViewById(R.id.logScroll)
    logPanel = LogPanel(this, txtLog, logScroll)
    txtConn = findViewById(R.id.txtConn)
    txtStatus = findViewById(R.id.txtStatus)
    txtApkTitle = findViewById(R.id.txtApkTitle)
    txtApkEmpty = findViewById(R.id.txtApkEmpty)
    txtDropSub = findViewById(R.id.txtDropSub)
    dropZone = findViewById(R.id.dropZone)
    btnRefreshOwner = findViewById(R.id.btnRefreshOwner)
    apkListBox = findViewById(R.id.apkListBox)
    statusDot = findViewById(R.id.statusDot)
    ownerDot = findViewById(R.id.ownerDot)
    txtOwner = findViewById(R.id.txtOwner)
    tcpipDot = findViewById(R.id.tcpipDot)
    txtTcpip = findViewById(R.id.txtTcpip)
    appsPill = findViewById(R.id.appsPill)
    btnRefreshInfo = findViewById(R.id.btnRefreshInfo)
    // Jenis perangkat dipilih otomatis dari model HP target (tidak ada tombol pilih lagi).
    deviceType = uiPrefs.getString(PREF_DEVICE_TYPE, "POS")?.takeIf { it in DeviceCatalog.DEVICE_PACKAGES } ?: "POS"
    txtModel = findViewById(R.id.txtModel)
    appsDot = findViewById(R.id.appsDot)
    txtApps = findViewById(R.id.txtApps)
    txtAppsDetail = findViewById(R.id.txtAppsDetail)
    permAdminDot = findViewById(R.id.permAdminDot)
    txtPermAdmin = findViewById(R.id.txtPermAdmin)
    progress = findViewById(R.id.progress)
    loading = LoadingOverlay(this)
}

/** Kelompokkan tombol: semua tombol, yang butuh koneksi, dan yang dimatikan saat sudah terhubung. */
internal fun MainActivity.bindButtonGroups() {
    val btnPair = button(R.id.btnPair)
    val btnPairQr = button(R.id.btnPairQr)
    val btnConnect = button(R.id.btnConnect)
    val btnConnectUsb = button(R.id.btnConnectUsb)
    val btnDisconnect = button(R.id.btnDisconnect)
    val btnInstallOnly = button(R.id.btnInstallOnly)
    val btnOwner = button(R.id.btnOwner)
    val btnAll = button(R.id.btnAll)
    val btnClearKaspersky = button(R.id.btnClearKaspersky)
    val btnUninstallKaspersky = button(R.id.btnUninstallKaspersky)
    val btnTcpip = button(R.id.btnTcpip)
    buttons = listOf(btnPair, btnPairQr, btnConnect, btnConnectUsb, btnDisconnect,
        btnInstallOnly, btnOwner, btnAll, btnClearKaspersky, btnUninstallKaspersky, btnTcpip)
    needsConnection = setOf(
        btnDisconnect, btnInstallOnly, btnOwner, btnAll, btnClearKaspersky, btnUninstallKaspersky, btnTcpip
    )
    blockedWhenConnected = setOf(btnPair, btnPairQr, btnConnect, btnConnectUsb)
}

internal fun MainActivity.button(id: Int): MaterialButton = findViewById(id)
