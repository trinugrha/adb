package co.sat.hmdminstaller

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Membungkus (enkripsi AES-GCM) kunci privat ADB sebelum ditulis ke penyimpanan aplikasi. Kunci pembungkus
 * hidup di Android Keystore dan tidak bisa diekspor, jadi salinan file `adb_key.pk8` (mis. dari backup atau
 * dump penyimpanan) tidak berguna di HP lain.
 *
 * Format file: "HKS1" + IV (12 byte) + ciphertext (termasuk tag GCM). File tanpa awalan itu dianggap format
 * lama (teks biasa) dan dibungkus ulang oleh [AdbManager].
 */
internal object KeyVault {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "hmdm_adb_wrap"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private val MAGIC = "HKS1".toByteArray(Charsets.US_ASCII)
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun isWrapped(blob: ByteArray): Boolean =
        blob.size > MAGIC.size + IV_LEN && MAGIC.indices.all { blob[it] == MAGIC[it] }

    /** Bungkus [plain]; null bila Keystore tidak bisa dipakai (sebagian HP POS/PDA bermasalah), pemanggil memakai format lama. */
    fun encrypt(plain: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key(createIfMissing = true))
        MAGIC + c.iv + c.doFinal(plain)
    } catch (e: Exception) {
        null
    }

    /** Buka bungkusan. Melempar exception bila kunci Keystore hilang atau data rusak; pemanggil membuat kunci ADB baru. */
    fun decrypt(blob: ByteArray): ByteArray {
        require(isWrapped(blob)) { "bukan format terbungkus" }
        val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + IV_LEN)
        val body = blob.copyOfRange(MAGIC.size + IV_LEN, blob.size)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(createIfMissing = false), GCMParameterSpec(TAG_BITS, iv))
        return c.doFinal(body)
    }

    private fun key(createIfMissing: Boolean): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) throw IllegalStateException("kunci Keystore tidak ditemukan")
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
