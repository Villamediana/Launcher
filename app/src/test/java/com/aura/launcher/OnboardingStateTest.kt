package com.aura.launcher

import com.aura.launcher.data.Settings
import com.aura.launcher.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Granting the home role makes Android relaunch Aura *as the home app*: a fresh
 * task, and with `stateNotNeeded` + `clearTaskOnLaunch` on the Activity nothing
 * in memory or in saved instance state comes back. The step therefore has to
 * survive on disk, or setup restarts at step one exactly when it succeeded.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingStateTest {

    private val repository get() = SettingsRepository(RuntimeEnvironment.getApplication())

    @Test
    fun `the onboarding step survives a full relaunch`() = runBlocking {
        val writer = repository
        writer.update { it.copy(onboardingStep = 2) }

        // A brand new repository stands in for the process starting over.
        val afterRelaunch = repository.settings.first()
        assertEquals(2, afterRelaunch.onboardingStep)
        assertFalse(afterRelaunch.onboarded)
    }

    @Test
    fun `the step defaults to the beginning on a first ever launch`() = runBlocking {
        assertEquals(0, Settings().onboardingStep)
    }

    @Test
    fun `finishing setup is recorded independently of the step`() = runBlocking {
        val writer = repository
        writer.update { it.copy(onboardingStep = 3, onboarded = true) }

        val loaded = repository.settings.first()
        assertEquals(3, loaded.onboardingStep)
        assertEquals(true, loaded.onboarded)
    }
}
