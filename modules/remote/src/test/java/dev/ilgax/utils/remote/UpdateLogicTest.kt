package dev.ilgax.utils.remote

import dev.ilgax.tabremote.protocol.IntegrityVerdict
import dev.ilgax.tabremote.protocol.UpdateReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private fun c(iface: String, ip: String) = UpdateAddresses.Candidate(iface, ip)

    @Test fun addressesAreOrderedHotspotThenWifiThenOthersAndCapped() {
        val picked = UpdateAddresses.pick(
            listOf(
                c("rmnet0", "10.20.30.40"), c("wlan0", "192.168.1.20"), c("lo", "127.0.0.1"),
                c("swlan0", "192.168.43.1"), c("wlan0", "192.168.1.20"), c("eth0", "172.16.0.9"),
                c("dummy0", "169.254.3.4"), c("rndis0", "192.168.42.129"), c("p2p0", "10.1.1.1"),
            ),
        )
        assertEquals(listOf("192.168.43.1", "192.168.1.20", "10.20.30.40", "172.16.0.9"), picked)
    }

    @Test fun unusableAddressesAreDropped() {
        assertFalse(UpdateAddresses.usable("127.0.0.1"))
        assertFalse(UpdateAddresses.usable("0.0.0.0"))
        assertFalse(UpdateAddresses.usable("169.254.1.1"))
        assertFalse(UpdateAddresses.usable("224.0.0.1"))
        assertFalse(UpdateAddresses.usable("fe80::1"))
        assertTrue(UpdateAddresses.usable("192.168.43.1"))
    }

    private fun snap(
        ended: UpdateServer.Reason? = null, served: Long = 0, report: UpdateReport? = null, deadline: Long = 600_000,
    ) = UpdateSnapshot(ended, served, 1000, deadline, report)

    @Test fun stateMapping() {
        assertEquals(WatchUpdateState.Idle, UpdateHost.stateOf(null, 0))
        assertEquals(WatchUpdateState.Waiting(590), UpdateHost.stateOf(snap(), 10_000))
        assertEquals(WatchUpdateState.Downloading(25), UpdateHost.stateOf(snap(served = 250), 0))
        assertEquals(
            WatchUpdateState.Downloading(0),
            UpdateHost.stateOf(snap(report = UpdateReport(UpdateReport.Phase.DOWNLOADING, 0)), 0),
        )
        assertEquals(WatchUpdateState.Installing, UpdateHost.stateOf(snap(ended = UpdateServer.Reason.COMPLETED, served = 1000), 0))
        assertEquals(
            WatchUpdateState.Installing,
            UpdateHost.stateOf(snap(served = 1000, report = UpdateReport(UpdateReport.Phase.INSTALLING, 0)), 0),
        )
        assertEquals(WatchUpdateState.TimedOut, UpdateHost.stateOf(snap(ended = UpdateServer.Reason.TIMEOUT), 0))
        assertEquals(WatchUpdateState.Idle, UpdateHost.stateOf(snap(ended = UpdateServer.Reason.STOPPED), 0))
        assertEquals(
            WatchUpdateState.Failed(UpdateReport.Failure.HASH_MISMATCH),
            UpdateHost.stateOf(snap(served = 1000, report = UpdateReport.failed(UpdateReport.Failure.HASH_MISMATCH)), 0),
        )
    }

    @Test fun versionStates() {
        fun ui(reported: Int?, update: WatchUpdateState = WatchUpdateState.Idle) = WatchAppUi("0.2.1", 3, reported, update)
        assertTrue(ui(2).updateAvailable)
        assertFalse(ui(3).updateAvailable)
        assertTrue(ui(3).upToDate)
        assertFalse(ui(null).updateAvailable)
        assertFalse(ui(null).upToDate)
        assertEquals(R.string.remote_app_st_available, ui(2).statusRes())
        assertEquals(R.string.remote_app_st_current, ui(3).statusRes())
        assertEquals(R.string.remote_app_st_unknown, ui(null).statusRes())
        assertEquals(R.string.remote_app_st_waiting, ui(2, WatchUpdateState.Waiting(100)).statusRes())
        assertTrue(ui(2, WatchUpdateState.Waiting(100)).active)
        assertFalse(ui(2, WatchUpdateState.TimedOut).active)
        assertEquals(
            R.string.remote_app_st_hash,
            ui(2, WatchUpdateState.Failed(UpdateReport.Failure.HASH_MISMATCH)).statusRes(),
        )
    }

    @Test fun retryingStateAndFreshToken() {
        val retry = UpdateReport(UpdateReport.Phase.RETRYING, 2)
        assertEquals(WatchUpdateState.Retrying(2), UpdateHost.stateOf(snap(report = retry), 0))
        assertEquals(
            WatchUpdateState.Retrying(1),
            UpdateHost.stateOf(snap(ended = UpdateServer.Reason.COMPLETED, report = UpdateReport(UpdateReport.Phase.RETRYING, 1)), 0),
        )
        assertEquals(WatchUpdateState.TimedOut, UpdateHost.stateOf(snap(ended = UpdateServer.Reason.TIMEOUT, report = retry), 0))
        // A finished one-shot server is only replaced when the watch says it is retrying, inside the user's window.
        assertTrue(UpdateHost.needsFreshToken(UpdateServer.Reason.COMPLETED, retry, 1_000))
        assertFalse(UpdateHost.needsFreshToken(UpdateServer.Reason.COMPLETED, retry, UpdateServer.DEFAULT_TIMEOUT_MS))
        assertFalse(UpdateHost.needsFreshToken(null, retry, 0))
        assertFalse(UpdateHost.needsFreshToken(UpdateServer.Reason.COMPLETED, UpdateReport.failed(UpdateReport.Failure.DOWNLOAD), 0))
        assertFalse(UpdateHost.needsFreshToken(UpdateServer.Reason.COMPLETED, null, 0))
    }

    @Test fun updatedToAndReinstall() {
        fun ui(reported: Int?, updatedTo: Int?, integrity: IntegrityResult? = null, update: WatchUpdateState = WatchUpdateState.Idle) =
            WatchAppUi("0.4.1", 6, reported, update, integrity, updatedTo)
        assertTrue(ui(6, 6).justUpdated)
        assertEquals(R.string.remote_app_st_updated, ui(6, 6).statusRes())
        assertFalse(ui(6, null).justUpdated)
        assertFalse(ui(5, 6).justUpdated)
        assertFalse(ui(6, 6, update = WatchUpdateState.Waiting(5)).justUpdated)
        assertFalse(ui(6, null, IntegrityResult(IntegrityVerdict.VERIFIED, 6)).canReinstall)
        for (v in listOf(IntegrityVerdict.CORRUPTED, IntegrityVerdict.DIFFERENT_BUILD, IntegrityVerdict.WRONG_KEY)) {
            assertTrue(ui(6, null, IntegrityResult(v, 6)).canReinstall)
        }
        assertFalse(ui(6, null).canReinstall)
        assertEquals(R.string.remote_app_integrity_ok, IntegrityVerdict.VERIFIED.statusRes())
        assertEquals(R.string.remote_app_integrity_corrupted, IntegrityVerdict.CORRUPTED.statusRes())
        assertEquals(R.string.remote_app_integrity_key, IntegrityVerdict.WRONG_KEY.statusRes())
        assertEquals(R.string.remote_app_integrity_different, IntegrityVerdict.DIFFERENT_BUILD.statusRes())
        assertEquals(R.string.remote_app_st_cert, ui(5, null, update = WatchUpdateState.Failed(UpdateReport.Failure.CERT_MISMATCH)).statusRes())
        assertEquals(R.string.remote_app_st_retrying, ui(5, null, update = WatchUpdateState.Retrying(1)).statusRes())
        assertTrue(ui(5, null, update = WatchUpdateState.Retrying(1)).active)
    }

    @Test fun clientLockServesOnlyTheFirstAddressUntilItGivesUp() {
        val lock = ClientLock()
        assertTrue(lock.admit("192.168.43.2"))
        assertTrue(lock.admit("192.168.43.2"))
        assertFalse(lock.admit("192.168.43.9"))
        lock.release("192.168.43.9") // not the owner: no effect
        assertFalse(lock.admit("192.168.43.9"))
        lock.release("192.168.43.2")
        assertTrue(lock.admit("192.168.43.9"))
    }
}
