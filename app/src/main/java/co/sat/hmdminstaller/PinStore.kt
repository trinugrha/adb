package co.sat.hmdminstaller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Penyimpanan PIN: yang disimpan hanya hash PBKDF2-HMAC-SHA256 + salt acak (bukan PIN asli).
 * Salah PIN 5x berturut-turut → terkunci sementara; makin sering makin lama (30 dtk, 1 mnt, 2 mnt ... maks 15 mnt).
 */
class PinStore(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("pin_store", Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver

    /** Penghitung boot HP (tidak berubah selama proses hidup). -1 bila tidak terbaca. */
    private val bootCount: Int by lazy {
        try { Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1) } catch (e: Throwable) { -1 }
    }

    val isSet: Boolean get() = sp.contains(K_HASH) && sp.contains(K_SALT)

    fun set(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString(K_SALT, salt.toHex())
            .putString(K_HASH, hash(pin, salt).toHex())
            .putInt(K_FAILS, 0)
            .also { clearLock(it) }
            .apply()
    }

    private fun clearLock(e: android.content.SharedPreferences.Editor) {
        e.remove(K_LOCK_UNTIL).remove(K_LOCK_RT).remove(K_LOCK_BOOT).remove(K_LOCK_WALL).remove(K_LOCK_MS)
    }

    /** Buat PIN bawaan di thread latar bila belum ada (hash PBKDF2 berat, jangan di UI thread). */
    fun ensureDefaultAsync(pin: String) {
        EXEC.execute { if (!isSet) set(pin) }
    }

    /** Periksa PIN di thread latar; [onResult] dipanggil di UI thread. Antrean yang sama dengan [ensureDefaultAsync]. */
    fun verifyAsync(pin: String, onResult: (Boolean) -> Unit) {
        EXEC.execute {
            val ok = verify(pin)
            MAIN.post { onResult(ok) }
        }
    }

    /**
     * Sisa waktu terkunci (ms); 0 bila tidak sedang terkunci. Dihitung dengan jam monoton (elapsedRealtime) selama
     * HP belum reboot, jadi mengubah jam sistem tidak berpengaruh. Setelah reboot (jam monoton mulai dari nol) dipakai
     * jam dinding, dibatasi [MAX_LOCK_MS].
     */
    fun lockRemainingMs(): Long {
        val untilRt = sp.getLong(K_LOCK_RT, 0L)
        if (untilRt == 0L) return 0L
        val now = SystemClock.elapsedRealtime()
        // Penghitung boot tidak terbaca: hanya jam dinding yang tersedia.
        if (bootCount == -1) {
            return (sp.getLong(K_LOCK_WALL, 0L) - System.currentTimeMillis()).coerceIn(0L, MAX_LOCK_MS)
        }
        // HP sudah reboot sejak kunci dipasang: jam monoton mulai dari nol dan jam dinding bisa diubah orang, jadi
        // kunci TIDAK dipercepat. Lama kunci semula diulang penuh dari sekarang (hitungan monoton baru).
        // Kunci yang sudah habis sebelum reboot sudah dihapus di bawah, jadi tidak ikut terulang.
        if (sp.getInt(K_LOCK_BOOT, -2) != bootCount) {
            val ms = sp.getLong(K_LOCK_MS, MAX_LOCK_MS).coerceIn(BASE_LOCK_MS, MAX_LOCK_MS)
            sp.edit().putLong(K_LOCK_RT, now + ms).putInt(K_LOCK_BOOT, bootCount).apply()
            return ms
        }
        val left = untilRt - now
        if (left <= 0L) {
            sp.edit().also { clearLock(it) }.apply()   // sudah habis: bersihkan agar tidak terulang setelah reboot
            return 0L
        }
        return left.coerceAtMost(MAX_LOCK_MS)
    }

    /** true bila PIN benar. Selama masih terkunci selalu false (tanpa menghitung percobaan). */
    fun verify(pin: String): Boolean {
        if (lockRemainingMs() > 0) return false
        val saltHex = sp.getString(K_SALT, null) ?: return false
        val hashHex = sp.getString(K_HASH, null) ?: return false
        val ok = MessageDigest.isEqual(hash(pin, saltHex.fromHex()), hashHex.fromHex())
        if (ok) {
            sp.edit().putInt(K_FAILS, 0).also { clearLock(it) }.apply()
        } else {
            val fails = sp.getInt(K_FAILS, 0) + 1
            val e = sp.edit().putInt(K_FAILS, fails)
            if (fails % MAX_TRIES == 0) {
                val step = fails / MAX_TRIES                      // 1,2,3...
                val ms = (BASE_LOCK_MS shl (step - 1).coerceAtMost(10)).coerceAtMost(MAX_LOCK_MS)
                e.putLong(K_LOCK_RT, SystemClock.elapsedRealtime() + ms)
                    .putInt(K_LOCK_BOOT, bootCount)
                    .putLong(K_LOCK_WALL, System.currentTimeMillis() + ms)
                    .putLong(K_LOCK_MS, ms)
            }
            e.apply()
        }
        return ok
    }

    /** Sisa percobaan sebelum terkunci. */
    fun triesLeft(): Int = MAX_TRIES - (sp.getInt(K_FAILS, 0) % MAX_TRIES)

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    companion object {
        const val PIN_LENGTH = 4
        /**
         * PIN awal, hanya dipakai saat PIN belum pernah dibuat. Mengubah nilai ini tidak berpengaruh di HP yang sudah
         * pernah membuka aplikasi (uninstall dulu).
         */
        const val DEFAULT_PIN = "4321"
        private const val MAX_TRIES = 5
        private const val BASE_LOCK_MS = 30_000L
        private const val MAX_LOCK_MS = 15 * 60_000L
        private const val ITERATIONS = 120_000
        private const val K_HASH = "hash"
        private const val K_SALT = "salt"
        private const val K_FAILS = "fails"
        private const val K_LOCK_UNTIL = "lock_until"   // lama (jam dinding), hanya dibersihkan
        private const val K_LOCK_RT = "lock_until_rt"
        private const val K_LOCK_BOOT = "lock_boot"
        private const val K_LOCK_WALL = "lock_until_wall"
        private const val K_LOCK_MS = "lock_ms"            // lama kunci semula, untuk diulang penuh setelah reboot
        private val MAIN = Handler(Looper.getMainLooper())
        private val EXEC = Executors.newSingleThreadExecutor { r -> Thread(r, "pin-hash").apply { isDaemon = true } }
    }
}
