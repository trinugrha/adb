package co.sat.hmdminstaller

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/** Cegah Wi-Fi / CPU HP ini tidur saat operasi panjang (install APK besar). */
internal class PowerLocks(context: Context) {
    private val appContext = context.applicationContext
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Tahan Wi-Fi (performa tinggi) + CPU tetap aktif selama operasi berjalan. Panggil dengan false setelah selesai. */
    @Synchronized
    fun hold(on: Boolean) {
        quietly {
            if (on) {
                if (wifiLock == null) {
                    val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                               else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wifiLock = wm.createWifiLock(mode, "hmdm:adb").apply { setReferenceCounted(false) }
                }
                if (wakeLock == null) {
                    val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hmdm:adb")
                        .apply { setReferenceCounted(false) }
                }
                if (wifiLock?.isHeld == false) wifiLock?.acquire()
                if (wakeLock?.isHeld == false) wakeLock?.acquire(30 * 60 * 1000L)
            } else {
                if (wifiLock?.isHeld == true) wifiLock?.release()
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }
}
