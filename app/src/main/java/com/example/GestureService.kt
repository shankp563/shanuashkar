package com.example

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.*
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class GestureService : Service(), SensorEventListener {

    private var windowManager: WindowManager? = null
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var proximitySensor: Sensor? = null

    // Touch-to-wake black overlay view
    private var wakeOverlayView: View? = null

    // Config defaults
    private var isShakeEnabled = false
    private var isWakeEnabled = false
    private var sensitivity = 14.0f
    private var lastShakeTime: Long = 0
    private var isFlashlightOn = false

    // Proximity state
    private var isNear = false

    companion object {
        const val ACTION_RELOAD = "com.example.gestures.RELOAD"
        const val ACTION_TRIGGER_SLEEP_WINDOW = "com.example.gestures.SLEEP"
        private const val CHANNEL_ID = "gesture_service_channel"
        private const val NOTIFICATION_ID = 4005
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        createNotificationChannel()
        showNotification()
        loadSettingsFromDb()
    }

    private fun loadSettingsFromDb() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(this@GestureService)
            val settings = db.settingsDao.getSettingsFlow().firstOrNull() ?: db.settingsDao.getSettings()
            settings?.let {
                isShakeEnabled = it.isShakeFlashlightEnabled
                isWakeEnabled = it.isDoubleTapWakeEnabled
                sensitivity = it.shakeSensitivity
                
                // Switch sensor listeners based on settings
                if (isShakeEnabled) {
                    sensorManager?.registerListener(this@GestureService, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
                } else {
                    sensorManager?.unregisterListener(this@GestureService, accelerometer)
                }

                if (isWakeEnabled) {
                    sensorManager?.registerListener(this@GestureService, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
                } else {
                    sensorManager?.unregisterListener(this@GestureService, proximitySensor)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_RELOAD) {
            loadSettingsFromDb()
        } else if (action == ACTION_TRIGGER_SLEEP_WINDOW) {
            // Invokes black OLED lock screen to allow Double-Tap wake
            showOledSleepOverlay()
        }
        return START_STICKY
    }

    // Displays solid black low-power screen overlay mimicking lock state
    private fun showOledSleepOverlay() {
        if (!isWakeEnabled || isNear) return // Ignore if face down or proximity blocked
        if (wakeOverlayView != null) return

        wakeOverlayView = View(this).apply {
            setBackgroundColor(Color.BLACK)
            
            // Set double click detector
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    dismissOledSleepOverlay()
                    return true
                }
            })

            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        )
        
        // Ensure standard Android screen can dim/prevent deep sleeping while overlay is active
        params.screenBrightness = 0.01f // Dim panel light completely (AMOLED black)

        try {
            windowManager?.addView(wakeOverlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dismissOledSleepOverlay() {
        wakeOverlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            wakeOverlayView = null
        }
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        dismissOledSleepOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // SensorEventListener overrides
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && isShakeEnabled) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            if (gForce > (sensitivity / 9.8f)) {
                val currentTime = SystemClock.elapsedRealtime()
                // Throttle shakes with 600ms latency to prevent rapid flashing
                if (currentTime - lastShakeTime > 600) {
                    lastShakeTime = currentTime
                    toggleFlashlight()
                }
            }
        }

        if (event.sensor.type == Sensor.TYPE_PROXIMITY && isWakeEnabled) {
            val distance = event.values[0]
            val maxRange = event.sensor.maximumRange
            isNear = distance < maxRange && distance < 3f
            
            // If in pocket, immediately remove touch sensing sleep screen to save battery
            if (isNear) {
                dismissOledSleepOverlay()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun toggleFlashlight() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            isFlashlightOn = !isFlashlightOn
            cameraManager.setTorchMode(cameraId, isFlashlightOn)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Aura Customizer Gestures",
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
            .setContentTitle("Utility Gestures Active")
            .setContentText("Shake for flashlight and double-tap gestures are active.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
