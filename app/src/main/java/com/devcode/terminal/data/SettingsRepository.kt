package com.devcode.terminal.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.devCodeSettings by preferencesDataStore(name = "devcode_settings")

class SettingsRepository(ctx: Context) {
    private val store = ctx.applicationContext.devCodeSettings

    val fontSize: Flow<Float> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val value = preferences[FONT_SIZE] ?: 14f
            if (value.isFinite()) value.coerceIn(10f, 22f) else 14f
        }

    suspend fun setFontSize(f: Float) {
        require(f.isFinite()) { "Font size must be finite" }
        store.edit { it[FONT_SIZE] = f.coerceIn(10f, 22f) }
    }

    val githubToken: Flow<String> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[GITHUB_TOKEN] ?: ""
        }

    suspend fun setGithubToken(token: String) {
        store.edit { it[GITHUB_TOKEN] = token.trim() }
    }

    companion object {
        private val FONT_SIZE = floatPreferencesKey("font_size")
        private val GITHUB_TOKEN = stringPreferencesKey("github_token")
    }
}
