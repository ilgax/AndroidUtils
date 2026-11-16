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

    private val t0 = 1_000_000L
    private fun muteTiktok(previous: Int = 0) {
        ops.modes[key(tiktok, "com.zhiliaoapp.musically")] = previous
        muter.reconcile(setOf(tiktok), ops, t0)
    }

    @Test fun anAppStaysMutedWhenItsPlayerPausesStopsOrDisappears() {
        muteTiktok()
        // Paused: the player is still there, no longer started.
        var r = muter.reconcile(emptySet(), ops, t0 + 1000, present = setOf(tiktok))
        assertTrue(r.restored.isEmpty())
        // Swiped: not in the started set, nor anywhere else.
        r = muter.reconcile(emptySet(), ops, t0 + 2000)
        assertTrue(r.restored.isEmpty())
        assertEquals(1, ops.modes[key(tiktok, "com.zhiliaoapp.musically")])
        assertTrue(muter.hasEntries())
    }

    @Test fun anAppIsRestoredWhenAPlayerOfItIsKnownToBeOnAnotherRoute() {
        muteTiktok()
        val r = muter.reconcile(emptySet(), ops, t0 + 1000, offSpeaker = setOf(tiktok), present = setOf(tiktok))
        assertEquals(1, r.restored.size)
        assertEquals(0, ops.modes[key(tiktok, "com.zhiliaoapp.musically")])
        assertFalse(muter.hasEntries())
    }

    @Test fun aPlayerOnTheSpeakerWinsOverAnotherOneOnTheBuds() {
        muteTiktok()
        val r = muter.reconcile(setOf(tiktok), ops, t0 + 1000, offSpeaker = setOf(tiktok), present = setOf(tiktok))
        assertTrue(r.restored.isEmpty())
        assertTrue(muter.hasEntries())
    }

    @Test fun anUnknownRouteDoesNotUnmute() {
        muteTiktok()
        val scan = SpeakerScans.scan(listOf(PlayerRoute(tiktok, started = true, onSpeaker = null)), speakerActive = false)
        assertTrue(scan.offSpeaker.isEmpty())
        assertEquals(setOf(tiktok), scan.present)
        val r = muter.reconcile(scan.uids, ops, t0 + 1000, scan.offSpeaker, scan.present)
        assertTrue(r.restored.isEmpty())
        assertTrue(muter.hasEntries())
    }

    @Test fun theScanReportsKnownNonSpeakerRoutesOfStartedAndStoppedPlayers() {
        val scan = SpeakerScans.scan(
            listOf(
                PlayerRoute(10500, started = false, onSpeaker = false),
                PlayerRoute(10501, started = true, onSpeaker = false),
                PlayerRoute(10502, started = false, onSpeaker = true),
                PlayerRoute(-1, started = true, onSpeaker = false),
            ),
            speakerActive = true,
        )
        assertEquals(setOf(10500, 10501), scan.offSpeaker)
        assertEquals(setOf(10500, 10501, 10502), scan.present)
        assertTrue(scan.uids.isEmpty())
    }

    @Test fun anAppWithNoPlayerForTheGracePeriodIsRestored() {
        muteTiktok()
        var r = muter.reconcile(emptySet(), ops, t0 + AppMuter.GRACE_MS - 1)
        assertTrue(r.restored.isEmpty())
        r = muter.reconcile(emptySet(), ops, t0 + AppMuter.GRACE_MS + 1)
        assertEquals(1, r.restored.size)
        assertFalse(muter.hasEntries())
    }

    @Test fun anyPlayerOfTheAppRestartsTheGracePeriod() {
        muteTiktok()
        val later = t0 + AppMuter.GRACE_MS - 1000
        muter.reconcile(emptySet(), ops, later, present = setOf(tiktok)) // paused, still there
        assertEquals(later, muter.entries().single().seen)
        val r = muter.reconcile(emptySet(), ops, later + AppMuter.GRACE_MS - 1)
        assertTrue(r.restored.isEmpty())
    }

    @Test fun theLastSeenTimeIsNotRewrittenOnEveryScan() {
        muteTiktok()
        muter.reconcile(setOf(tiktok), ops, t0 + 1000)
        assertEquals(t0, muter.entries().single().seen)
        muter.reconcile(setOf(tiktok), ops, t0 + AppMuter.SEEN_WRITE_MS)
        assertEquals(t0 + AppMuter.SEEN_WRITE_MS, muter.entries().single().seen)
    }

    @Test fun anOldRecordStillParsesAndStartsItsGracePeriodAtTheFirstScan() {
        store.putString(AppMuter.K_MUTED, "$tiktok|3|com.zhiliaoapp.musically\n10400|0|org.example.a")
        assertEquals(AppMuter.Entry(tiktok, "com.zhiliaoapp.musically", 3), muter.entries().first())
        assertEquals(2, muter.entries().size)
        ops.modes[key(tiktok, "com.zhiliaoapp.musically")] = 1
        var r = muter.reconcile(emptySet(), ops, t0)
        assertTrue(r.restored.isEmpty()) // unknown age: not expired, the clock starts now
        assertEquals(t0, muter.entries().first().seen)
        r = muter.reconcile(emptySet(), ops, t0 + AppMuter.GRACE_MS + 1)
        assertEquals(3, ops.modes[key(tiktok, "com.zhiliaoapp.musically")]) // restored to the recorded previous mode
    }

    @Test fun theNewRecordFormatRoundTripsAndOldCodeFindsItsFields() {
        muteTiktok(3)
        assertEquals("$tiktok|3|com.zhiliaoapp.musically|$t0|0", store.getString(AppMuter.K_MUTED))
        assertEquals(AppMuter.Entry(tiktok, "com.zhiliaoapp.musically", 3, t0, false), AppMuter(store, own).entries().single())
    }

    @Test fun listedAppsAreMutedOnAndRestoredOnOffWithoutAnyAudio() {
        ops.modes[key(tiktok, "com.zhiliaoapp.musically")] = 3
        val r = muter.reconcile(emptySet(), ops, t0, pinned = setOf("com.zhiliaoapp.musically"))
        assertEquals(1, r.muted.size)
        assertTrue(muter.entries().single().pinned)
        assertEquals(1, ops.modes[key(tiktok, "com.zhiliaoapp.musically")])
        muter.releaseAll(ops)
        assertEquals(3, ops.modes[key(tiktok, "com.zhiliaoapp.musically")])
        assertFalse(muter.hasEntries())
    }

    @Test fun aListedAppStaysMutedWhateverItsRouteAndHoweverLong() {
        val pinned = setOf("com.zhiliaoapp.musically")
        muter.reconcile(emptySet(), ops, t0, pinned = pinned)
        val r = muter.reconcile(emptySet(), ops, t0 + 3 * AppMuter.GRACE_MS, offSpeaker = setOf(tiktok), present = setOf(tiktok), pinned = pinned)
        assertTrue(r.restored.isEmpty())
        assertTrue(muter.hasEntries())
    }

    @Test fun anAppTakenOffTheListIsRestoredAndOneAddedWhileMutedBecomesListed() {
        val pkg = "com.zhiliaoapp.musically"
        muter.reconcile(emptySet(), ops, t0, pinned = setOf(pkg))
        val r = muter.reconcile(emptySet(), ops, t0 + 1000, pinned = emptySet())
        assertEquals(1, r.restored.size)
        assertFalse(muter.hasEntries())

        muteTiktok() // caught on the speaker first, then added to the list
        muter.reconcile(emptySet(), ops, t0 + AppMuter.GRACE_MS * 2, pinned = setOf(pkg))
        assertTrue(muter.entries().single().pinned)
    }

    @Test fun listedAppsFollowTheSameGuarantees() {
        // Protected packages, an uninstalled app and an op the user already ignored are left alone.
        ops.modes[key(10400, "org.example.a")] = 1
        val r = muter.reconcile(
            emptySet(), ops, t0,
            pinned = setOf("android", "com.android.systemui", "dev.ilgax.utils", "not.installed", "org.example.a"),
        )
        assertTrue(r.muted.isEmpty())
        assertTrue(ops.writes.isEmpty())
        assertFalse(muter.hasEntries())
        // Without Shizuku nothing changes.
        assertTrue(muter.reconcile(emptySet(), null, t0, pinned = setOf("org.example.b")).muted.isEmpty())
        assertFalse(muter.hasEntries())
    }

    @Test fun theMuteListIsStoredAsPackageNames() {
        val list = MuteList(store)
        list.add("b.pkg"); list.add("a.pkg"); list.add("b.pkg")
        assertEquals(listOf("b.pkg", "a.pkg"), MuteList(store).packages().toList())
        list.remove("b.pkg")
        list.remove("a.pkg")
        assertTrue(list.packages().isEmpty())
        assertEquals(null, store.getString(MuteList.K_MUTE_LIST))
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
