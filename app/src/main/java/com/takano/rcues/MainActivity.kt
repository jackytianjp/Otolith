package com.takano.rcues

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Rcues —— 防晕车「运动视觉参照」（跟 iPhone 车辆运动提示同类）。
 *
 * 屏幕上一圈小亮点，随眼镜感受到的加减速平滑滑动：坐车时给眼睛一个跟内耳一致的参照，
 * 减少「看到的静止 vs 身体在动」的冲突。黑底 = 不发光、不挡视线。
 *
 * 操作：触控板单击 = 复位/暂停，双击 = 退出。
 */
class MainActivity : Activity(), SensorEventListener {

    companion object {
        private const val TAG = "Rcues"
    }

    private lateinit var view: MotionCuesView
    private lateinit var sensors: SensorManager
    private var linear: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastStep = 0L
    private var lastWidthStep = -1
    private var brightOverride: Float? = null
    private var lastLog = 0L
    private var demo = false
    private var widthDemo = false

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (widthDemo) {
                // 宽度对比演示：恒定加速度 → 光带稳定流动；每 7 秒自动换一档（轻/中/重）
                val cyc = ((now / 1000.0) % 21.0)
                if ((cyc / 7.0).toInt() != lastWidthStep) {
                    lastWidthStep = (cyc / 7.0).toInt()
                    RcuesApp.setSeverity(this@MainActivity, (lastWidthStep + 1) % 3, toast = false)
                }
                view.demoLabel = null
                view.onAccel(0f, 0f, -2.2f)
            }
            if (demo) {
                // 演示：按"车"的时间线走一遍（加速 → 匀速 → 刹车 → 停车 → 左转 → 右转 → 直行）
                // 注意轴映射：前进 = -Z，向右 = +X，所以这里喂 x=横向、z=-纵向
                val cyc = ((now / 1000.0) % 22.0)
                var lat = 0.0
                var lon = 0.0
                val text: String
                when {
                    cyc < 4.0 -> { lon = 2.6; text = getString(R.string.demo_accel) }
                    cyc < 7.0 -> { text = getString(R.string.demo_cruise) }
                    cyc < 10.5 -> { lon = -3.2; text = getString(R.string.demo_brake) }
                    cyc < 13.0 -> { text = getString(R.string.demo_stopped) }
                    cyc < 16.0 -> { lat = -2.2; text = getString(R.string.demo_left) }
                    cyc < 19.0 -> { lat = 2.2; text = getString(R.string.demo_right) }
                    else -> { text = getString(R.string.demo_straight) }
                }
                view.demoLabel = text
                view.onAccel(lat.toFloat(), 0f, (-lon).toFloat())
            }
            view.step(now - lastStep)
            syncBrightness()
            lastStep = now
            if (now - lastLog > 2000) {
                lastLog = now
                Log.i(TAG, "sensor ${view.snapshot()}")
            }
            handler.postDelayed(this, 16)   // ~60Hz 重绘
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = MotionCuesView(this)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        setContentView(root)

        // 眼镜 HUD 静止几秒就会自动熄屏；防晕车要一直显示
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        demo = intent?.getBooleanExtra("demo", false) == true
        widthDemo = intent?.getBooleanExtra("widthDemo", false) == true
        if (intent?.hasExtra("bright") == true) {
            brightOverride = intent.getFloatExtra("bright", -1f).takeIf { it > 0f }
        }
        intent?.let { if (it.hasExtra("severity")) {
            val idx = it.getIntExtra("severity", 1).coerceIn(0, 2)
            RcuesApp.setSeverity(this, idx, toast = false)
        } }
        if (demo) Log.i(TAG, "演示模式：用合成加速度驱动（不用真传感器）")

        // 启动安全提示：本 App 只给乘员用。驾驶时使用流动光带会分散注意力。
        if (!widthDemo) {
            view.showSafetyNotice(getString(R.string.safety_notice))
            Log.i(TAG, "安全提示：仅限乘客使用，驾驶时请勿使用")
        }
        linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (linear == null) {
            Log.w(TAG, "没有线性加速度传感器，退回加速度计 + 重力滤波")
        }
    }

    /**
     * 强制窗口亮度：WindowManager.LayoutParams.screenBrightness 是**按窗口**覆盖系统亮度的，
     * 不需要任何权限，也不会改系统亮度设置（退出 App 后系统亮度照旧）。
     * 注意：只在本窗口可见时生效。
     */
    private var lastAppliedBrightness = -1f

    /** 每帧把动态亮度同步到窗口（变化超过 2% 才写，避免每帧都设置属性）。 */
    private fun syncBrightness() {
        val want = view.dynamicBrightness
        if (kotlin.math.abs(want - lastAppliedBrightness) < 0.02f) return
        lastAppliedBrightness = want
        applyBrightness(want)
    }

    private fun applyBrightness(b: Float? = null) {
        val v = b ?: brightOverride ?: view.dynamicBrightness
        window.attributes = window.attributes.apply {
            screenBrightness = v.coerceIn(0.02f, 1f)
        }
        android.util.Log.i("Rcues", "窗口亮度 = $v（强制覆盖，不受系统亮度控制）")
    }

    override fun onResume() {
        super.onResume()
        applyBrightness()
        RcuesApp.view = view
        RcuesApp.applySaved(view)
        applyBrightness()
        RcuesApp.onSeverityChanged = { _ -> lastAppliedBrightness = -1f; syncBrightness() }
        lastStep = SystemClock.elapsedRealtime()
        handler.post(ticker)
        val s = linear ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        s?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        Log.i(TAG, "注册传感器: ${s?.name} (${s?.type})")
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        sensors.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            view.onAccel(event.values[0], event.values[1], event.values[2])
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // 没线性加速度时：用 1g 低通估重力再减掉（简单近似）
            gx += (event.values[0] - gx) * 0.02f
            gy += (event.values[1] - gy) * 0.02f
            gz += (event.values[2] - gz) * 0.02f
            view.onAccel(event.values[0] - gx, event.values[1] - gy, event.values[2] - gz)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private var gx = 0f
    private var gy = 0f
    private var gz = 0f

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            Log.i(TAG, "key: ${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})")
            when (event.keyCode) {
                // 单击（触控板轻点 = NOTIFICATION，ENTER 也当单击）= 换下一档
                KeyEvent.KEYCODE_NOTIFICATION, KeyEvent.KEYCODE_ENTER -> {
                    RcuesApp.onTap()
                    return true
                }
                // 滑动（这台固件发的是方向键）：前滑下一档、后滑上一档
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    RcuesApp.onSwipe(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    RcuesApp.onSwipe(-1)
                    return true
                }
                // 长按 = 暂停/继续
                KeyEvent.KEYCODE_PROG_BLUE -> {
                    view.togglePause()
                    Log.i(TAG, "暂停=${view.isPaused()}")
                    return true
                }
                // 双击 = 退出（需连按两次）
                KeyEvent.KEYCODE_BACK -> {
                    if (RcuesApp.onBack()) return true
                    finishAffinity()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
