package com.takano.rcues

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 防晕车 App 的交互（极简，无菜单）。
 *
 * 用户要求：菜单全删；点击循环「轻 → 中 → 重」；亮度跟档位统合（一起变）。
 *
 *  · 单击（触控板轻点 83 / ENTER 66）= 换下一档
 *  · 滑动（方向键 / 广播）= 前滑下一档、后滑上一档（能收到就用，收不到不影响）
 *  · 长按（PROG_BLUE）= 暂停 / 继续
 *  · 双击（BACK）= 退出，需 1.5 秒内连按两次（防误触）
 *
 * 误触抑制：点击按"上一个事件"计时，900ms 内的一串事件只算一次动作
 * （实测一次轻点会出两个事件，手一碰也会连出好几个）。
 */
class RcuesApp : Application() {

    companion object {
        private const val TAG = "RcuesApp"
        private const val PREFS = "rcues"

        const val ACTION_SWIPE_FORWARD = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
        const val ACTION_SWIPE_BACK = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"

        /** 误触抑制。 */
        private const val TAP_DEDUPE_MS = 900L
        private const val SWIPE_COOLDOWN_MS = 700L
        private const val EXIT_CONFIRM_MS = 1500L

        @Volatile var view: MotionCuesView? = null

        /** 档位变化回调（Activity 用来设置窗口亮度）。 */
        @Volatile var onSeverityChanged: ((MotionCuesView.Severity) -> Unit)? = null

        private lateinit var appCtx: Context
        private var lastSeverity = 1
        private var lastTapEventAt = 0L
        private var lastMoveAt = 0L
        private var lastBackAt = 0L

        fun load(context: Context) {
            val p = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            lastSeverity = p.getInt("severity", 1)
                .coerceIn(0, MotionCuesView.Severity.values().size - 1)
        }

        fun applySaved(v: MotionCuesView) {
            v.severity = MotionCuesView.Severity.values()[lastSeverity]
        }

        /** 设定档位（会一起改光带宽度和窗口亮度）。 */
        fun setSeverity(context: Context, idx: Int, toast: Boolean = true) {
            val all = MotionCuesView.Severity.values()
            val i = idx.coerceIn(0, all.size - 1)
            lastSeverity = i
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("severity", i).apply()
            val s = all[i]
            view?.severity = s
            val name = appCtx.getString(s.labelRes)
            if (toast) view?.showToast(
                if (s.maxBrightness > s.minBrightness) {
                    appCtx.getString(R.string.toast_level, name)
                } else {
                    appCtx.getString(R.string.toast_level_plain, name)
                }
            )
            onSeverityChanged?.invoke(s)
            Log.i(TAG, "level=${name}（波长 ${s.waveLenDp}dp / 锐度 ${s.sharpness} / 亮度 ${s.minBrightness}→${s.maxBrightness}）")
        }

        /** 循环换档：dir=+1 下一档，-1 上一档。 */
        fun cycleSeverity(dir: Int) {
            val n = MotionCuesView.Severity.values().size
            setSeverity(appCtx, ((lastSeverity + dir) % n + n) % n)
        }

        /** 单击：换下一档。 */
        fun onTap() {
            val now = SystemClock.elapsedRealtime()
            val sinceEvent = if (lastTapEventAt == 0L) Long.MAX_VALUE else now - lastTapEventAt
            lastTapEventAt = now
            if (sinceEvent < TAP_DEDUPE_MS) {
                Log.i(TAG, "误触：点击太密，忽略（距上个事件 ${sinceEvent}ms）")
                return
            }
            cycleSeverity(1)
        }

        /** 滑动：前滑下一档，后滑上一档。 */
        fun onSwipe(dir: Int) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastMoveAt < SWIPE_COOLDOWN_MS) {
                Log.i(TAG, "误触：滑动太快，忽略（${now - lastMoveAt}ms）")
                return
            }
            lastMoveAt = now
            cycleSeverity(dir)
        }

        /** 双击/返回键。返回 true = 已处理（不要退出）。 */
        fun onBack(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackAt < EXIT_CONFIRM_MS) {
                Log.i(TAG, "确认退出")
                return false
            }
            lastBackAt = now
            view?.showToast(appCtx.getString(R.string.toast_exit_hint))
            Log.i(TAG, "退出确认中（1.5 秒内再按一次）")
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        appCtx = applicationContext
        load(this)
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            addAction(ACTION_SWIPE_FORWARD)
            addAction(ACTION_SWIPE_BACK)
        }
        ContextCompat.registerReceiver(this, swipeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        Log.i(TAG, "已注册双指滑动广播接收器")
    }

    private val swipeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SWIPE_FORWARD -> {
                    onSwipe(1)
                    runCatching { abortBroadcast() }
                }
                ACTION_SWIPE_BACK -> {
                    onSwipe(-1)
                    runCatching { abortBroadcast() }
                }
            }
        }
    }
}
