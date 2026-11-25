package co.sat.hmdminstaller

/**
 * Tingkat/jenis satu entri log. Warna dan penanda ditentukan dari sini,
 * bukan ditebak dari isi teks, sehingga tampilan log konsisten.
 *
 * Contoh tampilan:
 * ```
 * ── Pasang Aplikasi · 14:02:11
 * [1/2] hmdm-launcher.apk (12.3 MB)
 * > adb install -r hmdm-launcher.apk
 * ✓ Terpasang · 4.2 dtk
 * [2/2] agent.apk (3.1 MB)
 * > adb install -r agent.apk
 * ✗ Gagal: versi lebih rendah dari yang terpasang (INSTALL_FAILED_VERSION_DOWNGRADE)
 * Ringkasan: 1 berhasil, 1 gagal dari 2 APK
 * ✗ agent.apk: versi lebih rendah dari yang terpasang
 * ✗ Gagal: Pasang Aplikasi · 6.0 dtk
 * ```
 *
 * Aturan penulisan pesan:
 * - Tanpa titik di akhir (dibuang otomatis oleh `MainActivity.log`, kecuali baris DETAIL).
 * - HP yang sedang dipasang disebut "HP target". Fitur yang dijadikan HMDM disebut "MDM Mode", sama dengan tombol dan
 *   kartu di layar; "device owner" hanya untuk istilah teknis Android (alasan penolakan sistem, perintah dpm).
 * - Ukuran dan durasi lewat [Fmt] (titik desimal tetap, mis. 12.3 MB, 4.2 dtk).
 * - Percobaan ulang: "Percobaan 1/2 gagal (alasan), mencoba lagi…"; satuan waktu selalu "dtk". Alasan di sini singkat
 *   (tanpa saran perbaikan); saran lengkap hanya di baris ✗ akhir.
 * - Baris ✗ akhir berbentuk "<Aksi> gagal: <alasan singkat>. <Saran diawali huruf besar>" (satu bentuk untuk connect,
 *   sambung ulang, install, Pair, buka USB, salin APK, baca status, alamat tidak valid, dst; jangan menulis alasan
 *   tanpa awalan aksi). Awalan "Gagal ..." hanya dipakai di install, karena aksinya sudah jelas dari baris [n/m] di
 *   atasnya. Saran tidak ditaruh di dalam kurung dan tidak diawali huruf kecil.
 * - Status "belum ada / belum diset" memakai WARN (sama dengan warna kotak status di layar), bukan OK.
 * - Sambung ulang otomatis (saat install) tidak mencetak "> adb connect" per percobaan, cukup hasil akhirnya.
 * - Koneksi USB selalu disebut dengan label "USB · <nama perangkat>", tidak pernah dengan alamat loopback internal
 *   (127.0.0.1:<port>).
 * - Judul proses (HEAD/END) memakai nama yang sama dengan tombol atau kartu di layar.
 * - Istilah Android ditulis huruf kecil di tengah kalimat: "device owner", "profile owner".
 * - Rantai exception teknis (mis. "IOException: ... <- ...") dan nama kelas exception hanya di baris DETAIL; baris ✗
 *   memuat alasan singkat + saran. Jejak crash dari sesi sebelumnya juga baris DETAIL (maks. 14 baris, sisanya
 *   diringkas "… N baris lain tidak ditampilkan").
 * - DETAIL hanya untuk output mentah yang menambah informasi (bukan pengulangan alasan di baris ✗). Baris DETAIL yang
 *   terlalu panjang dipotong otomatis dan diakhiri "…" supaya jelas bahwa teksnya tidak utuh.
 * - Penyebab teknis (alasan putus koneksi, galat tak terduga, jejak crash) selalu di baris DETAIL di bawah baris ✗ / !,
 *   tidak digabung ke dalamnya.
 * - Saran per kode install (mis. INSTALL_FAILED_UPDATE_INCOMPATIBLE) ditulis sebagai kalimat sendiri setelah alasan,
 *   tidak di dalam kurung.
 * - Petunjuk langkah berikutnya setelah proses berhasil (bukan output mentah) memakai STEP diawali "Berikutnya:", bukan
 *   DETAIL.
 * - Pemisah "·" hanya untuk durasi, jam, dan label perangkat (mis. "Terpasang · 4.2 dtk", "USB · Pixel 6"); antar
 *   klausa biasa pakai koma.
 * - Gagal connect yang dicetak pemanggil (USB, sambung ulang) tetap diikuti baris DETAIL penyebab teknis, sama seperti
 *   connect biasa.
 * - Pesan Toast memakai bentuk yang sama ("<Aksi> gagal: <alasan singkat>. <Saran>"). Teks exception mentah
 *   (`e.message`) tidak pernah masuk ke baris ✗ atau Toast; ia hanya ditulis sebagai baris DETAIL lewat [Fmt.cause].
 * - Popup gagal memuat baris ✗ proses itu sebagai butir "Penyebab" tanpa awalan "<Aksi> gagal: " (judul popup sudah
 *   memuat aksinya), lewat `MainActivity.errReason`.
 * - Teks status di layar (header, progres) memakai "·" hanya untuk label dan durasi; petunjuk dan angka dipisah koma
 *   atau "dari".
 * - Kutipan nama tombol, dialog, atau perintah memakai tanda kutip ganda ("..."), di log, popup, maupun Toast; tidak
 *   pakai kutip tunggal. Petunjuk ke tombol memakai kata "ketuk" (bukan "klik") dan nama tombol persis seperti tulisan
 *   di layar, mis. ketuk "Cek Status".
 * - Petunjuk ke tombol memakai label yang benar-benar ada di layar: koneksi kabel ditunjuk dengan "Pilih Perangkat" di
 *   tab "USB" (bukan "Connect USB", yang hanya nama proses di log dan judul popup). Satuan waktu di popup juga "dtk",
 *   bukan "detik".
 * - Jalur menu Android ditulis dengan "›" (Pengaturan › Keamanan), bukan ">".
 * - Penyebab putus koneksi (`AdbController.lastDropReason`) ditulis dalam bahasa sehari-hari karena tampil di popup;
 *   nama exception dan jawaban mentah ada di `lastDropDetail`, hanya untuk baris DETAIL.
 * - Judul popup memakai huruf kapital di tiap kata ("Pilih Perangkat USB", "Keluar dari Aplikasi?"), sama dengan judul
 *   proses dan nama tombol. Istilah "izin" dipakai untuk permission Android di log, popup, dan Toast; "permission"
 *   hanya untuk nama paket atau perintah teknis.
 * - Pesan exception di baris DETAIL ditulis berbahasa Indonesia tanpa panah ("USB ke soket", bukan "USB->soket") dan
 *   tanpa kata "Timeout" ("Waktu habis ...").
 * - Popup hasil: ikon dan warna ditentukan parameter `ok` di `showConnProblem`, bukan dari teks judul. Judul popup
 *   hasil juga berhuruf kapital di tiap kata: "<Proses> Gagal" / "<Proses> Berhasil" (mis. "Connect USB Gagal",
 *   "Koneksi Terputus").
 * - Mode TCP/IP disebut "mode nirkabel (TCP/IP)" di log dan popup, sama dengan kartu di layar; tanda "->" tidak dipakai
 *   di pesan log.
 * - HP yang menjalankan aplikasi ini disebut "HP ini" (bukan "HP teknisi" atau "perangkat ini"), pasangan dari "HP
 *   target".
 * - Urutan koneksi nirkabel ditulis "Pair" lalu "Connect" (bukan "dan"). Petunjuk tombol di teks status juga memakai
 *   label layar, mis. "Pilih Perangkat" di tab USB, bukan nama proses ("Connect USB", "Pilih Perangkat USB").
 * - Butir "Penyebab" di popup selalu diawali huruf kapital, juga untuk penyebab putus koneksi (`lastDropReason`, yang
 *   di log ditulis huruf kecil).
 * - Contoh isian di Toast ditulis sebagai kalimat sendiri: "Isi nama model. Contoh: SM-T225".
 * - Judul proses cek status memakai nama tombol "Cek Status" (bukan "Cek Informasi Status").
 * - Klausa berurutan dipisah koma sebelum "lalu" ("Colok ulang kabel, lalu ketuk ..."); urutan dua nama tombol tetap
 *   tanpa koma ("Pair" lalu "Connect").
 * - Petunjuk ke HP ini ditulis "layar HP ini", bukan "layar aplikasi ini".
 * - Popup gagal tanpa baris ✗ memakai baris ! proses itu (maks. 2, kecuali pesan "mencoba lagi") sebagai butir
 *   "Penyebab". Butir "Penyebab" selalu diawali huruf kapital, tanpa titik di akhir. Satuan detik di pesan batas waktu
 *   selalu bilangan bulat ("20 dtk").
 * - Popup berbahasa sehari-hari: kode teknis install ("(INSTALL_FAILED_...)") hanya di log; popup membuangnya lewat
 *   `MainActivity.plainReason`.
 * - Exception dari perintah shell masuk ke output mentah lewat `AdbController.shellError` ("Error: <Kelas>: <pesan>"),
 *   selalu sebagai baris DETAIL.
 * - Toast simpan pengaturan: "Pengaturan <nama> disimpan. Ketuk \"Cek Status\" untuk mengecek ulang"; nama sama dengan
 *   judul popup pengaturannya.
 * - Progres untuk satu APK tidak memakai "1 dari 1" (\"Memasang APK: nama.apk\").
 * - Label kartu status di layar berbahasa Indonesia ("STATUS MDM MODE", "STATUS INSTALASI", "STATUS IZIN"); judul
 *   dialog pengaturan memakai "dan", bukan "&" ("Pengaturan Aplikasi dan Versi"), sama dengan Toast simpan.
 * - Daftar "Periksa" yang sama dipakai satu sumber teks (mis. `QR_PAIR_CHECKLIST`), supaya popup tidak berbeda kalimat
 *   untuk kegagalan yang sama.
 * - Penutup daftar baris yang dipotong selalu lewat [Fmt.omitted] ("… 3 baris lain tidak ditampilkan"), bukan ditulis
 *   ulang di tiap pemanggil.
 * - Setiap proses: HEAD → isi → END_OK / END_ERR, lalu satu baris kosong.
 * - Penanda "sedang berlangsung" dan percobaan ulang diakhiri satu karakter elipsis "…" (bukan tiga titik "..."), sama
 *   dengan pemotongan baris DETAIL.
 * - Awalan aksi di baris ✗ memakai nama proses persis seperti judul HEAD (mis. "Hapus Data Aplikasi Kaspersky gagal:
 *   ..."), bukan versi huruf kecil.
 *   Awalan itu ditulis lewat konstanta [RunTitle] ("${RunTitle.CONNECT_USB} gagal: ..."), tidak diketik ulang, supaya
 *   judul HEAD, baris ✗, dan judul popup tidak bisa berbeda. Nama tombol "Pair dengan QR" sama dengan [RunTitle.PAIR_QR].
 * - Pesan Export/Import di pengaturan memakai nama tombol persis ("Export model perangkat gagal", "Import model
 *   perangkat berhasil"), bukan "Ekspor"/"Impor"/"diekspor".
 * - Pesan yang sama dari dua tempat (mis. daftar aplikasi kosong) ditulis sekali di [AdbMessages], bukan disalin ke
 *   tiap pemanggil.
 * - Frasa yang berulang di log, popup, dan teks status juga satu sumber di [AdbMessages]: cara menyambung
 *   ([AdbMessages.HOW_TO_CONNECT]), petunjuk setelah terhubung ([AdbMessages.NEXT_AFTER_CONNECT]), penutup popup gagal
 *   ([AdbMessages.SEE_LOG]), dan peringatan model tidak dikenal ([AdbMessages.unknownModel]).
 * - Petunjuk yang menyuruh membuka pengaturan menyebut letak tombolnya di layar ("lewat ikon gerigi", "lewat ikon + di
 *   kartu \"Informasi Status\""), bukan hanya nama dialognya.
 * - Semua popup gagal ditutup [AdbMessages.SEE_LOG], termasuk ringkasan APK gagal dan "Koneksi Terputus"; butirnya
 *   tidak menambah "(lihat log)" sendiri. Nama paket mentah (mis. com.kaspersky.kes) tidak masuk ke baris ✗ atau popup,
 *   hanya ke perintah di baris CMD.
 * - Teks yang sudah ada di strings.xml (judul kartu, tombol Batal / OK, Lihat Lainnya, isi kotak pilih APK) diambil
 *   lewat `getString`, tidak ditulis ulang di kode, supaya satu perubahan cukup di satu tempat.
 * - Pindah dari langkah Pasang Aplikasi ke Aktifkan MDM Mode di Proses Otomatis dicatat "Lanjut: <judul proses>"
 *   (STEP), memakai judul yang sama dengan tombol.
 * - Langkah lanjutan setelah Pair manual ([AdbMessages.NEXT_AFTER_PAIR]), petunjuk bila port connect tidak ketemu
 *   ([AdbMessages.CONNECT_MANUALLY]), dan alasan Pair dengan QR habis waktu ([AdbMessages.qrPairTimeout]) juga satu
 *   sumber di [AdbMessages]; log memakainya dengan awalan "Berikutnya:", popup dengan awalan "Langkah berikutnya:".
 * - Peringatan (WARN) tidak memuat perintah shell atau nama paket mentah, karena bisa ikut tampil sebagai butir
 *   "Penyebab" di popup; perintahnya sudah ada di baris CMD di atasnya.
 * - Paragraf tambahan di popup konfirmasi memakai label yang dikenali [formatPopupMessage] ("Langkah berikutnya:",
 *   "Catatan:", "Perhatian:"), bukan kalimat tanpa label.
 * - Jam dan kecepatan kirim lewat [Fmt.hms] dan [Fmt.speed] (Locale.US), tidak memakai `String.format` / `.format`
 *   langsung di pemanggil.
 * - Rincian per aplikasi pada hasil cek status (nama paket, versi terpasang dan yang diharapkan) ditulis sebagai baris
 *   DETAIL di bawah satu baris WARN ringkas ("Belum terpasang 2 dari 5 aplikasi"), bukan di dalam WARN.
 * - Popup gagal yang dibuat dari proses tanpa baris ✗ (Pair dengan QR habis waktu, tidak ada HP target di port USB)
 *   tetap memakai bentuk yang sama: butir "Penyebab:", daftar "Periksa:", lalu [AdbMessages.SEE_LOG].
 * - Konstanta petunjuk langkah ([AdbMessages.NEXT_AFTER_CONNECT], [AdbMessages.NEXT_AFTER_PAIR],
 *   [AdbMessages.CONNECT_MANUALLY]) berupa frasa tanpa awalan; pemanggil menambahkan "Berikutnya:" di log atau "Langkah
 *   berikutnya:" di popup.
 * - Status berhasil MDM Mode di log selalu "MDM Mode aktif: HMDM" (cek manual maupun setelah Aktifkan MDM Mode).
 * - Alasan "tidak ada HP target di port USB" satu sumber di [AdbMessages.NO_USB_TARGET]; log memakainya apa adanya,
 *   popup dengan huruf pertama kapital.
 * - Daftar "Periksa" untuk "Pasang Aplikasi + Aktifkan MDM Mode" mengikuti langkah yang gagal: daftar Pasang Aplikasi
 *   bila APK gagal dipasang, daftar Aktifkan MDM Mode bila APK sudah terpasang semua ([RunMessages.failureHint]).
 * - Peringatan dari thread latar tidak memuat nama thread; nama thread dan rantai exception hanya di baris DETAIL.
 * - Setiap kode install yang dikenali di [AdbMessages] punya alasan singkat dan saran sendiri, jadi baris ✗ dan popup
 *   gagal pasang selalu berbentuk "<alasan>. <saran>", bukan alasan tanpa langkah berikutnya.
 * - Popup berhasil "Aktifkan Mode Nirkabel" mengikuti log: bila mode nirkabel sudah aktif sebelumnya (sesi USB tetap
 *   tersambung), isinya "sudah aktif, tidak ada yang diubah", bukan "berhasil diaktifkan".
 * - Toast "<Aksi> gagal" yang menolak isian selalu memuat saran atau contoh setelah alasan, termasuk saat isian duplikat.
 * - Peringatan MDM Mode tidak bisa dibatalkan satu sumber di [RunMessages.OWNER_WARNING] (popup konfirmasi tidak
 *   menulis ulang), dan menyebut fitur sebagai "MDM Mode", bukan "device owner" atau "factory reset" (pakai "reset ke
 *   pengaturan pabrik"). Frasa akun selalu "akun (Google dan lainnya)", bukan "akun Google/pengguna".
 * - Popup gagal dari proses tanpa baris ✗ dibuat lewat [RunMessages.failureWithCause], bukan dirakit ulang di pemanggil.
 * - Popup berhasil memakai kalimat yang sama dengan baris ✓ di log (mis. mode nirkabel yang sudah aktif: "tidak perlu
 *   diubah lagi"). Awalan baris ✗ selalu nama proses yang sedang berjalan ("Connect USB gagal: ..."), termasuk untuk
 *   kegagalan membuka jalur USB.
 */
enum class LogLevel(val marker: String, val bold: Boolean = false) {
    /** Pembuka satu proses. */
    HEAD("── ", bold = true),
    /** Langkah / item yang sedang dikerjakan, mis. "[1/3] nama.apk". */
    STEP(""),
    /** Perintah ADB yang dijalankan. */
    CMD("> "),
    /** Berhasil. */
    OK("✓ "),
    /** Peringatan atau input yang belum lengkap; proses tidak berhenti karenanya. */
    WARN("! "),
    /** Gagal. */
    ERR("✗ "),
    /** Output mentah dari device, hanya untuk diagnosis. */
    DETAIL("    "),
    /** Ringkasan hasil. */
    SUMMARY("", bold = true),
    /** Penutup proses: berhasil. */
    END_OK("✓ ", bold = true),
    /** Penutup proses: gagal / berhenti. */
    END_ERR("✗ ", bold = true)
}
