package dev.ilgax.utils.speaker

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentPlanTest {
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private val buds = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    private val wired = AudioDeviceInfo.TYPE_WIRED_HEADPHONES

    @Test fun builtInOutputsAreNotExternal() {
        assertFalse(ExperimentPlan.externalOutput(listOf(speaker, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_REMOTE_SUBMIX), false))
        assertTrue(ExperimentPlan.externalOutput(listOf(speaker, buds), false))
        assertTrue(ExperimentPlan.externalOutput(listOf(speaker, AudioDeviceInfo.TYPE_USB_HEADSET), false))
    }

    @Test fun ourOwnFakeHeadsetDoesNotCount() {
        assertFalse(ExperimentPlan.externalOutput(listOf(speaker, wired), fakeWired = true))
        assertTrue(ExperimentPlan.externalOutput(listOf(speaker, wired), fakeWired = false))
        assertTrue(ExperimentPlan.externalOutput(listOf(speaker, wired, buds), fakeWired = true))
    }

    @Test fun fakeHeadsetOnlyWhileTheSwitchAndToggleAreOnAndNothingExternalIsConnected() {
        assertTrue(ExperimentPlan.wiredWanted(switchOn = true, toggleOn = true, externalOutput = false))
        assertFalse(ExperimentPlan.wiredWanted(false, true, false))
        assertFalse(ExperimentPlan.wiredWanted(true, false, false))
        assertFalse(ExperimentPlan.wiredWanted(true, true, true))
    }

    @Test fun redirectCapturesEveryAppWhenTheSpeakerIsTheOnlyOutput() {
        assertEquals(RedirectPlan.EveryApp, ExperimentPlan.redirect(true, true, false, emptySet()))
        assertEquals(RedirectPlan.EveryApp, ExperimentPlan.redirect(true, true, false, setOf(10345)))
    }

    @Test fun redirectOnlyTargetsTheAppsFoundOnTheSpeakerWhenSomethingExternalIsConnected() {
        assertEquals(RedirectPlan.OnlyUids(setOf(10345)), ExperimentPlan.redirect(true, true, true, setOf(10345)))
        assertEquals(RedirectPlan.None, ExperimentPlan.redirect(true, true, true, emptySet()))
    }

    @Test fun redirectIsOffWhenEitherSwitchIsOff() {
        assertEquals(RedirectPlan.None, ExperimentPlan.redirect(false, true, false, setOf(1)))
        assertEquals(RedirectPlan.None, ExperimentPlan.redirect(true, false, false, setOf(1)))
    }
}
