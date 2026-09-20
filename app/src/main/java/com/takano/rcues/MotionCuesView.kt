package com.takano.rcues

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * 防晕车「运动视觉参照」v8 —— 极简版：没有任何菜单。
 *
 * 视觉：屏幕四边各排一条**密集小灯泡**，亮度由**行波**调制（一颗接一颗亮起、又一颗接一颗熄灭），
 * 连起来就是一条流动的光带。方向按车辆受力反着走：
 *   · 左右竖边 ← 纵向加速度（前进/刹车）；上下横边 ← 横向加速度（转弯）
 *   · 转弯时纵向被交叉抑制，避免"拐弯带前进感"
 *
 * 档位（轻/中/重）**同时决定光带宽度和屏幕亮度**，点击循环切换（用户要求：菜单全删、统合）。
 *  · 轻：细而疏 + 35% 亮度
 *  · 中：中等 + 60% 亮度
 *  · 重：宽而密 + 100% 亮度
 *
 * 交互只有三个：单击 = 换档，长按 = 暂停，双击 = 退出（需连按两次防误触）。
 */
class MotionCuesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 症状档位：光带宽度 + 屏幕亮度一起定。 */
    enum class Severity(
        @androidx.annotation.StringRes val labelRes: Int,
        val waveLenDp: Float,
        val sharpness: Float,
        val gain: Float,
        /** 静止时的窗口亮度（0..1，不受系统亮度条控制）。 */
        val minBrightness: Float,
        /** 加速度拉满时的窗口亮度；和 min 相同 = 恒定亮度。 */
        val maxBrightness: Float,
    ) {
        LIGHT(R.string.level_light, 300f, 6.0f, 0.85f, 0.35f, 0.35f),
        MEDIUM(R.string.level_medium, 170f, 3.5f, 1.0f, 0.35f, 1.00f),
        HEAVY(R.string.level_heavy, 130f, 1.5f, 1.15f, 0.35f, 1.00f),
    }

    companion object {
        private const val LP = 0.14f
        private const val GAIN = 55f
        private const val MAXV = 340f
        private const val RESPONSE = 0.30f

        private const val SPACING_DP = 3.2f
        private const val DOT_R_DP = 2.7f

        private const val BREATH_HZ = 0.9f
        private const val BREATH_DEPTH = 0.05f
        private const val ACCEL_BRIGHTNESS = false

        private const val CROSS_K = 0.65f
        private const val CROSS_SOFT = 0.5f

        const val FORWARD_AXIS = -3
        const val RIGHT_AXIS = 1
        const val INVERT = false

        /**
         * 亮度随加速度变化：静止 = minBrightness，加速度到 [BRIGHT_A_REF] m/s² 时到 maxBrightness。
         * 平滑时间常数 [BRIGHT_TAU] 秒 —— 屏幕背光变化太快会闪，所以要拖慢。
         */
        const val BRIGHT_A_REF = 2.5f
        private const val BRIGHT_TAU = 0.45f

        /** 换档提示显示时长（毫秒）。 */
        private const val TOAST_MS = 1500L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#C8FFE0")
    }
    private val uiText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC8FFE0")
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    @Volatile private var rawX = 0f
    @Volatile private var rawY = 0f
    @Volatile private var rawZ = 0f
    @Volatile private var haveData = false

    private var fLat = 0f
    private var fLon = 0f
    private var offH = 0f
    private var offV = 0f
    private var vH = 0f
    private var vV = 0f
    private var t = 0f
    private var paused = false
    private var crossLat = 1f
    private var crossLon = 1f

    /** 当前档位。 */
    @Volatile var severity = Severity.MEDIUM
        set(value) {
            field = value
            dynamicBrightness = value.minBrightness   // 换档先把亮度基准对齐，再随加速度爬
            invalidate()
        }

    /** 演示模式：中央显示阶段文字。 */
    @Volatile var demoLabel: String? = null

    /** 当前应设的窗口亮度（0..1），随加速度平滑变化；Activity 每帧读它去设置窗口亮度。 */
    @Volatile var dynamicBrightness = 0.35f
        private set

    /** 换档提示（几秒后自动消失）。 */
    @Volatile var toast: String? = null
    private var toastUntil = 0L

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** 档位名字（跟着系统语言走）。 */
    fun labelOf(s: Severity): String = context.getString(s.labelRes)

    fun showToast(text: String) {
        toast = text
        toastUntil = android.os.SystemClock.elapsedRealtime() + TOAST_MS
        invalidate()
    }

    fun onAccel(x: Float, y: Float, z: Float) {
        rawX = x; rawY = y; rawZ = z
        haveData = true
    }

    private fun axis(a: Int): Float = when (if (a < 0) -a else a) {
        1 -> rawX
        2 -> rawY
        else -> rawZ
    } * (if (a < 0) -1f else 1f)

    fun recenter() {
        fLat = 0f; fLon = 0f
        offH = 0f; offV = 0f; vH = 0f; vV = 0f
        invalidate()
    }

    fun togglePause() {
        paused = !paused
        if (paused) recenter()
        showToast(context.getString(if (paused) R.string.toast_paused else R.string.toast_resumed))
    }

    fun isPaused() = paused

    fun step(dtMs: Long) {
        val dt = (dtMs / 1000f).coerceIn(0.001f, 0.1f)
        t += dt
        if (paused) {
            invalidate()
            return
        }

        fLat += (axis(RIGHT_AXIS) - fLat) * LP
        fLon += (axis(FORWARD_AXIS) - fLon) * LP

        val domLat = abs(fLat) / (abs(fLat) + abs(fLon) + CROSS_SOFT)
        val domLon = abs(fLon) / (abs(fLat) + abs(fLon) + CROSS_SOFT)
        crossLat = 1f - CROSS_K * domLon
        crossLon = 1f - CROSS_K * domLat

        // 动态亮度：静止 = min，加速度越大越亮（中/重 是 0.35 → 1.0；轻是恒定 0.35）
        val aMag0 = hypot(fLat, fLon)
        val aNorm = (aMag0 / BRIGHT_A_REF).coerceIn(0f, 1f)
        val targetBright = severity.minBrightness +
            (severity.maxBrightness - severity.minBrightness) * aNorm
        val k = 1f - kotlin.math.exp(-dt / BRIGHT_TAU)
        dynamicBrightness += (targetBright - dynamicBrightness) * k

        val sgn = if (INVERT) -1f else 1f
        val g = GAIN * severity.gain
        val wantH = (-sgn * fLat * g * crossLat).coerceIn(-MAXV, MAXV)
        val wantV = (sgn * fLon * g * crossLon).coerceIn(-MAXV, MAXV)

        vH += (wantH - vH) * RESPONSE
        vV += (wantV - vV) * RESPONSE
        offH += vH * dt
        offV += vV * dt

        invalidate()
    }

    fun snapshot(): String =
        if (!haveData) "no-sensor"
        else "x=%.2f y=%.2f z=%.2f | 横流=%.0f 纵流=%.0f 档=%s"
            .format(rawX, rawY, rawZ, vH, vV, labelOf(severity))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val aMag = hypot(fLat, fLon)
        val breath = 1f - BREATH_DEPTH + BREATH_DEPTH * sin(t * 2f * PI.toFloat() * BREATH_HZ)
        val baseAlpha = if (paused) 60f else {
            val boost = if (ACCEL_BRIGHTNESS) (aMag * 35f).coerceAtMost(55f) else 0f
            ((110f + boost) * breath).coerceIn(60f, 220f)
        }

        val inset = dp(22f)
        val spacing = dp(SPACING_DP)
        val dotR = dp(DOT_R_DP)
        val waveLen = dp(severity.waveLenDp)
        val sharp = severity.sharpness
        val l = inset
        val r = width - inset
        val tp = inset
        val b = height - inset

        val alphaH = baseAlpha * (0.55f + 0.45f * crossLat)
        val alphaV = baseAlpha * (0.55f + 0.45f * crossLon)
        drawStrip(canvas, alphaH, l, tp, r, tp, spacing, dotR, waveLen, sharp, offH)
        drawStrip(canvas, alphaH, l, b, r, b, spacing, dotR, waveLen, sharp, offH)
        drawStrip(canvas, alphaV, l, tp, l, b, spacing, dotR, waveLen, sharp, offV)
        drawStrip(canvas, alphaV, r, tp, r, b, spacing, dotR, waveLen, sharp, offV)

        demoLabel?.let {
            uiText.textSize = dp(15f)
            uiText.color = Color.parseColor("#7AFFFFFF")
            canvas.drawText(it, width / 2f, height / 2f, uiText)
        }

        // 换档提示
        val now = android.os.SystemClock.elapsedRealtime()
        if (toast != null && now < toastUntil) {
            uiText.textSize = dp(18f)
            uiText.color = Color.parseColor("#FFC8FFE0")
            canvas.drawText(toast!!, width / 2f, height / 2f + dp(30f), uiText)
        } else if (toast != null) {
            toast = null
        }

        if (!haveData) {
            paint.alpha = 80
            canvas.drawCircle(width / 2f, height / 2f + dp(60f), dp(4f), paint)
        }
    }

    private fun drawStrip(
        canvas: Canvas,
        baseAlpha: Float,
        x1: Float, y1: Float, x2: Float, y2: Float,
        spacing: Float, dotR: Float, waveLen: Float, sharp: Float, offset: Float,
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len <= 0f) return
        val ux = dx / len
        val uy = dy / len
        val count = (len / spacing).toInt().coerceAtLeast(1)

        for (i in 0..count) {
            val s = i * spacing
            val phase = ((s - offset) % waveLen + waveLen) % waveLen / waveLen
            val wave = 0.5f - 0.5f * cos((phase * 2f * PI).toFloat())
            val br = wave.pow(sharp)
            paint.alpha = (baseAlpha * br).toInt().coerceIn(0, 255)
            canvas.drawCircle(x1 + ux * s, y1 + uy * s, dotR * (0.55f + 0.75f * br), paint)
        }
    }
}
