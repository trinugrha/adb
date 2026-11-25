package co.sat.hmdminstaller

/**
 * Katalog perangkat: daftar aplikasi, versi, dan model bawaan per jenis (POS / PDA / TABLET), plus pencocokan model
 * dan versi. Murni data dan fungsi tanpa state; perubahan pengguna ada di [VersionStore] dan [ModelStore].
 */
object DeviceCatalog {
    /** Daftar aplikasi wajib per jenis perangkat (dipakai ceklist "Status Instalasi"). */
    val DEVICE_PACKAGES: Map<String, List<String>> = linkedMapOf(
        "POS" to emptyList(),
        "PDA" to emptyList(),
        "TABLET" to emptyList()
    )
    /**
     * Versi aplikasi yang diharapkan (versionName) per jenis perangkat. Jenis tanpa daftar versi
     * hanya dicek terpasang atau tidak.
     */
    val EXPECTED_VERSIONS: Map<String, Map<String, String>> = emptyMap()

    /**
     * Daftar model bawaan (kosong; diisi lewat Pengaturan Model Perangkat) per jenis perangkat; dipakai kartu Informasi
     * Status untuk memilih POS / PDA / TABLET otomatis.
     */
    val DEVICE_MODELS: Map<String, List<String>> = linkedMapOf(
        "POS" to emptyList(),
        "PDA" to emptyList(),
        "TABLET" to emptyList()
    )

    private fun normModel(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }

    /**
     * Cocokkan [model] dengan [DEVICE_MODELS]. Cocok bila sama persis (abaikan huruf besar/kecil, spasi, tanda hubung)
     * atau model hanya berbeda 1-2 huruf varian di belakang (mis. SM-T225N untuk SM-T225). null = tidak dikenal.
     */
    fun detectType(model: String): String? {
        val m = normModel(model)
        if (m.isEmpty()) return null
        for (type in DEVICE_MODELS.keys) {
            for (c in ModelStore.models(type)) {
                val n = normModel(c)
                if (m == n) return type
                val isSamsungVariant = n.startsWith("SM") && m.startsWith(n) &&
                    m.length - n.length <= 2 && m.substring(n.length).all { it.isLetter() }
                if (isSamsungVariant) return type
            }
        }
        return null
    }

    /** Versi terbaca sama dengan harapan (abaikan spasi dan huruf besar/kecil). */
    fun versionMatches(expected: String, actual: String?): Boolean =
        actual != null && actual.trim().equals(expected.trim(), ignoreCase = true)
}
