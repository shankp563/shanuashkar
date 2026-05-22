package com.example.data

import androidx.room.*

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val triggerType: String, // "TIME" (for specific hour) or "BATTERY" (percentage threshold)
    val triggerValue: String, // e.g. "18:00" or "20"
    val actionType: String, // "WALLPAPER" or "PRIVACY_FILTER" or "BOTH"
    val actionValue: String, // wallpaper hex gradient / source color code (e.g. "#FF4A00,#9B00E8") or state ("ON"/"OFF")
    val isEnabled: Boolean = true
)
