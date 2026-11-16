package dev.ilgax.utils.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMuterTest {
    private val own = 10200
    private val tiktok = 10345
    private val store = MemoryStore()
    private val muter = AppMuter(store, ownUid = own)
    private val ops = FakeOps(
        mapOf(
            tiktok to listOf("com.zhiliaoapp.musically"),
            10400 to listOf("org.example.a", "org.example.b"), // a shared uid
            own to listOf("dev.ilgax.utils"),
            1000 to listOf("android"),
            2000 to listOf("com.android.shell"),
            10077 to listOf("com.android.systemui"),
        ),
    )
    private fun key(uid: Int, pkg: String) = uid to pkg

    @Test fun anAppOnTheSpeakerIsMutedWithItsPreviousModeRecorded() {
        ops.modes[key(tiktok, "com.zhiliaoapp.musically")] = 3 // MODE_DEFAULT
        val r = muter.reconcile(setOf(tiktok), ops)
        assertEquals(1, r.muted.size)
        assertEquals(AppMuter.Entry(tiktok, "com.zhiliaoapp.musically", 3), muter.entries().single())
        assertEquals(1, ops.modes[key(tiktok, "com.zhiliaoapp.musically")])
        // Muting again does nothing.
        ops.writes.clear()
        muter.reconcile(setOf(tiktok), ops)
        assertTrue(ops.writes.isEmpty())
    }

    @Test fun everyPackageOfASharedUidIsMutedAndRestoredSeparately() {
        ops.modes[key(10400, "org.example.a")] = 0
        ops.modes[key(10400, "org.example.b")] = 4
        muter.reconcile(setOf(10400), ops)
        assertEquals(2, muter.entries().size)
        muter.releaseAll(ops)
        assertEquals(0, ops.modes[key(10400, "org.example.a")])
        assertEquals(4, ops.modes[key(10400, "org.example.b")])
    }

    @Test fun anAppIsRestoredWhenItsPlayersLeaveTheSpeaker() {
        ops.modes[key(tiktok, "com.zhiliaoapp.musically")] = 0
        muter.reconcile(setOf(tiktok), ops)
        val r = muter.reconcile(emptySet(), ops) // the buds took over again
        assertEquals(1, r.restored.size)
        assertEquals(0, ops.modes[key(tiktok, "com.zhiliaoapp.musically")])
        assertFalse(muter.hasEntries())
    }

    @Test fun everythingIsRestoredWhenTheSwitchGoesOff() {
        ops.modes[key(tiktok, "com.zhiliaoapp.musically")] = 0
        ops.modes[key(10400, "org.example.a")] = 3
        muter.reconcile(setOf(tiktok, 10400), ops)
        val r = muter.releaseAll(ops)
        assertEquals(3, r.restored.size)
        assertFalse(muter.hasEntries())
        assertEquals(0, ops.modes[key(tiktok, "com.zhiliaoapp.musically")])
        assertEquals(3, ops.modes[key(10400, "org.example.a")])
    }

    @Test fun withoutShizukuTheRestoreStaysPendingAndFinishesLater() {
        ops.modes[key(tiktok, "com.zhiliaoapp.musically")] = 0
        muter.reconcile(setOf(tiktok), ops)

        val down = muter.releaseAll(null) // OFF while Shizuku is gone
        assertTrue(down.restored.isEmpty())
        assertTrue(muter.hasEntries())
        assertEquals(1, ops.modes[key(tiktok, "com.zhiliaoapp.musically")]) // still muted, and on record

        // The process restarts: a new muter reads the persisted record.
        val later = AppMuter(store, ownUid = own)
        assertTrue(later.hasEntries())
        later.releaseAll(ops) // Shizuku is back
        assertFalse(later.hasEntries())
        assertEquals(0, ops.modes[key(tiktok, "com.zhiliaoapp.musically")])
    }

    @Test fun aFailedRestoreIsKeptForTheNextAttempt() {
        ops.modes[key(tiktok, "com.zhiliaoapp.musically")] = 0
        muter.reconcile(setOf(tiktok), ops)
        ops.failSet = true
        val r = muter.releaseAll(ops)
        assertEquals(1, r.failures.size)
        assertTrue(muter.hasEntries())
        ops.failSet = false
        muter.releaseAll(ops)
        assertFalse(muter.hasEntries())
    }

    @Test fun neverMutesOurOwnUidTheSystemUidOrSystemUi() {
        val r = muter.reconcile(setOf(own, 1000, 2000, 0, 10077), ops)
        assertTrue(r.muted.isEmpty())
        assertTrue(ops.writes.isEmpty())
        assertFalse(muter.hasEntries())
        assertTrue(muter.isProtectedUid(own))
        assertTrue(muter.isProtectedUid(1000))
        assertTrue(muter.isProtectedUid(1000 + 10 * AppMuter.PER_USER_RANGE)) // system uid of a secondary user
        assertFalse(muter.isProtectedUid(tiktok))
    }

    @Test fun anAppOpAlreadyIgnoredByTheUserIsLeftAlone() {
        ops.modes[key(tiktok, "com.zhiliaoapp.musically")] = 1
        muter.reconcile(setOf(tiktok), ops)
        assertFalse(muter.hasEntries())
        assertTrue(ops.writes.isEmpty())
    }

    @Test fun aModeTheUserChangedMeanwhileIsNotOverwrittenOnRestore() {
        ops.modes[key(tiktok, "com.zhiliaoapp.musically")] = 0
        muter.reconcile(setOf(tiktok), ops)
        ops.modes[key(tiktok, "com.zhiliaoapp.musically")] = 2 // changed by hand in the meantime
        ops.writes.clear()
        muter.releaseAll(ops)
        assertTrue(ops.writes.isEmpty())
        assertEquals(2, ops.modes[key(tiktok, "com.zhiliaoapp.musically")])
        assertFalse(muter.hasEntries())
    }

    @Test fun aFailedMuteLeavesNoRecord() {
        ops.failSet = true
        val r = muter.reconcile(setOf(tiktok), ops)
        assertEquals(1, r.failures.size)
        assertFalse(muter.hasEntries())
    }

    @Test fun noBackendMeansNothingChanges() {
        val r = muter.reconcile(setOf(tiktok), null)
        assertTrue(r.muted.isEmpty() && r.restored.isEmpty())
        assertFalse(muter.hasEntries())
    }

    // ---- which uids are on the speaker ----

    @Test fun onlyStartedPlayersRoutedToTheSpeakerCount() {
        val scan = SpeakerScans.scan(
            listOf(
                PlayerRoute(tiktok, started = true, onSpeaker = true),
                PlayerRoute(10500, started = true, onSpeaker = false), // on the buds
                PlayerRoute(10501, started = false, onSpeaker = true), // paused
            ),
            speakerActive = false,
        )
        assertEquals(setOf(tiktok), scan.uids)
        assertFalse(scan.unknownOwner)
    }

    @Test fun aPlayerWithoutARouteFollowsTheActiveOutput() {
        val routes = listOf(PlayerRoute(tiktok, started = true, onSpeaker = null))
        assertEquals(setOf(tiktok), SpeakerScans.scan(routes, speakerActive = true).uids)
        assertTrue(SpeakerScans.scan(routes, speakerActive = false).uids.isEmpty())
    }

    @Test fun aHiddenOwnerOnTheSpeakerIsReportedSoMediaCanBePaused() {
        val scan = SpeakerScans.scan(listOf(PlayerRoute(-1, started = true, onSpeaker = true)), speakerActive = false)
        assertTrue(scan.unknownOwner)
        assertTrue(scan.uids.isEmpty())
        // A hidden owner whose route is only guessed is not worth pausing for.
        assertFalse(SpeakerScans.scan(listOf(PlayerRoute(-1, started = true, onSpeaker = null)), speakerActive = true).unknownOwner)
    }
}
