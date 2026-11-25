package co.sat.hmdminstaller

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Koneksi ADB dari dalam aplikasi. Kunci RSA + sertifikat dibuat sekali dan disimpan,
 * karena status "paired" di HP target terikat pada kunci ini.
 */
class AdbManager(context: Context) : AbsAdbConnectionManager() {

    private val keyFile = File(context.filesDir, "adb_key.pk8")
    private val certFile = File(context.filesDir, "adb_cert.der")
    private val key: PrivateKey
    private val cert: Certificate

    /**
     * Alasan kunci lama tidak terpakai dan kunci baru dibuat (null = kunci lama dipakai, atau memang belum ada).
     * Bila terisi, semua HP target harus di-pair ulang; pemanggil sebaiknya menampilkannya di log.
     */
    val keyResetReason: String?

    init {
        setApi(Build.VERSION.SDK_INT)
        // Isi kunci format lama (teks biasa) yang berhasil dibaca; dibungkus ulang dengan Keystore di bawah.
        var legacyPlain: ByteArray? = null
        var k: PrivateKey? = null
        var c: Certificate? = null
        var reason: String? = null
        if (keyFile.exists() || certFile.exists()) {
            try {
                if (!keyFile.exists() || !certFile.exists()) {
                    reason = "salah satu file kunci ADB hilang"
                } else {
                    val raw = keyFile.readBytes()
                    val wrapped = KeyVault.isWrapped(raw)
                    val keyBytes = if (wrapped) KeyVault.decrypt(raw) else raw
                    val pk = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                    val ct = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
                    // Pastikan kunci privat dan sertifikat satu pasangan (bisa tidak cocok bila proses mati saat menulis).
                    // Bila tipe kunci tidak bisa dibaca modulusnya, anggap cocok agar kunci lama tidak dibuang tanpa alasan.
                    val pm = (pk as? RSAKey)?.modulus
                    val cm = (ct.publicKey as? RSAKey)?.modulus
                    val sameKey = pm == null || cm == null || pm == cm
                    if (sameKey) {
                        k = pk; c = ct
                        if (!wrapped) legacyPlain = keyBytes
                    } else reason = "kunci dan sertifikat ADB tidak cocok"
                }
            } catch (e: Exception) {
                reason = "file kunci ADB tidak terbaca (${e.javaClass.simpleName})"
            }
        }
        if (k == null || c == null) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=HMDM Installer")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 24L * 3600 * 1000),
                Date(now + 20L * 365 * 24 * 3600 * 1000), name, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val x509 = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            // Urutan: sertifikat dulu, kunci terakhir. Kunci lama baru tergantikan setelah sertifikat baru utuh;
            // bila proses mati di antaranya, pemeriksaan pasangan di atas akan memicu pembuatan ulang yang bersih.
            writeAtomic(certFile, x509.encoded)
            val encoded = kp.private.encoded
            writeAtomic(keyFile, KeyVault.encrypt(encoded) ?: encoded)
            k = kp.private
            c = x509
        }
        keyResetReason = reason
        key = checkNotNull(k) { "kunci ADB tidak tersedia" }
        cert = checkNotNull(c) { "sertifikat ADB tidak tersedia" }
        // Migrasi: kunci lama yang masih teks biasa dibungkus. Gagal = biarkan seperti semula, tanpa mengganggu.
        legacyPlain?.let { plain -> KeyVault.encrypt(plain)?.let { quietly { writeAtomic(keyFile, it) } } }
    }

    /** Tulis ke file sementara lalu ganti nama, supaya file tujuan tidak pernah berisi data setengah jadi. */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("tidak bisa menyimpan ${target.name}")
            }
        }
    }

    override fun getPrivateKey(): PrivateKey = key

    override fun getCertificate(): Certificate = cert

    override fun getDeviceName(): String = "HMDM-Installer"
}
