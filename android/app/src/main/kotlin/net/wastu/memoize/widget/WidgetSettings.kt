package net.wastu.memoize.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.widgetPreferences by preferencesDataStore("notes-widgets")

/** Non-secret, durable configuration shared by launcher, app and upgrade checks. */
class WidgetSettings(context: Context) {
    private val preferences = context.applicationContext.widgetPreferences

    suspend fun tag(appWidgetId: Int): String? = preferences.data.first()[key(appWidgetId)]

    fun observeTag(appWidgetId: Int): Flow<String?> = preferences.data.map { it[key(appWidgetId)] }

    suspend fun setTag(appWidgetId: Int, tag: String?) {
        preferences.edit { values ->
            val key = key(appWidgetId)
            if (tag.isNullOrBlank()) values.remove(key) else values[key] = tag
        }
    }

    suspend fun remove(appWidgetId: Int) = setTag(appWidgetId, null)

    private fun key(appWidgetId: Int) = stringPreferencesKey("tag-${appWidgetId.also { require(it > 0) }}")
}
