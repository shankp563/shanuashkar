package com.example

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.BatteryManager
import android.util.Log
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("AutomationReceiver", "Received intent action: $action")

        val goAsync = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val settings = db.settingsDao.getSettings()

                // 1. Restart Services on System Boot
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    if (settings?.isShakeFlashlightEnabled == true || settings?.isDoubleTapWakeEnabled == true) {
                        val svcIntent = Intent(context, GestureService::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(svcIntent)
                        } else {
                            context.startService(svcIntent)
                        }
                    }
                    if (settings?.isPrivacyOverlayEnabled == true) {
                        val overlayIntent = Intent(context, PrivacyOverlayService::class.java).apply {
                            putExtra(PrivacyOverlayService.EXTRA_OPACITY, settings.privacyOverlayOpacity)
                            putExtra(PrivacyOverlayService.EXTRA_PATTERN, settings.privacyPatternIndex)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(overlayIntent)
                        } else {
                            context.startService(overlayIntent)
                        }
                    }
                }

                // 2. Smart Automation Triggers
                val automations = db.settingsDao.getAllAutomations()
                if (automations.isNotEmpty() && settings?.isProUnlocked == true) {
                    
                    var batteryPct: Int? = null
                    var currentTimeStr: String? = null

                    if (action == Intent.ACTION_BATTERY_CHANGED) {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        if (level >= 0 && scale > 0) {
                            batteryPct = (level * 100f / scale).toInt()
                        }
                    } else if (action == Intent.ACTION_TIME_TICK) {
                        val currentCalendar = Calendar.getInstance()
                        currentTimeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(currentCalendar.time)
                    }

                    for (auto in automations) {
                        if (!auto.isEnabled) continue

                        var triggerMatched = false
                        if (auto.triggerType == "BATTERY" && batteryPct != null) {
                            val targetPct = auto.triggerValue.toIntOrNull() ?: -1
                            // We trigger when battery hits or drops below the percentage
                            if (batteryPct <= targetPct) {
                                triggerMatched = true
                            }
                        } else if (auto.triggerType == "TIME" && currentTimeStr != null) {
                            if (auto.triggerValue == currentTimeStr) {
                                triggerMatched = true
                            }
                        }

                        if (triggerMatched) {
                            Log.i("AutomationReceiver", "Automation Triggered: ${auto.name}")
                            executeAutomationAction(context, auto.actionType, auto.actionValue)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                goAsync.finish()
            }
        }
    }

    private fun executeAutomationAction(context: Context, actionType: String, actionValue: String) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                // Actions can set wallpapers or toggle the privacy shader overlay
                if (actionType == "WALLPAPER" || actionType == "BOTH") {
                    // Check if value is comma-separated gradient colors
                    val colors = actionValue.split(",")
                    if (colors.size >= 2) {
                        val startColor = Color.parseColor(colors[0].trim())
                        val endColor = Color.parseColor(colors[1].trim())
                        applyGradientWallpaper(context, startColor, endColor)
                    } else {
                        // Single Color
                        val singleColor = Color.parseColor(actionValue.trim())
                        applyGradientWallpaper(context, singleColor, singleColor)
                    }
                }

                if (actionType == "PRIVACY_FILTER" || actionType == "BOTH") {
                    val overlayIntent = Intent(context, PrivacyOverlayService::class.java)
                    if (actionValue.equals("ON", ignoreCase = true)) {
                        // Start Privacy Shading Overlay
                        val db = AppDatabase.getInstance(context)
                        val settings = db.settingsDao.getSettings()
                        overlayIntent.action = PrivacyOverlayService.ACTION_START
                        overlayIntent.putExtra(PrivacyOverlayService.EXTRA_OPACITY, settings?.privacyOverlayOpacity ?: 0.4f)
                        overlayIntent.putExtra(PrivacyOverlayService.EXTRA_PATTERN, settings?.privacyPatternIndex ?: 1)

                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(overlayIntent)
                        } else {
                            context.startService(overlayIntent)
                        }
                        
                        settings?.let {
                            db.settingsDao.saveSettings(it.copy(isPrivacyOverlayEnabled = true))
                        }
                    } else {
                        // Stop Privacy Overlay
                        overlayIntent.action = PrivacyOverlayService.ACTION_STOP
                        context.stopService(overlayIntent)
                        
                        val db = AppDatabase.getInstance(context)
                        val settings = db.settingsDao.getSettings()
                        settings?.let {
                            db.settingsDao.saveSettings(it.copy(isPrivacyOverlayEnabled = false))
                        }
                    }
                }
            } catch (v: Exception) {
                v.printStackTrace()
            }
        }
    }

    private fun applyGradientWallpaper(context: Context, startCol: Int, endCol: Int) {
        try {
            val wm = WallpaperManager.getInstance(context.applicationContext)
            // Generate elegant wallpaper bitmap dimensions
            val width = 1080
            val height = 2400
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val paint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    startCol, endCol, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

            wm.setBitmap(bitmap)
            Log.i("AutomationReceiver", "Gradient Wallpaper applied automatically.")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
