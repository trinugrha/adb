package co.sat.hmdminstaller

import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.IOException

/*
 * Dialog pengaturan (Model Perangkat, Aplikasi dan Versi) yang dipisah dari MainActivity.
 * Dipakai sebagai fungsi ekstensi MainActivity, jadi tetap memakai state dan fungsi Activity yang sama.
 */

/** Batas ukuran file impor (byte). */
private const val MAX_IMPORT_BYTES = 1_000_000

private fun MainActivity.toastLong(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

/**
 * Minta pengguna memilih lokasi file, lalu tulis hasil [buildJson] ke sana. [what] nama data untuk pesan (mis. "model
 * perangkat"). Alasan singkat berbahasa Indonesia untuk teknisi; teks exception mentah hanya di log DETAIL.
 */
private fun MainActivity.pickExport(what: String, fileName: String, buildJson: () -> String) {
    onExportPicked = { uri ->
        try {
            writeText(uri, buildJson())
            toastLong("Export $what berhasil")
        } catch (e: Exception) {
            val msg = "Export $what gagal: file tidak bisa ditulis. Pilih lokasi lain, lalu coba lagi"
            toastLong(msg)
            log(LogLevel.ERR, msg)
            log(LogLevel.DETAIL, Fmt.cause(e))
        }
    }
    exportVersionsLauncher.launch(fileName)
}

/**
 * Minta pengguna memilih file JSON, lalu serahkan isinya ke [onParsed] yang memasukkannya ke draf dan
 * mengembalikan nama jenis yang diimpor. Hasil baru berlaku setelah Simpan.
 */
private fun MainActivity.pickImport(what: String, onParsed: (String) -> Set<String>) {
    onImportPicked = { uri ->
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("tidak bisa membaca file")
            if (bytes.size > MAX_IMPORT_BYTES) throw IllegalArgumentException("file terlalu besar")
            val imported = onParsed(String(bytes, Charsets.UTF_8))
            toastLong("Import $what berhasil (${imported.joinToString()}). Ketuk \"Simpan\" untuk menerapkan")
        } catch (e: Exception) {
            // Pesan dari kode ini sendiri (IllegalArgumentException / "tidak bisa ...") sudah berbahasa Indonesia
            // dan dipakai sebagai alasan; exception sistem diganti alasan umum.
            val reason = e.message?.takeIf {
                e is IllegalArgumentException || (e is IOException && it.startsWith("tidak bisa"))
            } ?: "file tidak bisa dibaca"
            val msg = "Import $what gagal: $reason. Pilih file hasil Export $what"
            toastLong(msg)
            log(LogLevel.ERR, msg)
            log(LogLevel.DETAIL, Fmt.cause(e))
        }
    }
    importVersionsLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
}

private fun MainActivity.writeText(uri: Uri, text: String) {
    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        ?: throw IOException("tidak bisa menulis file")
}

/**
 * Dialog Pengaturan Model Perangkat: tambah/hapus model (ro.product.model) untuk POS / PDA / TABLET.
 * Model ini dipakai kartu Informasi Status untuk memilih jenis perangkat otomatis. Disimpan saat menekan Simpan.
 */
internal fun MainActivity.showModelSettings() {
    if (isFinishing || busy) return
    ModelSettingsDialog().show(this)
}

/**
 * Isi dialog Model Perangkat. Draf ditahan di sini (juga saat pindah jenis) dan baru ditulis ke [ModelStore] saat
 * Simpan. Fungsi anggota berbentuk ekstensi MainActivity supaya tetap memakai Activity yang sama (konteks, log, state)
 * tanpa parameter tambahan.
 */
private class ModelSettingsDialog {
    private val types = DeviceCatalog.DEVICE_MODELS.keys.toList()
    private val draft = LinkedHashMap<String, MutableList<String>>()
    private var current = types.first()
    private lateinit var ui: SettingsUi
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    fun show(act: MainActivity) {
        with(act) {
            for (t in types) draft[t] = ModelStore.models(t).toMutableList()
            if (deviceType in types) current = deviceType

            ui = SettingsUi(this)
            scroll = ScrollView(this)
            list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val btnAdd = ui.addButton("Tambah model")
            val btnExport = ui.outlinedButton("Export", R.drawable.ic_upload_file)
            val btnImport = ui.outlinedButton("Import", R.drawable.ic_download)

            // Pilihan jenis perangkat: tombol segmen (POS / TABLET / PDA).
            val segment = ui.typeSegment(types, { current }) { t ->
                current = t
                buildList()
            }
            btnAdd.setOnClickListener { showAddDialog() }
            btnExport.setOnClickListener {
                // Ekspor daftar yang sedang tampil di dialog (termasuk perubahan yang belum disimpan).
                pickExport("model perangkat", "model-perangkat.json") { ModelStore.toJson(types, draft) }
            }
            btnImport.setOnClickListener {
                pickImport("model perangkat") { text ->
                    val parsed = ModelStore.parseJson(text, types)
                    for ((t, l) in parsed) draft[t] = l.toMutableList()
                    buildList()
                    parsed.keys
                }
            }

            scroll.addView(ui.body(segment.view, btnAdd, btnExport, btnImport, list))
            buildList()

            showPopup(
                "Pengaturan Model Perangkat", "Model yang dikenali untuk memilih POS / PDA / TABLET otomatis.",
                icon = R.drawable.ic_settings, content = scroll, ownScroll = true, hasInput = true,
                positive = "Simpan", negative = getString(R.string.action_cancel)
            ) { save() }
        }
    }

    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** Jenis yang sudah memuat model [m], atau null. */
    private fun dupType(m: String): String? =
        types.firstOrNull { t -> draft.getValue(t).any { norm(it) == norm(m) } }

    private fun MainActivity.buildList() {
        list.removeAllViews()
        val items = draft.getValue(current)
        list.addView(ui.sectionLabel("DAFTAR MODEL · ${items.size}"), ui.lpTop(R.dimen.section_gap))
        for (m in items.toList()) {
            val del = ui.iconButton(R.drawable.ic_delete, ui.cDanger, "Hapus $m") {
                draft.getValue(current).remove(m)
                buildList()
            }
            list.addView(ui.itemCard(m, current, false, del), ui.lpTop(R.dimen.card_gap))
        }
    }

    private fun MainActivity.showAddDialog() {
        val inModel = ui.field("contoh: SM-T225", "", capitalize = true)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.label("Nama model", bold = true, color = ui.cText))
            addView(inModel, ui.lpField())
        }
        showPopup(
            "Tambah Model ke $current", icon = R.drawable.ic_add, content = form, hasInput = true,
            positive = "Tambah", negative = getString(R.string.action_cancel)
        ) {
            // Isian belum valid: popup tetap terbuka (return false).
            val m = inModel.text?.toString()?.trim().orEmpty()
            val d = dupType(m)
            when {
                norm(m).isEmpty() -> {
                    toastLong("Tambah model gagal: nama model belum diisi. Contoh: SM-T225")
                    false
                }
                d != null -> {
                    toastLong("Tambah model gagal: $m sudah ada di daftar $d. Hapus dulu dari daftar $d bila ingin memindahkannya")
                    false
                }
                else -> {
                    draft.getValue(current).add(m)
                    buildList()
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    true
                }
            }
        }
    }

    /** Tulis draf ke penyimpanan dan buang hasil cek lama. Selalu true (popup ditutup). */
    private fun MainActivity.save(): Boolean {
        for (t in types) ModelStore.save(t, draft.getValue(t))
        target.deviceModel = null
        // Daftar model berubah: hasil cek lama tidak berlaku, Status Instalasi dan Status Izin kembali "Belum
        // diperiksa".
        clearAppChecks()
        refreshApps()
        refreshPerms()
        applyButtonState(busy)
        toastLong("Pengaturan model perangkat disimpan. Ketuk \"Cek Status\" untuk mengecek ulang")
        return true
    }
}

/**
 * Dialog Setting: ubah daftar aplikasi (tambah/hapus) dan versi yang diharapkan per jenis perangkat
 * (POS / TABLET / PDA). Kolom versi dikosongkan = aplikasi hanya dicek terpasang, versinya tidak dibandingkan.
 * Perubahan ditahan sebagai draf (juga saat pindah jenis) dan baru disimpan saat menekan Simpan.
 */
internal fun MainActivity.showVersionSettings() {
    if (isFinishing || busy) return
    VersionSettingsDialog().show(this)
}

/** Isi dialog Aplikasi dan Versi; struktur sama seperti [ModelSettingsDialog]. */
private class VersionSettingsDialog {
    private val types = DeviceCatalog.DEVICE_PACKAGES.keys.toList()
    // Draf per jenis: daftar paket (berurutan) dan versinya, awalnya dari nilai yang berlaku sekarang.
    private val draftPkgs = LinkedHashMap<String, MutableList<String>>()
    private val draftVers = LinkedHashMap<String, MutableMap<String, String>>()
    private val fields = LinkedHashMap<String, EditText>()
    private var current = types.first()
    private lateinit var ui: SettingsUi
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    fun show(act: MainActivity) {
        with(act) {
            for (t in types) {
                draftPkgs[t] = VersionStore.packages(t).toMutableList()
                draftVers[t] = VersionStore.expected(t).toMutableMap()
            }
            if (deviceType in types) current = deviceType

            ui = SettingsUi(this)
            scroll = ScrollView(this)
            list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val btnAdd = ui.addButton("Tambah aplikasi")
            val btnExport = ui.outlinedButton("Export", R.drawable.ic_upload_file)
            val btnImport = ui.outlinedButton("Import", R.drawable.ic_download)

            // Pilihan jenis perangkat: tombol segmen (POS / TABLET / PDA).
            val segment = ui.typeSegment(types, { current }) { t ->
                commitFields()
                current = t
                buildFields()
            }
            btnAdd.setOnClickListener { showAddDialog() }
            btnExport.setOnClickListener {
                // Ekspor data yang sudah tersimpan (perubahan di dialog yang belum disimpan tidak ikut).
                pickExport("versi aplikasi", "versi-aplikasi.json") {
                    VersionStore.toJson(
                        types,
                        types.associateWith { VersionStore.packages(it) },
                        types.associateWith { VersionStore.expected(it) }
                    )
                }
            }
            btnImport.setOnClickListener {
                pickImport("versi aplikasi") { text ->
                    val parsed = VersionStore.parseJson(text, types)
                    for ((t, pv) in parsed) {
                        draftPkgs[t] = pv.first.toMutableList()
                        draftVers[t] = pv.second.toMutableMap()
                    }
                    buildFields()
                    parsed.keys
                }
            }

            scroll.addView(ui.body(segment.view, btnAdd, btnExport, btnImport, list))
            // Isi kolom SEBELUM dialog tampil. Android memeriksa ada-tidaknya kolom ketik saat dialog dibuka;
            // bila kosong, keyboard diblokir (FLAG_ALT_FOCUSABLE_IM) dan versi tidak bisa diketik.
            buildFields()

            showPopup(
                "Pengaturan Aplikasi dan Versi", "Daftar aplikasi dan versi yang dicek per jenis perangkat.",
                icon = R.drawable.ic_settings, content = scroll, ownScroll = true, hasInput = true,
                positive = "Simpan", negative = getString(R.string.action_cancel)
            ) { save() }
        }
    }

    /** Salin isi kolom versi ke draf jenis yang sedang tampil. */
    private fun commitFields() {
        val d = draftVers.getValue(current)
        for ((pkg, et) in fields) {
            val v = et.text?.toString()?.trim().orEmpty()
            if (v.isNotEmpty()) d[pkg] = v else d.remove(pkg)
        }
    }

    private fun MainActivity.buildFields() {
        list.removeAllViews()
        fields.clear()
        val pkgs = draftPkgs.getValue(current)
        val vers = draftVers.getValue(current)
        list.addView(ui.sectionLabel("DAFTAR APLIKASI · ${pkgs.size}"), ui.lpTop(R.dimen.section_gap))
        for (pkg in pkgs.toList()) {
            // EditText hanya penampung nilai versi (tidak ditampilkan); versi tampil sebagai lencana.
            val et = ui.field("tanpa cek versi", vers[pkg].orEmpty()).apply {
                isFocusable = false
                isCursorVisible = false
            }
            val edit = ui.iconButton(R.drawable.ic_edit, ui.cEdit, "Edit versi $pkg") { showEditVersionDialog(pkg) }
            val del = ui.iconButton(R.drawable.ic_delete, ui.cDanger, "Hapus $pkg") {
                commitFields()
                draftPkgs.getValue(current).remove(pkg)
                draftVers.getValue(current).remove(pkg)
                buildFields()
            }
            val verText = vers[pkg].orEmpty()
            val badge = if (verText.isEmpty()) "tanpa cek versi" else "v$verText"
            list.addView(ui.itemCard(pkg, badge, verText.isEmpty(), edit, del), ui.lpTop(R.dimen.card_gap))
            fields[pkg] = et
        }
    }

    private fun MainActivity.showEditVersionDialog(pkg: String) {
        commitFields()
        val inVer = ui.field("kosong = tanpa cek versi", draftVers.getValue(current)[pkg].orEmpty())
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.label(pkg, bold = true, size = R.dimen.text_body, color = ui.cText))
            addView(ui.label("Versi (versionName)", bold = true, color = ui.cText), ui.lpTop(R.dimen.space_lg))
            addView(inVer, ui.lpField())
        }
        showPopup(
            "Edit Versi", icon = R.drawable.ic_edit, content = form, hasInput = true,
            positive = getString(R.string.action_ok), negative = getString(R.string.action_cancel)
        ) {
            val v = inVer.text?.toString()?.trim().orEmpty()
            if (v.isNotEmpty()) draftVers.getValue(current)[pkg] = v else draftVers.getValue(current).remove(pkg)
            buildFields()
            true
        }
    }

    private fun MainActivity.showAddDialog() {
        val inPkg = ui.field("com.contoh.app", "")
        val inVer = ui.field("contoh: 1.0.0", "")
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.label("Nama paket", bold = true, color = ui.cText))
            addView(inPkg, ui.lpField())
            addView(ui.label("Versi (wajib diisi)", bold = true, color = ui.cText), ui.lpTop(R.dimen.space_lg))
            addView(inVer, ui.lpField())
        }
        showPopup(
            "Tambah Aplikasi ke $current", icon = R.drawable.ic_add, content = form, hasInput = true,
            positive = "Tambah", negative = getString(R.string.action_cancel)
        ) {
            // Isian belum valid: popup tetap terbuka (return false).
            val pkg = inPkg.text?.toString()?.trim().orEmpty()
            val ver = inVer.text?.toString()?.trim().orEmpty()
            when {
                !isValidPackageName(pkg) -> {
                    toastLong("Tambah aplikasi gagal: nama paket tidak valid. Contoh: com.contoh.app")
                    false
                }
                ver.isEmpty() -> {
                    toastLong("Tambah aplikasi gagal: versi tidak boleh kosong. Isi versionName yang diharapkan")
                    false
                }
                pkg in draftPkgs.getValue(current) -> {
                    toastLong("Tambah aplikasi gagal: aplikasi sudah ada di daftar $current. Ubah versinya lewat daftar yang ada")
                    false
                }
                else -> {
                    commitFields()
                    draftPkgs.getValue(current).add(pkg)
                    draftVers.getValue(current)[pkg] = ver
                    buildFields()
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    true
                }
            }
        }
    }

    /** Tulis draf ke penyimpanan dan buang hasil cek lama. Selalu true (popup ditutup). */
    private fun MainActivity.save(): Boolean {
        commitFields()
        for (t in types) VersionStore.save(t, draftPkgs.getValue(t), draftVers.getValue(t))
        // Hasil cek lama dibuat tidak berlaku karena daftar/versi berubah; status, model perangkat, dan izin kembali
        // "Belum diperiksa".
        clearAppChecks()
        target.deviceModel = null
        refreshApps()
        refreshPerms()
        applyButtonState(busy)
        toastLong("Pengaturan aplikasi dan versi disimpan. Ketuk \"Cek Status\" untuk mengecek ulang")
        return true
    }
}
