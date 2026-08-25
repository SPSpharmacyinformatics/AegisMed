package com.aegismed.app.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "aegis_settings")

object Settings {
    private val FONT_SCALE = floatPreferencesKey("font_scale")
    private val NIGHT_ROUTINE = booleanPreferencesKey("night_routine_active")
    private val ESCALATION_MINUTES = intPreferencesKey("escalation_minutes")
    private val LOW_STOCK_ALERTS = booleanPreferencesKey("low_stock_alerts")
    private val ONLINE_LOOKUPS = booleanPreferencesKey("online_lookups_enabled")

    fun fontScaleFlow(context: Context): Flow<Float> =
        context.dataStore.data.map { it[FONT_SCALE] ?: 1.15f }

    suspend fun fontScaleValue(context: Context): Float =
        context.dataStore.data.first()[FONT_SCALE] ?: 1.15f

    suspend fun setFontScale(context: Context, value: Float) {
        context.dataStore.edit { it[FONT_SCALE] = value }
    }

    suspend fun isNightRoutine(context: Context): Boolean =
        context.dataStore.data.first()[NIGHT_ROUTINE] ?: false

    fun nightRoutineFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[NIGHT_ROUTINE] ?: false }

    suspend fun setNightRoutine(context: Context, value: Boolean) {
        context.dataStore.edit { it[NIGHT_ROUTINE] = value }
    }

    suspend fun escalationMinutes(context: Context): Int =
        context.dataStore.data.first()[ESCALATION_MINUTES] ?: 45

    suspend fun setEscalationMinutes(context: Context, value: Int) {
        context.dataStore.edit { it[ESCALATION_MINUTES] = value }
    }

    suspend fun lowStockAlertsEnabled(context: Context): Boolean =
        context.dataStore.data.first()[LOW_STOCK_ALERTS] ?: true

    suspend fun setLowStockAlerts(context: Context, value: Boolean) {
        context.dataStore.edit { it[LOW_STOCK_ALERTS] = value }
    }

    fun onlineLookupsFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[ONLINE_LOOKUPS] ?: true }

    suspend fun onlineLookupsEnabled(context: Context): Boolean =
        context.dataStore.data.first()[ONLINE_LOOKUPS] ?: true

    suspend fun setOnlineLookups(context: Context, value: Boolean) {
        context.dataStore.edit { it[ONLINE_LOOKUPS] = value }
    }
}
