package fan.superai.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import fan.superai.data.AppSettings
import fan.superai.engine.EngineState
import kotlin.math.abs
import kotlin.math.min

/**
 * Overlay kartı (v9.6 ile aynı mavi kart, aynı buton sırası):
 *   🎯 FAN   2/3  %34
 *   ⭐ Yan   T•K  %61
 *   [uzun bas → detay: Kotlin / Python / Hakem / en iyi 3 üye]
 *   2 3 1 4 2 3
 *   [DEL][4][3][2][1]
 *
 * DÜĞME GÜVENİLİRLİĞİ
 *  - Kart hiçbir zaman ekrandan geniş olamaz ([onMeasure] ekran genişliğiyle sınırlar),
 *    yatay görünümde tahmin satırı daralabilir; böylece sayı düğmeleri her zaman
 *    ekranın içinde ve basılabilir kalır.
 *  - Düğmeler daha yüksek dokunma alanına ve basılı görünümüne sahiptir.
 *  - [update] çağrısına verilen `echo` listesi, düğmeye basıldığı anda son sayılar
 *    satırında görünür; motor hesabı bitince gerçek değerle değiştirilir.
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
    private fun screenWidth() = resources.displayMetrics.widthPixels.coerceAtLeast(dp(120))

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
    private var recentEmpty = "- - - - - -"
    private var recentJoin = " "
    var detailOpen = false
        private set

    private val labelColor = Color.parseColor("#BBDEFB")
    private val confColor = Color.parseColor("#FFB74D")

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(context).apply {
        this.text = text; textSize = size * sc; setTextColor(color)
        typeface = if (mono) Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** Tek satır + sonu kırpma: dar ekranda taşmayı önler. */
    private fun TextView.oneLine() = apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
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

    /**
     * Kartı ekran genişliğiyle sınırlar. FLAG_LAYOUT_NO_LIMITS ile pencere ekran
     * dışına taşabildiği için bu sınır olmazsa yatay kartta sağdaki düğmeler
     * ekran dışında kalıyordu (düğme "bozuldu" hissinin ana nedeni).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = screenWidth()
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (mode == MeasureSpec.UNSPECIFIED || size == 0 || size > max)
            MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST) else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }

    private fun row(label: String, main: TextView, conf: TextView): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(tv(label, 11f, labelColor).apply { minWidth = dp((52 * sc).toInt()) })
        addView(main, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        addView(conf.apply { gravity = Gravity.END; minWidth = dp((32 * sc).toInt()) })
        setOnTouchListener(dragListener)
    }

    /**
     * Veri girişi düğmesi. [minH] dokunma alanını büyütür; basılıyken rengi koyulaşır
     * (dokunma geri bildirimi), titreşim açıksa hafif titreşim verir.
     */
    private fun button(text: String, color: String, size: Float, minH: Int, action: () -> Unit) = TextView(context).apply {
        this.text = text; textSize = size * sc; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setColor(Color.parseColor(color)) }
        setPadding(dp(2), dp(4), dp(2), dp(4))
        minimumHeight = minH
        minHeight = minH
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (s.vibrate) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.alpha = 0.55f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            false   // tıklama yine düğmenin kendi onTouchEvent'inde işlenir
        }
    }

    private fun buttons(compact: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        val sz = if (compact) 12f else 14f
        val minH = dp(if (compact) (30 * sc).toInt() else (38 * sc).toInt())
        // Dar ekranda düğme satırı ekranın en fazla %45'ini kaplar → hepsi görünür kalır.
        val room = (screenWidth() * 0.45f).toInt() - 4 * dp(4)
        val bw = if (compact) min(dp((28 * sc).toInt()), maxOf(dp(18), room / 5)) else 0
        val items = listOf(
            button(if (compact) "⌫" else "DEL", "#78909C", if (compact) 11f else 10f, minH) { onDelete() },
            button("4", "#F44336", sz, minH) { onNumber(4) },
            button("3", "#FF9800", sz, minH) { onNumber(3) },
            button("2", "#2196F3", sz, minH) { onNumber(2) },
            button("1", "#4CAF50", sz, minH) { onNumber(1) }
        )
        items.forEachIndexed { i, b ->
            addView(b, LayoutParams(if (compact) bw else 0, LayoutParams.WRAP_CONTENT, if (compact) 0f else 1f).apply {
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
            tvRecent = tv(recentEmpty, 9f, labelColor, mono = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0); setOnTouchListener(dragListener) }
            addView(tvRecent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(buttons(false), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    private fun buildHorizontal() {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(5), dp(6), dp(5))
        recentEmpty = "- - - -"
        recentJoin = ""                     // dar satırda sayılar bitişik: "2314"
        tvMain = tv("--", 15f, Color.WHITE, bold = true, mono = true).oneLine()
        tvMainC = tv("--", 10f, confColor, mono = true).oneLine()
        tvSide = tv("--", 14f, Color.parseColor("#E1BEE7"), bold = true, mono = true).oneLine()
        tvSideC = tv("--", 10f, confColor, mono = true).oneLine()
        val sep = { tv(" │ ", 12f, Color.parseColor("#90CAF9")) }
        val gap = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(3) }
        val info = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = true             // dar ekranda taşan kısım kırpılır, düğmelerin üstüne çizilmez
            setOnTouchListener(dragListener)
            addView(tv("FAN ", 11f, labelColor))
            addView(tvMain)
            addView(tvMainC, gap)
            addView(sep())
            addView(tv("Yan ", 11f, labelColor))
            addView(tvSide)
            addView(tvSideC, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(3) })
        }
        // Tahmin satırı daralabilir (ağırlık 1) → sayı düğmeleri her zaman kart içinde kalır.
        addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (s.showRecent) {
            addView(sep())
            tvRecent = tv(recentEmpty, 9f, labelColor, mono = true).apply {
                setOnTouchListener(dragListener); oneLine(); maxWidth = dp((56 * sc).toInt())
            }
            addView(tvRecent)
        }
        addView(buttons(true))
    }

    fun toggleDetail() {
        val d = detail ?: return
        detailOpen = !detailOpen
        d.visibility = if (detailOpen) VISIBLE else GONE
    }

    /**
     * @param echo düğmeye basıldığı anda eklenen, motoru henüz işlemediği sayılar
     *             (anında geri bildirim; motor durumu gelince kendiliğinden düşer).
     */
    @JvmOverloads
    fun update(st: EngineState?, busy: String?, echo: List<Int> = emptyList()) {
        tvBusy?.let { it.visibility = if (busy != null) VISIBLE else GONE; it.text = busy ?: "" }
        val v = st?.verdict
        if (st == null || v == null) {
            tvMain.text = "--"; tvMainC.text = "--"; tvSide.text = "--"; tvSideC.text = "--"
            tvRecent?.text = if (echo.isEmpty()) recentEmpty else echo.joinToString(recentJoin)
            return
        }
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
        val recent = if (echo.isEmpty()) st.recent else (st.recent + echo).takeLast(6)
        tvRecent?.text = if (recent.isEmpty()) recentEmpty else recent.joinToString(recentJoin)
        fun lab(p: DoubleArray?): String {
            if (p == null) return "--"
            val o = fan.superai.engine.P.order(p)
            return "${o[0] + 1}/${o[1] + 1} %${((p[o[0]] + p[o[1]]) * 100).toInt()}"
        }
        tvK?.text = "🔵 Kotlin  ${lab(st.kotlinProbs)}"
        tvP?.text = "🐍 Python  ${lab(st.pythonProbs)}"
        tvRef?.text = "⚖️ 🔵${(v.weightK * 100).toInt()} · 🐍${(v.weightP * 100).toInt()}"
        val top = (st.kotlinStats + st.pythonStats).filter { it.enabled && !it.benched }.sortedByDescending { it.weight }.take(3)
        tvTop?.text = "🏆 " + top.joinToString(" · ") { it.name.replace(" ", "").take(10) }
    }
}
