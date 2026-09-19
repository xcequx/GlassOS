package com.uxspace.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating panel drawn over the Wireless Debugging pairing dialog.
 *
 * The dialog must stay in the foreground (mDNS dies when it backgrounds). The user
 * therefore cannot switch back to GlassOS — this overlay is the only reliable place
 * to type the 6-digit code and the IP:port shown on that same screen.
 */
object PairingOverlay {

    private var window: View? = null
    private val main = Handler(Looper.getMainLooper())

    fun canDraw(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun hide() {
        val run = {
            val view = window
            window = null
            if (view != null) {
                val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                runCatching { wm.removeViewImmediate(view) }
                runCatching { wm.removeView(view) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post(run)
    }

    @SuppressLint("SetTextI18n")
    fun show(context: Context, onPair: (code: String, host: String, port: String) -> Unit) {
        if (window != null) return
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = app.resources.displayMetrics.density

        fun dip(v: Int) = (v * dp).toInt()

        val card = GradientDrawable().apply {
            setColor(0xF0161B24.toInt())
            cornerRadius = 22 * dp
            setStroke(dip(1), 0xFF2A3344.toInt())
        }

        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(16), dip(14), dip(16), dip(14))
            background = card
            elevation = 24f
        }

        val title = TextView(app).apply {
            text = "GlassOS — wpisz tu, nie przełączaj okna"
            setTextColor(0xFF5EE0F7.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val hint = TextView(app).apply {
            text = "Zostaw okno parowania otwarte. Przepis 6 cyfr i linię IP:port z tego okna."
            setTextColor(0xFF93A0B4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dip(4), 0, dip(8))
        }
        val code = field(app, "6-cyfrowy kod").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val endpoint = field(app, "IP:port  np. 192.168.1.20:37123").apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val status = TextView(app).apply {
            text = ""
            setTextColor(0xFF5EE0F7.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dip(6), 0, 0)
        }
        val pair = Button(app).apply {
            text = "Sparuj"
            setBackgroundColor(0xFF5EE0F7.toInt())
            setTextColor(0xFF00333C.toInt())
            setOnClickListener {
                val digits = code.text.toString().filter(Char::isDigit).take(6)
                if (digits.length != 6) {
                    status.text = "Kod musi mieć 6 cyfr"
                    return@setOnClickListener
                }
                val parsed = parseEndpoint(endpoint.text.toString())
                val host = parsed?.first.orEmpty()
                val port = parsed?.second?.toString().orEmpty()
                isEnabled = false
                text = "Paruję…"
                status.text = "Nie zamykaj okna Androida…"
                onPair(digits, host, port)
            }
        }

        root.addView(title)
        root.addView(hint)
        root.addView(code, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(48)))
        root.addView(endpoint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(48)).apply {
            topMargin = dip(8)
        })
        root.addView(pair, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(48)).apply {
            topMargin = dip(10)
        })
        root.addView(status)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val close = Button(app).apply {
            text = "ZAMKNIJ TEN PASEK"
            setBackgroundColor(0xFFFF8A80.toInt())
            setTextColor(0xFF1A0000.toInt())
            setOnClickListener { hide() }
        }
        root.addView(close, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(48)).apply {
            bottomMargin = dip(10)
        })

        val params = WindowManager.LayoutParams(
            dip(340),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dip(24)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        runCatching {
            wm.addView(root, params)
            window = root
        }
    }

    fun showPairingResult(ok: Boolean, message: String) {
        main.post {
            val root = window as? LinearLayout ?: return@post
            val status = root.getChildAt(root.childCount - 1) as? TextView
            val pair = root.getChildAt(root.childCount - 2) as? Button
            status?.text = message
            status?.setTextColor(if (ok) 0xFF7CFFB2.toInt() else 0xFFFF8A80.toInt())
            pair?.isEnabled = true
            pair?.text = "Sparuj"
            if (ok) main.postDelayed({ hide() }, 900)
        }
    }

    fun parseEndpoint(raw: String): Pair<String, Int>? {
        val t = raw.trim().replace(" ", "")
        val idx = t.lastIndexOf(':')
        if (idx <= 0) return null
        val host = t.substring(0, idx)
        val port = t.substring(idx + 1).toIntOrNull() ?: return null
        if (host.isBlank() || port <= 0) return null
        return host to port
    }

    private fun field(ctx: Context, hint: String): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(0xFF93A0B4.toInt())
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF1E2533.toInt())
        setPadding(36, 24, 36, 24)
        setSingleLine(true)
    }
}
