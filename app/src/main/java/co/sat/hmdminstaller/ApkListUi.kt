package co.sat.hmdminstaller

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/* Satu baris di daftar APK yang akan dipasang, dipisah dari MainActivity. */

/**
 * Baris untuk [item] ke-[index] (mulai 0): nomor, nama, ukuran, dan tombol hapus.
 * [canRemove] false = tombol hapus mati dan redup; [onRemove] dipanggil saat tombol hapus diketuk.
 */
internal fun Context.apkRow(index: Int, item: ApkItem, canRemove: Boolean, onRemove: () -> Unit): View {
    val ctx = this
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_chip)
        setPadding(px(R.dimen.space_md), px(R.dimen.space_sm), px(R.dimen.space_xs), px(R.dimen.space_sm))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { if (index > 0) topMargin = px(R.dimen.space_sm) }
    }
    row.addView(TextView(ctx).apply {
        text = "${index + 1}"
        setTextSizeRes(R.dimen.text_caption)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(colorOf(R.color.accent_text))
        minWidth = px(R.dimen.index_min_width)
    })
    val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    col.addView(TextView(ctx).apply {
        text = item.name
        setTextSizeRes(R.dimen.text_body)
        setTextColor(colorOf(R.color.text))
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.MIDDLE
    })
    col.addView(TextView(ctx).apply {
        text = item.sizeText
        setTextSizeRes(R.dimen.text_caption)
        setTextColor(colorOf(R.color.muted))
    })
    row.addView(col)
    row.addView(ImageButton(ctx).apply {
        id = R.id.apkRemoveTag
        setImageResource(R.drawable.ic_delete)
        setColorFilter(colorOf(R.color.danger))
        background = null
        contentDescription = "Hapus ${item.name} dari daftar"
        isEnabled = canRemove
        isClickable = canRemove
        alpha = if (canRemove) 1f else 0.3f
        layoutParams = px(R.dimen.icon_button_size).let { LinearLayout.LayoutParams(it, it) }
        setOnClickListener { onRemove() }
    })
    return row
}
