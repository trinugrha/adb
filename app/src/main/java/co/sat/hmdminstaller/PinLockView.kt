package co.sat.hmdminstaller

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * Layar login PIN (overlay penuh) di atas MainActivity: kartu putih di atas latar redup,
 * 4 kotak PIN, "Tampilkan PIN", dan tombol "Masuk". Dibuat lewat kode agar koneksi ADB
 * yang berjalan di MainActivity tidak ikut terputus.
 */
class PinLockView(
    context: Context,
    private val store: PinStore,
    private val onUnlocked: () -> Unit,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val input = EditText(context)
    private val boxes = mutableListOf<TextView>()
    private val boxRow = LinearLayout(context)
    private val message = TextView(context)
    private val eyeIcon = ImageView(context)
    private val eyeText = TextView(context)
    private val btnLogin = MaterialButton(context)
    private var showPin = false
    private val autoSubmit = Runnable { submit() }

    // ---- Terkunci sementara karena terlalu banyak salah ----
    // Harus dideklarasikan sebelum blok init, karena init memanggil startLockTicker().
    private val ticker = object : Runnable {
        override fun run() {
            val left = store.lockRemainingMs()
            if (left > 0) {
                setInputEnabled(false)
                val s = (left + 999) / 1000
                showMessage(context.getString(R.string.pin_locked, s / 60, s % 60))
                handler.postDelayed(this, 500)
            } else if (!input.isEnabled) {
                setInputEnabled(true)
                hideMessage()
                focusInput()
            }
        }
    }

    private fun px(id: Int) = context.px(id)
    private fun dimen(id: Int) = resources.getDimension(id)
    private fun color(id: Int) = context.colorOf(id)

    init {
        isClickable = true            // telan semua sentuhan agar layar di bawahnya tidak bisa disentuh
        isFocusable = false
        setBackgroundColor(color(R.color.scrim))   // latar redup (isi aplikasi tidak terbaca)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                px(R.dimen.popup_padding_h), px(R.dimen.popup_padding_top),
                px(R.dimen.popup_padding_h), px(R.dimen.space_2xl)
            )
            background = GradientDrawable().apply {
                cornerRadius = dimen(R.dimen.radius_dialog)
                setColor(color(R.color.surface))
            }
            elevation = px(R.dimen.dialog_elevation).toFloat()
        }
        addView(card, LayoutParams(-1, -2, Gravity.CENTER).apply {
            marginStart = px(R.dimen.space_xl)
            marginEnd = px(R.dimen.space_xl)
        })

        // Ikon gembok dalam kotak hijau muda
        card.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_lock)
            imageTintList = ColorStateList.valueOf(color(R.color.accent_text))
            px(R.dimen.space_lg).let { setPadding(it, it, it, it) }
            background = GradientDrawable().apply {
                cornerRadius = dimen(R.dimen.radius_xl)
                setColor(color(R.color.accent_text_soft))
            }
        }, LinearLayout.LayoutParams(px(R.dimen.pin_icon_box), px(R.dimen.pin_icon_box)))

        card.addView(TextView(context).apply {
            text = context.getString(R.string.pin_title)
            setTextSizeRes(R.dimen.text_title)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(R.dimen.space_lg) })

        card.addView(TextView(context).apply {
            text = context.getString(R.string.pin_subtitle)
            setTextSizeRes(R.dimen.text_body)
            setTextColor(color(R.color.muted))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(R.dimen.space_sm) })

        card.addView(TextView(context).apply {
            text = context.getString(R.string.pin_prompt, PinStore.PIN_LENGTH)
            setTextSizeRes(R.dimen.text_card_title)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(R.dimen.space_2xl) })

        // Kotak-kotak PIN
        boxRow.orientation = LinearLayout.HORIZONTAL
        boxRow.gravity = Gravity.CENTER
        repeat(PinStore.PIN_LENGTH) {
            val b = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSizeRes(R.dimen.text_pin_digit)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text))
            }
            boxes.add(b)
            boxRow.addView(b, LinearLayout.LayoutParams(px(R.dimen.pin_box_width), px(R.dimen.pin_box_height)).apply {
                marginStart = px(R.dimen.pin_box_gap)
                marginEnd = px(R.dimen.pin_box_gap)
            })
        }
        boxRow.setOnClickListener { focusInput() }
        card.addView(boxRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(R.dimen.space_md) })

        // EditText asli (tak terlihat) yang menampung ketikan; kotak di atas hanya tampilan.
        input.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(PinStore.PIN_LENGTH))
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            isCursorVisible = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    paintBoxes()
                    if (message.tag == "err") hideMessage()
                    // Digit terakhir terisi → langsung diperiksa; jika benar langsung masuk.
                    if ((s?.length ?: 0) == PinStore.PIN_LENGTH) {
                        this@PinLockView.handler.removeCallbacks(autoSubmit)
                        this@PinLockView.handler.postDelayed(autoSubmit, 100)
                    }
                }
            })
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
            }
        }
        card.addView(input, LinearLayout.LayoutParams(px(R.dimen.stroke_width), px(R.dimen.stroke_width)))

        message.apply {
            setTextSizeRes(R.dimen.text_body)
            gravity = Gravity.CENTER
            setTextColor(color(R.color.danger))
            visibility = View.GONE
        }
        card.addView(message, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(R.dimen.space_md) })

        // Tampilkan PIN
        val eyeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(R.dimen.space_md), px(R.dimen.space_sm), px(R.dimen.space_md), px(R.dimen.space_sm))
            setOnClickListener { showPin = !showPin; updateEye(); paintBoxes(); focusInput() }
        }
        eyeIcon.imageTintList = ColorStateList.valueOf(color(R.color.muted))
        eyeText.apply { setTextSizeRes(R.dimen.text_body); setTextColor(color(R.color.muted)) }
        eyeRow.addView(eyeIcon, LinearLayout.LayoutParams(px(R.dimen.icon_size), px(R.dimen.icon_size)))
        eyeRow.addView(eyeText, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(R.dimen.space_sm) })
        card.addView(eyeRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(R.dimen.space_sm) })
        updateEye()

        // Tombol Masuk
        btnLogin.apply {
            text = context.getString(R.string.pin_login)
            setTextSizeRes(R.dimen.text_card_title)
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setIconResource(R.drawable.ic_login)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = px(R.dimen.space_sm)
            cornerRadius = px(R.dimen.radius_lg)
            backgroundTintList = ColorStateList.valueOf(color(R.color.btn_main))
            setTextColor(color(R.color.on_tab_button))
            iconTint = ColorStateList.valueOf(color(R.color.on_tab_button))
            setOnClickListener { submit() }
        }
        card.addView(
            btnLogin,
            LinearLayout.LayoutParams(-1, px(R.dimen.control_height_large)).apply { topMargin = px(R.dimen.space_md) }
        )

        paintBoxes()
        startLockTicker()
    }

    private fun updateEye() {
        eyeIcon.setImageResource(if (showPin) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        eyeText.setText(if (showPin) R.string.pin_hide else R.string.pin_show)
    }

    private fun paintBoxes() {
        val s = input.text?.toString().orEmpty()
        val locked = store.lockRemainingMs() > 0
        boxes.forEachIndexed { i, b ->
            b.text = when {
                i >= s.length -> ""
                showPin -> s[i].toString()
                else -> "•"
            }
            val active = !locked && i == s.length.coerceAtMost(PinStore.PIN_LENGTH - 1) && input.hasFocus()
            b.background = GradientDrawable().apply {
                cornerRadius = dimen(R.dimen.radius_lg)
                setColor(color(R.color.box_bg))
                setStroke(px(R.dimen.stroke_width_strong), color(if (active) R.color.accent_text else R.color.stroke))
            }
        }
    }

    private fun showMessage(text: String) {
        message.text = text
        message.tag = "err"
        message.visibility = View.VISIBLE
    }
    private fun hideMessage() { message.tag = null; message.visibility = View.GONE }

    private fun submit() {
        handler.removeCallbacks(autoSubmit)   // cegah hitung ganda (auto-submit + Done/Masuk)
        if (store.lockRemainingMs() > 0) return
        val pin = input.text?.toString().orEmpty()
        if (pin.length < PinStore.PIN_LENGTH) {
            showMessage(context.getString(R.string.pin_too_short, PinStore.PIN_LENGTH))
            shake()
            return
        }
        if (verifying) return
        verifying = true
        // Hash PBKDF2 berat: dijalankan di thread latar, hasilnya kembali ke UI thread.
        store.verifyAsync(pin) { ok ->
            verifying = false
            if (!isAttachedToWindow) return@verifyAsync
            if (ok) {
                hideKeyboard()
                onUnlocked()
                return@verifyAsync
            }
            input.setText("")
            shake()
            if (store.lockRemainingMs() > 0) startLockTicker()
            else showMessage(context.getString(R.string.pin_wrong, store.triesLeft()))
        }
    }

    /** true selama PIN sedang diperiksa di thread latar (cegah kirim ganda). */
    private var verifying = false

    private fun shake() {
        val d = px(R.dimen.shake_distance).toFloat()
        boxRow.animate().translationX(d).setDuration(50).withEndAction {
            boxRow.animate().translationX(-d).setDuration(80).withEndAction {
                boxRow.animate().translationX(0f).setDuration(50).start()
            }.start()
        }.start()
    }

    private fun startLockTicker() { handler.removeCallbacks(ticker); handler.post(ticker) }

    private fun setInputEnabled(on: Boolean) {
        input.isEnabled = on
        btnLogin.isEnabled = on
        boxRow.alpha = if (on) 1f else 0.5f
        if (!on) { input.setText(""); hideKeyboard() }
    }

    // ---- Keyboard ----
    private fun focusInput() {
        if (!input.isEnabled) return
        input.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        paintBoxes()
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        input.setOnFocusChangeListener { _, _ -> paintBoxes() }
        handler.postDelayed({ focusInput() }, 250)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
