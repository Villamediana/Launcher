package com.aura.launcher

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.HomeWidget
import com.aura.launcher.data.Settings
import com.aura.launcher.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Hiding is stored as the exception, and that choice has two consequences
 * worth holding still.
 *
 * A widget nobody has touched is on — including one that did not exist when
 * the set was last written, which is what stops an update from shipping an
 * invisible feature. And turning one off must leave the others exactly where
 * they were: this is the same store that once had a toggle resetting its
 * neighbours, and the ids in here are a single string in one key.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetVisibilityTest {

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File.createTempFile("aura-widgets", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = scope) { file }
        repository = SettingsRepository(ApplicationProvider.getApplicationContext(), store)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `a new install shows the basics and nothing else`() {
        val fresh = Settings()
        val basics = setOf(
            HomeWidget.CLOCK, HomeWidget.DATE, HomeWidget.CALENDAR,
            HomeWidget.WEATHER, HomeWidget.BATTERY, HomeWidget.TODO,
        )
        for (widget in HomeWidget.entries) {
            assertEquals(widget.id, widget in basics, fresh.shows(widget))
        }
    }

    @Test
    fun `turning everything on is remembered as everything on`() = runBlocking {
        repository.update { it.copy(hiddenWidgets = emptySet()) }
        val after = repository.settings.first()
        for (widget in HomeWidget.entries) assertTrue(widget.id, after.shows(widget))
    }

    @Test
    fun `hiding one widget survives a reload and leaves the rest alone`() = runBlocking {
        repository.update { it.copy(hiddenWidgets = setOf(HomeWidget.TODO.id)) }

        val after = repository.settings.first()
        assertFalse(after.showTodo)
        assertTrue(after.showTimer)
        assertTrue(after.showClock)
        assertTrue(after.showNotifications)
        assertEquals(setOf(HomeWidget.TODO.id), after.hiddenWidgets)
    }

    @Test
    fun `an unrelated change does not bring a hidden widget back`() = runBlocking {
        repository.update { it.copy(hiddenWidgets = setOf(HomeWidget.WEATHER.id)) }
        repository.update { it.copy(hapticsEnabled = false) }

        val after = repository.settings.first()
        assertFalse(after.showWeather)
        assertFalse(after.hapticsEnabled)
    }

    @Test
    fun `an id nobody recognises is carried without hiding anything else`() = runBlocking {
        // A widget removed in a later version leaves its id behind in the set.
        // It must not be read as a wildcard, and it must not be dropped either:
        // downgrading should find the widget still off.
        repository.update { it.copy(hiddenWidgets = setOf("a-widget-from-the-future")) }

        val after = repository.settings.first()
        for (widget in HomeWidget.entries) {
            assertTrue("${widget.id} should still be visible", after.shows(widget))
        }
        assertEquals(setOf("a-widget-from-the-future"), after.hiddenWidgets)
    }
}
