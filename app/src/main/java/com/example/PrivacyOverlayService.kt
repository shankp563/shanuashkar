package com.example

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class PrivacyOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: PrivacyFilterView? = null
    private var opacity: Float = 0.4f
    private var patternIndex: Int = 1

    companion object {
        const val ACTION_START = "com.example.privacy.START"
        const val ACTION_STOP = "com.example.privacy.STOP"
        const val EXTRA_OPACITY = "opacity"
        const val EXTRA_PATTERN = "pattern"
        private const val CHANNEL_ID = "privacy_filter_channel"
        private const val NOTIFICATION_ID = 4004
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            stopOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        opacity = intent?.getFloatExtra(EXTRA_OPACITY, opacity) ?: opacity
        patternIndex = intent?.getIntExtra(EXTRA_PATTERN, patternIndex) ?: patternIndex

        showNotification()
        startOrUpdateOverlay()

        return START_STICKY
    }

    private fun startOrUpdateOverlay() {
        if (overlayView == null) {
            overlayView = PrivacyFilterView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            try {
                windowManager?.addView(overlayView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            overlayView?.updateParams(opacity, patternIndex)
        }
    }

    private fun stopOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
        }
    }

    override fun onDestroy() {
        stopOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Privacy Display Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun showNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Privacy Shield Active")
            .setContentText("The viewport dimmer and anti-peeping pattern is running.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // Custom view that draws semi-transparent colors and patterns over screens
    inner class PrivacyFilterView(context: Context) : View(context) {
        private var viewOpacity: Float = 0.4f
        private var viewPatternIndex: Int = 1
        private val paint = Paint().apply {
            isAntiAlias = true
            strokeWidth = 3f
        }

        fun updateParams(newOpacity: Float, newPattern: Int) {
            viewOpacity = newOpacity
            viewPatternIndex = newPattern
            invalidate()
        }

        init {
            updateParams(opacity, patternIndex)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw Dim Layer
            val alphaInt = (viewOpacity * 255).toInt().coerceIn(0, 255)
            
            if (viewPatternIndex == 2) {
                // Amber Warming Tint
                canvas.drawColor(Color.argb(alphaInt, 240, 160, 4))
            } else {
                // Standard Black Tint
                canvas.drawColor(Color.argb(alphaInt, 0, 0, 0))
            }

            paint.color = Color.argb((viewOpacity * 100).toInt().coerceIn(0, 100), 12, 12, 12)

            when (viewPatternIndex) {
                1 -> {
                    // Fine Grid: draw grids
                    val gridXSpacing = 32f
                    val gridYSpacing = 32f

                    var x = 0f
                    while (x < width) {
                        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
                        x += gridXSpacing
                    }

                    var y = 0f
                    while (y < height) {
                        canvas.drawLine(0f, y, width.toFloat(), y, paint)
                        y += gridYSpacing
                    }
                }
                3 -> {
                    // Diagonal Microstripes
                    val spacing = 20f
                    var offset = -height.toFloat()
                    while (offset < width) {
                        // Drawing line from (offset, 0) to (offset + height, height)
                        canvas.drawLine(offset, 0f, offset + height, height.toFloat(), paint)
                        offset += spacing
                    }
                }
                else -> {
                    // 0: None - No Pattern
                }
            }
        }
    }
}
