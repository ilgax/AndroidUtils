package dev.ilgax.utils.remote

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationVisibilityTest {
    @Test
    fun hiddenWhenChannelOffOrAppNotificationsOff() {
        assertTrue(NotificationVisibility.isHidden(NotificationManager.IMPORTANCE_NONE, true))
        assertTrue(NotificationVisibility.isHidden(NotificationManager.IMPORTANCE_MIN, false))
        assertTrue(NotificationVisibility.isHidden(null, false))
    }

    @Test
    fun shownOtherwise() {
        assertFalse(NotificationVisibility.isHidden(NotificationManager.IMPORTANCE_MIN, true))
        assertFalse(NotificationVisibility.isHidden(null, true))
    }
}
