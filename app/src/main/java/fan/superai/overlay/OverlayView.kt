package fan.superai.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import fan.superai.data.AppSettings
import fan.superai.engine.EngineState
import kotlin.math.abs

/**
 * Overlay kartı (v9.6 ile aynı mavi kart, aynı buton sırası):
 *   🎯 FAN   2/3  %34
 *   ⭐ Yan   T•K  %61
 *   [uzun bas → detay: Kotlin / Python / Hakem / en iyi 3 üye]
 *   2 3 1 4 2 3
 *   [DEL][4][3][2][1]
 */
@SuppressLint("ViewConstructor", "SetTextI18n", "ClickableViewAccessibility")
class OverlayView(
    ctx: Context,
    private val s: AppSettings,
    private val onNumber: (Int) -> Unit,
    private val onDelete: () -> Unit,
    private val onDrag: (dx: Int, dy: Int) -> Unit
) : LinearLayout(ctx) {

    private val sc = s.overlayTextScale
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var tvMain: TextView
    private lateinit var tvMainC: TextView
    private lateinit var tvSide: TextView
    private lateinit var tvSideC: TextView
    private var tvK: TextView? = null
    private var tvP: TextView? = null
    private var tvRef: TextView? = null
    private var tvTop: TextView? = null
    private var detail: LinearLayout? = null
    private var tvRecent: TextView? = null
    private var tvBusy: TextView? = null
    var detailOpen = false
        private set

    private val labelColor = Color.parseColor("#BBDEFB")
    private val confColor = Color.parseColor("#FFB74D")

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(context).apply {
        this.text = text; textSize = size * sc; setTextColor(color)
        typeface = if (mono) Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private val gesture = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) { if (s.overlayDetail) toggleDetail() }
    })
    private var lx = 0f; private var ly = 0f
    private val dragListener = OnTouchListener { _, e ->
        gesture.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lx = e.rawX; ly = e.rawY }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - lx).toInt(); val dy = (e.rawY - ly).toInt()
                if (abs(dx) > 0 || abs(dy) > 0) { onDrag(dx, dy); lx = e.rawX; ly = e.rawY }
            }
        }
        true
    }

    // Kotlin initializes properties and init blocks in source order. The gesture
    // detector and listener must exist before building any of the touch targets.
    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb((s.overlayAlpha * 255).toInt(), 0x15, 0x65, 0xC0))
        }
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnTouchListener(dragListener)
        if (s.overlayHorizontal) buildHorizontal() else buildVertical()
    }

    private fun row(label: String, main: TextView, conf: TextView): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(tv(label, 11f, labelColor).apply { minWidth = dp((52 * sc).toInt()) })
        addView(main, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        addView(conf.apply { gravity = Gravity.END; minWidth = dp((32 * sc).toInt()) })
        setOnTouchListener(dragListener)
    }

    private fun button(text: String, color: String, size: Float, action: () -> Unit) = TextView(context).apply {
        this.text = text; textSize = size * sc; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setColor(Color.parseColor(color)) }
        setPadding(0, dp(5), 0, dp(5))
        setOnClickListener {
            if (s.vibrate) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
    }

    private fun buttons(compact: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        val sz = if (compact) 12f else 14f
        val items = listOf(
            button(if (compact) "⌫" else "DEL", "#78909C", if (compact) 11f else 10f) { onDelete() },
            button("4", "#F44336", sz) { onNumber(4) },
            button("3", "#FF9800", sz) { onNumber(3) },
            button("2", "#2196F3", sz) { onNumber(2) },
            button("1", "#4CAF50", sz) { onNumber(1) }
        )
        items.forEachIndexed { i, b ->
            addView(b, LayoutParams(if (compact) dp((28 * sc).toInt()) else 0, LayoutParams.WRAP_CONTENT, if (compact) 0f else 1f).apply {
                if (i > 0) leftMargin = dp(4)
            })
        }
    }

    private fun buildVertical() {
        minimumWidth = dp((170 * sc).toInt())
        tvMain = tv("--", 17f, Color.WHITE, bold = true, mono = true)
        tvMainC = tv("--", 10f, confColor, mono = true)
        tvSide = tv("--", 17f, Color.parseColor("#E1BEE7"), bold = true, mono = true)
        tvSideC = tv("--", 10f, confColor, mono = true)
        addView(row("🎯 FAN", tvMain, tvMainC))
        addView(row("⭐ Yan", tvSide, tvSideC))
        // Detay paneli
        val d = LinearLayout(context).apply {
            orientation = VERTICAL; visibility = GONE; setPadding(0, dp(4), 0, 0)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(0, 0) }
            setOnTouchListener(dragListener)
        }
        d.addView(View(context).apply { setBackgroundColor(Color.parseColor("#6690CAF9")) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        tvK = tv("🔵 Kotlin  --", 11f, Color.WHITE, mono = true)
        tvP = tv("🐍 Python  --", 11f, Color.WHITE, mono = true)
        tvRef = tv("⚖️ Hakem   --", 10f, labelColor, mono = true)
        tvTop = tv("🏆 --", 9f, labelColor, mono = true).apply { gravity = Gravity.CENTER }
        listOf(tvK, tvP, tvRef, tvTop).forEach { d.addView(it) }
        detail = d
        addView(d)
        tvBusy = tv("", 9f, confColor).apply { gravity = Gravity.CENTER; visibility = GONE }
        addView(tvBusy, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (s.showRecent) {
            tvRecent = tv("- - - - - -", 9f, labelColor, mono = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0); setOnTouchListener(dragListener) }
            addView(tvRecent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(buttons(false), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    private fun buildHorizontal() {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        tvMain = tv("--", 15f, Color.WHITE, bold = true, mono = true)
        tvMainC = tv("--", 10f, confColor, mono = true)
        tvSide = tv("--", 14f, Color.parseColor("#E1BEE7"), bold = true, mono = true)
        tvSideC = tv("--", 10f, confColor, mono = true)
        val sep = { tv(" │ ", 12f, Color.parseColor("#90CAF9")) }
        val info = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setOnTouchListener(dragListener)
            addView(tv("FAN ", 11f, labelColor)); addView(tvMain); addView(tv(" ", 10f, 0)); addView(tvMainC)
            addView(sep()); addView(tv("Yan ", 11f, labelColor)); addView(tvSide); addView(tv(" ", 10f, 0)); addView(tvSideC)
        }
        addView(info)
        if (s.showRecent) {
            addView(sep())
            tvRecent = tv("- - - -", 9f, labelColor, mono = true).apply { setOnTouchListener(dragListener) }
            addView(tvRecent)
        }
        addView(tv("  ", 10f, 0))
        addView(buttons(true))
    }

    fun toggleDetail() {
        val d = detail ?: return
        detailOpen = !detailOpen
        d.visibility = if (detailOpen) VISIBLE else GONE
    }

    fun update(st: EngineState?, busy: String?) {
        tvBusy?.let { it.visibility = if (busy != null) VISIBLE else GONE; it.text = busy ?: "" }
        val v = st?.verdict
        if (st == null || v == null) { tvMain.text = "--"; tvMainC.text = "--"; tvSide.text = "--"; tvSideC.text = "--"; return }
        if (st.learning) {
            tvMain.text = "…"; tvMainC.text = "${st.count}/50"
        } else {
            tvMain.text = v.label
            tvMain.setTextColor(if (v.secondary == null) Color.parseColor("#A5D6A7") else Color.WHITE)
            tvMainC.text = "%${(v.confidence * 100).toInt()}"
        }
        val hit = st.lastSideHit == true
        tvSide.text = v.sideLabel + if (hit) "=" else ""
        tvSide.setTextColor(if (hit) Color.parseColor("#81C784") else Color.parseColor("#E1BEE7"))
        tvSideC.text = "%${(v.sideConfidence * 100).toInt()}"
        tvRecent?.text = if (st.recent.isEmpty()) "- - - - - -" else st.recent.joinToString(" ")
        fun lab(p: DoubleArray?): String {
            if (p == null) return "--"
            val o = fan.superai.engine.P.order(p)
            return "${o[0] + 1}/${o[1] + 1} %${((p[o[0]] + p[o[1]]) * 100).toInt()}"
        }
        tvK?.text = "🔵 Kotlin  ${lab(st.kotlinProbs)}"
        tvP?.text = "🐍 Python  ${lab(st.pythonProbs)}"
        tvRef?.text = "⚖️ 🔵${(v.weightK * 100).toInt()} · 🐍${(v.weightP * 100).toInt()}"
        val top = (st.kotlinStats + st.pythonStats).filter { it.enabled && !it.benched }.sortedByDescending { it.weight }.take(3)
        tvTop?.text = "🏆 " + top.joinToString(" · ") { it.name.replace(" ", "") .take(10) }
    }
}
