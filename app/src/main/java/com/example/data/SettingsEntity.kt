package com.example.data

import androidx.room.*

@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val id: String = "global",
    val isProUnlocked: Boolean = false,
    val isDoubleTapSleepEnabled: Boolean = false,
    val isDoubleTapWakeEnabled: Boolean = false,
    val isShakeFlashlightEnabled: Boolean = false,
    val shakeSensitivity: Float = 14.0f, // Force threshold
    val isPrivacyOverlayEnabled: Boolean = false,
    val privacyOverlayOpacity: Float = 0.4f,
    val privacyPatternIndex: Int = 1 // 0: None, 1: Fine Grid, 2: Amber Warming, 3: Diagonal Microstripes
)
