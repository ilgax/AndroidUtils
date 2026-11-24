package dev.ilgax.utils.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityTest {
    @Test fun ageOutIsFiveSeconds() = assertEquals(5_000L, Reach.AGE_OUT_MS)

    @Test fun heardWithinWindowIsReachable() {
        val r = Reachability()
        assertTrue(r.hear("aa", 1_000))
        assertEquals(listOf("aa"), r.heard(5_999))
    }

    @Test fun silentForFiveSecondsAgesOut() {
        val r = Reachability()
        r.hear("aa", 1_000)
        assertEquals(emptyList<String>(), r.heard(6_000))
    }

    @Test fun beaconKeepsItAlive() {
        val r = Reachability()
        r.hear("aa", 0)
        assertFalse(r.hear("aa", 4_000))
        assertEquals(listOf("aa"), r.heard(8_000))
        assertEquals(emptyList<String>(), r.heard(9_000))
    }

    @Test fun orderIsFirstHeardAndStable() {
        val r = Reachability()
        r.hear("bb", 0); r.hear("aa", 100); r.hear("bb", 1_000)
        assertEquals(listOf("bb", "aa"), r.heard(1_500))
    }

    @Test fun reappearingHostGoesToTheEnd() {
        val r = Reachability()
        r.hear("aa", 0); r.hear("bb", 100)
        r.hear("bb", 4_000)
        assertEquals(listOf("bb"), r.heard(6_000))
        assertTrue(r.hear("aa", 7_000))
        assertEquals(listOf("bb", "aa"), r.heard(7_100))
    }

    @Test fun pinnedConnectionStaysHeard() {
        val r = Reachability()
        r.hear("aa", 0)
        assertEquals(listOf("aa"), r.heard(60_000, pinned = "aa"))
        assertEquals(listOf("aa"), r.heard(70_000, pinned = "aa"))
        assertEquals(emptyList<String>(), r.heard(80_000))
    }

    @Test fun burstCache() {
        assertTrue(Reach.needsBurst(null, 10))
        assertFalse(Reach.needsBurst(1_000, 1_000 + Reach.CACHE_MS - 1))
        assertTrue(Reach.needsBurst(1_000, 1_000 + Reach.CACHE_MS))
        assertTrue(Reach.needsBurst(5_000, 1_000)) // clock went backwards
    }
}

class TargetSelectionTest {
    private val paired = setOf("aa", "bb", "cc")
    private fun r(picked: String?, vararg heard: String) = TargetSelection.resolve(picked, heard.toList(), paired)

    @Test fun nothingHeard() {
        assertEquals(TargetState.NoneInRange, r(null).state)
        assertEquals(TargetState.NoneInRange, r("aa").state)
        assertNull(r("aa").remember)
    }

    @Test fun oneHeardIsTheTargetWithoutDropdown() {
        val res = r(null, "bb")
        assertEquals(TargetState.Single("bb"), res.state)
        assertNull(res.remember)
        assertEquals("bb", res.state.targetId)
    }

    @Test fun oneHeardAndItIsThePick() = assertEquals(TargetState.Single("aa"), r("aa", "aa").state)

    @Test fun severalHeardUseTheLastPick() {
        val res = r("bb", "aa", "bb")
        assertEquals(TargetState.Choice("bb", listOf("aa", "bb")), res.state)
        assertNull(res.remember)
    }

    @Test fun neverPickedTakesFirstHeardAndRemembersIt() {
        val res = r(null, "cc", "aa")
        assertEquals(TargetState.Choice("cc", listOf("cc", "aa")), res.state)
        assertEquals("cc", res.remember)
    }

    @Test fun rememberedDefaultDoesNotChangeWhenOrderChanges() {
        assertEquals("cc", r("cc", "aa", "cc").state.targetId)
    }

    @Test fun pickedGoesSilentWhileAnotherIsHeardShowsNotInRange() {
        val res = r("aa", "bb")
        assertEquals(TargetState.NotInRange("aa", listOf("bb")), res.state)
        assertNull(res.state.targetId)
        assertNull(res.remember)
        assertEquals(TargetState.NotInRange("aa", listOf("bb", "cc")), r("aa", "bb", "cc").state)
    }

    @Test fun unpairedPickIsIgnoredAndUnpairedHostsAreNotHeard() {
        assertEquals(TargetState.Single("aa"), r("zz", "aa").state)
        assertEquals(TargetState.NoneInRange, r(null, "zz").state)
    }

    @Test fun duplicatesInHeardCollapse() = assertEquals(TargetState.Single("aa"), r(null, "aa", "aa").state)

    // The tile
    private fun t(picked: String?, vararg heard: String) = TargetSelection.tile(picked, heard.toList(), paired)

    @Test fun tileOneHeardIsTheTarget() {
        assertEquals(TargetState.Single("bb"), t("aa", "bb"))
        assertEquals(TargetState.Single("bb"), t(null, "bb"))
    }

    @Test fun tileSeveralHeardUsesLastPick() = assertEquals(TargetState.Single("bb"), t("bb", "aa", "bb"))

    @Test fun tileLastPickSilentShowsNotInRange() =
        assertEquals(TargetState.NotInRange("cc", listOf("aa", "bb")), t("cc", "aa", "bb"))

    @Test fun tileNothingPickedTakesFirstHeard() = assertEquals(TargetState.Single("aa"), t(null, "aa", "bb"))

    @Test fun tileNoneHeard() = assertEquals(TargetState.NoneInRange, t("aa"))
}

class NativePhoneTest {
    private val bonded = listOf(
        BondedDevice("Galaxy Buds", 0x0400), BondedDevice("Nothing Phone (2)", NativePhone.MAJOR_PHONE), BondedDevice(null, NativePhone.MAJOR_PHONE),
    )

    @Test fun matchesBondedPhoneByName() =
        assertTrue(NativePhone.isNative("Nothing Phone (2)", DeviceClass.PHONE, bonded, null))

    @Test fun matchIgnoresCaseAndSpaces() =
        assertTrue(NativePhone.isNative(" nothing phone (2) ", DeviceClass.PHONE, bonded, null))

    @Test fun nonPhoneClassDoesNotCount() =
        assertFalse(NativePhone.isNative("Galaxy Buds", DeviceClass.PHONE, bonded, null))

    @Test fun tabletIsNeverFlaggedByDefault() {
        assertFalse(NativePhone.isNative("Nothing Phone (2)", DeviceClass.TABLET, bonded, null))
        assertFalse(NativePhone.isNative("Galaxy Tab S9 FE+", DeviceClass.TABLET, bonded, null))
    }

    @Test fun unknownInfoHidesNothing() {
        assertFalse(NativePhone.isNative(null, DeviceClass.UNKNOWN, bonded, null))
        assertFalse(NativePhone.isNative("", DeviceClass.PHONE, bonded, null))
        assertFalse(NativePhone.isNative("Nothing Phone (2)", DeviceClass.PHONE, emptyList(), null))
    }

    @Test fun overrideWinsBothWays() {
        assertTrue(NativePhone.isNative(null, DeviceClass.UNKNOWN, emptyList(), true))
        assertTrue(NativePhone.isNative("x", DeviceClass.TABLET, bonded, true))
        assertFalse(NativePhone.isNative("Nothing Phone (2)", DeviceClass.PHONE, bonded, false))
    }

    @Test fun featuresHiddenOnlyForNativePhone() {
        for (c in listOf(Command.PLAY_PAUSE, Command.VOLUME_UP, Command.VOLUME_DOWN, Command.NEXT, Command.PREVIOUS, Command.RING)) {
            assertFalse(c.name, HostFeatures.available(c, true))
            assertTrue(c.name, HostFeatures.available(c, false))
        }
        for (c in listOf(Command.LOCK, Command.KILL_APP, Command.OPEN_APP, Command.SWITCH_APP, Command.SPEAKER, Command.RUN_MACRO)) {
            assertTrue(c.name, HostFeatures.available(c, true))
        }
        assertFalse(HostFeatures.nowPlaying(true))
        assertTrue(HostFeatures.nowPlaying(false))
    }
}

class HostBookTest {
    private val id1 = "0a0b0c0d"
    private val id2 = "01020304"

    @Test fun migrationTurnsTheSingleTabletIntoEntryOneKeepingTheOldAlias() {
        val b = HostBook.migrate(null, id1)
        assertEquals(1, b.entries.size)
        assertEquals(id1, b.entries[0].id)
        assertEquals("tabremote-hmac-watch", b.entries[0].alias)
        assertFalse(b.entries[0].isNative)
    }

    @Test fun migrationWithNothingPairedIsEmpty() {
        assertTrue(HostBook.migrate(null, null).isEmpty)
        assertTrue(HostBook.migrate(null, "short").isEmpty)
    }

    @Test fun migrationDoesNotRunOverAnExistingBook() {
        val stored = HostBook(listOf(HostEntry(id2, HostBook.aliasFor(id2), "Phone"))).encode()
        assertEquals(listOf(id2), HostBook.migrate(stored, id1).entries.map { it.id })
    }

    @Test fun pairingAddsWithItsOwnAlias() {
        val b = HostBook.migrate(null, id1).paired(id2)
        assertEquals(listOf(id1, id2), b.entries.map { it.id })
        assertEquals("tabremote-hmac-watch-$id2", b.get(id2)!!.alias)
        assertEquals("tabremote-hmac-watch", b.get(id1)!!.alias)
    }

    @Test fun rePairingAKnownIdChangesNothingButTheKey() {
        val b = HostBook.migrate(null, id1).rename(id1, "Tab").setNativeOverride(id1, true)
        assertEquals(b.entries, b.paired(id1).entries)
    }

    @Test fun removeAndRename() {
        val b = HostBook.migrate(null, id1).paired(id2).rename(id2, "  My phone ").remove(id1)
        assertEquals(listOf(id2), b.entries.map { it.id })
        assertEquals("My phone", b.get(id2)!!.name)
        assertTrue(b.get(id2)!!.customName)
    }

    @Test fun infoSetsDefaultNameButNotACustomOne() {
        val info = HostInfo("Galaxy Tab S9 FE+", "Tab BT", DeviceClass.TABLET)
        val b = HostBook.migrate(null, id1).applyInfo(id1, info, false, 100)
        assertEquals("Galaxy Tab S9 FE+", b.get(id1)!!.name)
        assertEquals(DeviceClass.TABLET, b.get(id1)!!.deviceClass)
        assertEquals(100L, b.get(id1)!!.infoAt)
        val renamed = b.rename(id1, "Tablet").applyInfo(id1, HostInfo("Other", null, DeviceClass.TABLET), false, 200)
        assertEquals("Tablet", renamed.get(id1)!!.name)
    }

    @Test fun overrideBeatsAutoAndSurvivesNewInfo() {
        val b = HostBook.migrate(null, id1).applyInfo(id1, HostInfo("P", "P", DeviceClass.PHONE), true, 1)
        assertTrue(b.get(id1)!!.isNative)
        val off = b.setNativeOverride(id1, false).applyInfo(id1, HostInfo("P", "P", DeviceClass.PHONE), true, 2)
        assertFalse(off.get(id1)!!.isNative)
        assertTrue(off.setNativeOverride(id1, null).get(id1)!!.isNative)
    }

    @Test fun encodeDecodeRoundTrip() {
        val b = HostBook.migrate(null, id1).paired(id2).applyInfo(id2, HostInfo("Nothing\tPhone", "NP", DeviceClass.PHONE), true, 77)
            .setNativeOverride(id2, false).rename(id1, "Tab")
        val back = HostBook.decode(b.encode())
        assertEquals(b.entries.map { it.copy(name = it.name.replace('\t', ' '), btName = it.btName) }, back.entries)
        assertEquals(false, back.get(id2)!!.nativeOverride)
        assertNull(back.get(id1)!!.nativeOverride)
    }

    @Test fun decodeSkipsGarbage() {
        assertTrue(HostBook.decode("garbage\n\nx\ty").isEmpty)
        assertTrue(HostBook.decode(null).isEmpty)
    }

    @Test fun idHexRoundTrip() {
        val bytes = HostBook.idBytes(id1)!!
        assertEquals(id1, HostBook.hex(bytes))
        assertNull(HostBook.idBytes("zz"))
    }

    @Test fun infoRefreshIsDueRarely() {
        assertTrue(UpdateSources.infoDue(0, 5))
        assertFalse(UpdateSources.infoDue(1_000, 2_000))
        assertTrue(UpdateSources.infoDue(1_000, 1_000 + UpdateSources.INFO_REFRESH_MS))
    }

    @Test fun updateSourcesPreferSelectedAndSkipRecentlyAskedOthers() {
        assertEquals(listOf("bb", "aa"), UpdateSources.order("bb", listOf("aa", "bb"), emptyMap(), 100))
        assertEquals(listOf("bb"), UpdateSources.order("bb", listOf("aa", "bb"), mapOf("aa" to 50L), 100))
        assertEquals(listOf("aa"), UpdateSources.order("zz", listOf("aa"), emptyMap(), 100)) // selected not heard
        assertEquals(listOf("bb", "aa"), UpdateSources.order("bb", listOf("aa", "bb"), mapOf("aa" to 50L), 50 + UpdateSources.OTHER_RECHECK_MS))
    }
}

class HostInfoFrameTest {
    private val key = ByteArray(32) { it.toByte() }
    private val mac = { d: ByteArray -> Hmac.sha256(key, d) }
    private val nonce = ByteArray(Protocol.NONCE_SIZE) { (it + 1).toByte() }

    @Test fun roundTrip() {
        val info = HostInfo("Galaxy Tab S9 FE+", "Tab BT", DeviceClass.TABLET)
        val f = InfoFrame.encode(mac, nonce, info)
        assertEquals(info, (InfoFrame.verify(mac, nonce, f) as InfoFrame.Result.Valid).info)
    }

    @Test fun noBluetoothNameAndUnicode() {
        val info = HostInfo("Téléphone ✓", null, DeviceClass.PHONE)
        val back = (InfoFrame.verify(mac, nonce, InfoFrame.encode(mac, nonce, info)) as InfoFrame.Result.Valid).info
        assertEquals(info, back)
        assertNull(back.btName)
    }

    @Test fun longNamesAreCutOnACharacterBoundary() {
        val info = HostInfo("é".repeat(60), "x".repeat(100), DeviceClass.PHONE)
        val back = (InfoFrame.verify(mac, nonce, InfoFrame.encode(mac, nonce, info)) as InfoFrame.Result.Valid).info
        assertEquals(20, back.displayName.length)
        assertEquals(InfoFrame.MAX_NAME, back.btName!!.length)
    }

    @Test fun wrongNonceKeyOrTamperingIsRejected() {
        val f = InfoFrame.encode(mac, nonce, HostInfo("A", "B", DeviceClass.PHONE))
        assertEquals(InfoFrame.Result.Invalid, InfoFrame.verify(mac, ByteArray(16), f))
        assertEquals(InfoFrame.Result.Invalid, InfoFrame.verify({ Hmac.sha256(ByteArray(32), it) }, nonce, f))
        val bad = f.copyOf().also { it[3] = 'Z'.code.toByte() }
        assertEquals(InfoFrame.Result.Invalid, InfoFrame.verify(mac, nonce, bad))
        assertEquals(InfoFrame.Result.Invalid, InfoFrame.verify(mac, nonce, f.copyOf(5)))
    }

    @Test fun unknownClassCodeIsUnknown() = assertEquals(DeviceClass.UNKNOWN, DeviceClass.fromCode(9))

    @Test fun classFromScreenWidth() {
        assertEquals(DeviceClass.TABLET, DeviceClass.fromSmallestWidthDp(600))
        assertEquals(DeviceClass.PHONE, DeviceClass.fromSmallestWidthDp(411))
    }

    @Test fun nameChoice() {
        assertEquals("Galaxy Tab S9 FE+", HostInfo.pickName("Galaxy Tab S9 FE+", "bt", "SM-X616B"))
        assertEquals("bt", HostInfo.pickName(" ", "bt", "SM-X616B"))
        assertEquals("SM-X616B", HostInfo.pickName(null, null, "SM-X616B"))
    }

    @Test fun commandAndCharacteristicAreAppendedWithoutClashes() {
        assertEquals(21, Command.GET_INFO.id)
        assertEquals(Command.GET_INFO, Command.fromId(21))
        val uuids = listOf(
            Protocol.NONCE_UUID, Protocol.COMMAND_UUID, Protocol.PAIR_UUID, Protocol.APPS_UUID, Protocol.OFFER_UUID, Protocol.MACROS_UUID,
            Protocol.VERIFY_UUID, Protocol.UPDATE_DATA_UUID, Protocol.NOW_PLAYING_UUID, Protocol.NOW_ART_UUID, Protocol.INFO_UUID,
        )
        assertEquals(uuids.size, uuids.toSet().size)
    }

    @Test fun commandFrameCarriesGetInfo() {
        val f = CommandFrame.encode(mac, nonce, Command.GET_INFO)
        assertEquals(Command.GET_INFO, (CommandFrame.verify(mac, nonce, f) as CommandFrame.Result.Valid).command)
    }
}

class MultiHostScanTest {
    private val a = byteArrayOf(1, 2, 3, 4)
    private val b = byteArrayOf(5, 6, 7, 8)

    @Test fun oneFilterPerPairedHost() {
        val fs = ScanPolicy.filters(listOf(a, b))
        assertEquals(2, fs.size)
        assertTrue(fs[0].data.copyOf(4).contentEquals(a))
        assertTrue(fs[1].data.copyOf(4).contentEquals(b))
    }

    @Test fun tooManyHostsFallBackToOneOpenFilter() {
        val fs = ScanPolicy.filters(List(ScanPolicy.MAX_FILTERS + 1) { byteArrayOf(it.toByte(), 0, 0, 0) })
        assertEquals(1, fs.size)
        assertTrue(fs[0].mask.all { it == 0.toByte() })
    }

    @Test fun matchFindsThePairedHostOnly() {
        val adv = Advertisement(a, false)
        assertEquals("01020304", ScanPolicy.match(adv, setOf("01020304", "05060708")))
        assertNull(ScanPolicy.match(adv, setOf("05060708")))
    }
}
