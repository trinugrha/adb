package co.sat.hmdminstaller

/**
 * Status HP target yang sudah dibaca (koneksi, MDM Mode, aplikasi, izin, model). Satu tempat supaya tampilan dan proses
 * latar memakai sumber yang sama. Nilai null = belum diketahui. Ditulis dari thread kerja, dibaca dari UI thread
 * (karena itu @Volatile).
 */
class TargetState {
    @Volatile var connectedAddr: String? = null
    var connectedSince = 0L
    @Volatile var ownerState: OwnerState = OwnerState.Unknown
    /** Apakah com.kaspersky.kes terpasang di HP target: null = belum diketahui (tombol Kaspersky mati). */
    @Volatile var kasperskyInstalled: Boolean? = null
    /** Apakah com.hmdm.launcher terpasang di HP target: null = belum diketahui (tombol Owner/Launcher mati). */
    @Volatile var hmdmInstalled: Boolean? = null
    /** Apakah adbd HP target sudah berjalan dalam mode TCP/IP (port aktif): true = tombol "Aktifkan Mode Nirkabel" dimatikan. */
    @Volatile var tcpipActive: Boolean? = null
    /** Status tiap aplikasi wajib di HP target: null = belum diketahui. */
    @Volatile var appsInstalled: Map<String, String?>? = null
    /** Jenis perangkat yang dicek untuk [appsInstalled]; hasil dipakai hanya bila sama dengan jenis yang sedang terpilih. */
    @Volatile var appsType: String? = null
    /** Status tiga izin com.hmdm.launcher di HP target: null = belum diketahui. */
    @Volatile var hmdmPerms: HmdmPermissions? = null
    /** Model HP target hasil baca di kartu Informasi Status (model, jenis terdeteksi atau null). */
    @Volatile var deviceModel: Pair<String, String?>? = null
    /** True hanya bila model HP target terbaca DAN ada di daftar. Selain itu perintah cek instalasi & izin tidak dijalankan. */
    fun modelRecognized(): Boolean = deviceModel?.second != null
    /**
     * True bila model HP target dikenali (jenis diketahui) tetapi daftar aplikasi jenis itu kosong: pemeriksaan
     * instalasi & izin dihentikan.
     */
    fun modelAppListEmpty(): Boolean = deviceModel?.second?.let { VersionStore.packages(it).isEmpty() } == true
    /** Jenis perangkat yang dicek untuk [hmdmPerms]; hasil dipakai hanya bila sama dengan jenis yang sedang terceklis. */
    @Volatile var permType: String? = null

    /** Lupakan sambungan dan semua status yang pernah dibaca dari HP target (koneksi putus atau gagal). */
    fun reset() {
        connectedAddr = null
        connectedSince = 0L
        ownerState = OwnerState.Unknown
        kasperskyInstalled = null
        hmdmInstalled = null
        tcpipActive = null
        appsInstalled = null
        hmdmPerms = null
        permType = null
        deviceModel = null
    }
}
