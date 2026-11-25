package co.sat.hmdminstaller

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton

/* Popup seragam aplikasi (layout dialog_popup), dipisah dari MainActivity; dipakai sebagai fungsi ekstensi MainActivity. */

/** Warna ikon dan lencana popup: NAVY = netral/berhasil, DANGER = gagal/berbahaya. */
internal enum class PopupTone { NAVY, DANGER }

/**
 * Satu-satunya bentuk popup di aplikasi (layout dialog_popup): ikon bulat, judul di tengah, isi, lalu tombol.
 * Semua popup (hasil, konfirmasi, pilihan, form, QR, pengaturan) lewat sini supaya tampilannya seragam.
 * [tone] NAVY = netral/berhasil, DANGER = gagal/berbahaya. Tombol positif selalu navy.
 * [positive] null = tanpa tombol positif; [negative] null = tanpa tombol Batal.
 * [onPositive] mengembalikan true untuk menutup popup, false untuk tetap terbuka (mis. isian belum valid).
 * [ownScroll] true bila [content] sudah punya ScrollView sendiri (tidak dibungkus ScrollView popup lagi).
 * [hasInput] true bila [content] berisi kolom ketik (keyboard tidak boleh diblokir).
 */
internal fun MainActivity.showPopup(
    title: String,
    message: String? = null,
    icon: Int = R.drawable.ic_info,
    tone: PopupTone = PopupTone.NAVY,
    content: View? = null,
    ownScroll: Boolean = false,
    positive: String? = getString(R.string.action_ok),
    negative: String? = null,
    hasInput: Boolean = false,
    onNegative: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    onPositive: (() -> Boolean)? = null
): AlertDialog? {
    if (isFinishing || isDestroyed) return null
    val v = layoutInflater.inflate(R.layout.dialog_popup, null)
    val danger = tone == PopupTone.DANGER
    v.findViewById<View>(R.id.popupIconBg)
        .setBackgroundResource(if (danger) R.drawable.bg_badge_danger else R.drawable.bg_badge_navy)
    v.findViewById<ImageView>(R.id.popupIcon).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(colorOf(if (danger) R.color.danger else R.color.accent_text))
    }
    v.findViewById<TextView>(R.id.popupTitle).text = title
    val msg = v.findViewById<TextView>(R.id.popupMessage)
    if (message.isNullOrEmpty()) msg.visibility = View.GONE else {
        msg.text = formatPopupMessage(message)
        // Satu kalimat pendek: rata tengah di bawah judul; pesan berparagraf/berdaftar: rata kiri.
        msg.gravity = if (message.contains("\n")) Gravity.START else Gravity.CENTER_HORIZONTAL
    }
    if (content != null) {
        if (ownScroll) {
            val body = v.findViewById<FrameLayout>(R.id.popupBody)
            body.removeAllViews()
            body.addView(
                content,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        } else {
            v.findViewById<FrameLayout>(R.id.popupContent).apply {
                visibility = View.VISIBLE
                // Tanpa teks isi, kolom/daftar langsung di bawah judul dengan jarak kecil.
                if (message.isNullOrEmpty()) {
                    (layoutParams as ViewGroup.MarginLayoutParams).topMargin = px(R.dimen.space_sm)
                }
                addView(
                    content,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }
        }
    }
    val dlg = AlertDialog.Builder(this).setView(v).create()
    val ok = v.findViewById<MaterialButton>(R.id.popupOk)
    val cancel = v.findViewById<MaterialButton>(R.id.popupCancel)
    if (positive == null) ok.visibility = View.GONE else {
        ok.text = positive
        ok.setOnClickListener { if (onPositive?.invoke() != false) dlg.dismiss() }
    }
    if (negative != null) {
        cancel.visibility = View.VISIBLE
        cancel.text = negative
        // Tanpa tombol positif, Batal memakai lebar penuh dan tidak perlu jarak kanan.
        if (positive == null) (cancel.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = 0
        cancel.setOnClickListener { onNegative?.invoke(); dlg.dismiss() }
    }
    // Popup apa pun (konfirmasi, pengaturan, QR, dst) tidak boleh tampil di atas layar kunci PIN.
    if (lockView != null) return null
    openPopups.add(dlg)
    dlg.setOnDismissListener { openPopups.remove(dlg); onDismiss?.invoke() }
    dlg.show()
    if (hasInput) dlg.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    return dlg
}
