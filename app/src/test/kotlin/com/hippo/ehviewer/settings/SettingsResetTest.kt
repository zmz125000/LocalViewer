package com.hippo.ehviewer.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ehviewer.core.preferences.DataStorePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsResetTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }

    private class TestSettings(store: DataStore<Preferences>) : DataStorePreferences(store) {
        val enabled = boolPref("enabled", true)
        val seconds = intPref("seconds", 5)
        val location = stringOrNullPref("location")
    }

    @Test
    fun resetRestoresDeclaredDefaultsAndRemovesObsoleteKeys() = runBlocking {
        val store = MemoryStore()
        val settings = TestSettings(store)
        settings.withMutableSnapshot {
            it[settings.enabled] = false
            it[settings.seconds] = 200
            it[settings.location] = "/custom/folder"
        }
        store.updateData {
            it.toMutablePreferences().apply { this[stringPreferencesKey("obsolete_setting")] = "old value" }
        }
        assertEquals(false, settings.enabled.valueFlow().first())
        assertEquals(200, settings.seconds.valueFlow().first())

        settings.resetToDefaults()

        assertTrue(store.data.value.asMap().isEmpty())
        assertEquals(true, settings.enabled.valueFlow().first())
        assertEquals(5, settings.seconds.valueFlow().first())
        assertEquals(null, settings.snapshot()[settings.location])

        // Reset leaves the store usable for subsequent preference edits.
        settings.withMutableSnapshot { it[settings.seconds] = 10 }
        assertEquals(10, settings.seconds.valueFlow().first())
    }
}
