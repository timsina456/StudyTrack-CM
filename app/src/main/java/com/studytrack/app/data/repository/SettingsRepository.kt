package com.studytrack.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "studytrack_settings")

enum class AppThemeMode { LIGHT, DARK, SYSTEM }

data class AppSettings(
    val dailyTargetMinutes: Int = 120,
    val weeklyTargetMinutes: Int = 600,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val gamificationEnabled: Boolean = true
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val DAILY_TARGET = intPreferencesKey("daily_target_minutes")
        val WEEKLY_TARGET = intPreferencesKey("weekly_target_minutes")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val GAMIFICATION = booleanPreferencesKey("gamification_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            dailyTargetMinutes = prefs[Keys.DAILY_TARGET] ?: 120,
            weeklyTargetMinutes = prefs[Keys.WEEKLY_TARGET] ?: 600,
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() } ?: AppThemeMode.SYSTEM,
            gamificationEnabled = prefs[Keys.GAMIFICATION] ?: true
        )
    }

    suspend fun setDailyTarget(minutes: Int) {
        context.dataStore.edit { it[Keys.DAILY_TARGET] = minutes }
    }

    suspend fun setWeeklyTarget(minutes: Int) {
        context.dataStore.edit { it[Keys.WEEKLY_TARGET] = minutes }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setGamificationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GAMIFICATION] = enabled }
    }
}
