package com.paa.assistant.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.paa.assistant.R
import com.paa.assistant.ui.overlay.VoiceOverlayActivity

/**
 * FloatingPillService — provides an always-accessible, draggable floating mic bubble.
 *
 * Uses WindowManager TYPE_APPLICATION_OVERLAY to dock to the screen edge.
 * Tapping activates PAA VoiceOverlayActivity instantly without switching active apps.
 * Dragging allows positioning anywhere on the screen edge.
 */
class FloatingPillService : Service() {

    companion object {
        var isRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        isRunning = true
        startForegroundServiceNotification()
        setupFloatingBubble()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "paa_floating_pill"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "PAA Floating Bubble", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PAA Assistant Active")
            .setContentText("Tap the floating bubble to speak")
            .build()

        startForeground(1001, notification)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()

        val frame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            setBackgroundResource(android.R.drawable.dialog_holo_dark_frame)
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            val padding = (12 * density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        frame.addView(icon)

        // Drag & click listener
        frame.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false
                        }
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(frame, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            val intent = Intent(this@FloatingPillService, VoiceOverlayActivity::class.java).apply {
                                putExtra("auto_listen", true)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(intent)
                        }
                        return true
                    }
                }
                return false
            }
        })

        floatingView = frame
        windowManager?.addView(frame, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        floatingView?.let { windowManager?.removeView(it) }
    }
}
