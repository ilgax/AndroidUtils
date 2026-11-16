package dev.ilgax.tabremote.protocol

import dev.ilgax.tabremote.protocol.OfferPolicy.Action
import dev.ilgax.tabremote.protocol.RetryPolicy.AttemptFailure
import dev.ilgax.tabremote.protocol.RetryPolicy.Decision
import dev.ilgax.tabremote.protocol.UpdateFlow.Event
import dev.ilgax.tabremote.protocol.UpdateFlow.State
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    // region retry and backoff

    @Test fun backoffGrowsAndIsCapped() {
        assertEquals(2_000, RetryPolicy.backoffMs(1))
        assertEquals(6_000, RetryPolicy.backoffMs(2))
        assertEquals(18_000, RetryPolicy.backoffMs(3))
        assertEquals(20_000, RetryPolicy.backoffMs(4))
        assertEquals(20_000, RetryPolicy.backoffMs(50))
        assertEquals(2_000, RetryPolicy.backoffMs(0))
    }

    @Test fun threeAttemptsThenGiveUp() {
        assertTrue(RetryPolicy.decide(1, AttemptFailure.UNREACHABLE) is Decision.Retry)
        assertTrue(RetryPolicy.decide(2, AttemptFailure.UNREACHABLE) is Decision.Retry)
        val last = RetryPolicy.decide(3, AttemptFailure.UNREACHABLE) as Decision.GiveUp
        assertEquals(UpdateReport.Failure.DOWNLOAD, last.failure)
    }

    @Test fun freshOfferOnlyWhenTheTokenIsSpent() {
        fun fresh(f: AttemptFailure) = (RetryPolicy.decide(1, f) as Decision.Retry).freshOffer
        assertFalse(fresh(AttemptFailure.NO_WIFI))
        assertFalse(fresh(AttemptFailure.UNREACHABLE))
        assertTrue(fresh(AttemptFailure.GONE))
        assertTrue(fresh(AttemptFailure.HASH_MISMATCH))
    }

    @Test fun giveUpReasons() {
        fun reason(f: AttemptFailure) = (RetryPolicy.decide(RetryPolicy.MAX_ATTEMPTS, f) as Decision.GiveUp).failure
        assertEquals(UpdateReport.Failure.NO_WIFI, reason(AttemptFailure.NO_WIFI))
        assertEquals(UpdateReport.Failure.DOWNLOAD, reason(AttemptFailure.GONE))
        assertEquals(UpdateReport.Failure.HASH_MISMATCH, reason(AttemptFailure.HASH_MISMATCH))
    }

    @Test fun retryDelayFollowsBackoff() {
        assertEquals(6_000, (RetryPolicy.decide(2, AttemptFailure.NO_WIFI) as Decision.Retry).delayMs)
    }

    // endregion

    // region offer handling

    private fun offer(version: Int = 6, age: Int = 5, force: Boolean = false) =
        UpdateOffer(version, 4000, ByteArray(16), ByteArray(32), age, listOf("10.0.0.2"), force)

    private fun decide(
        o: UpdateOffer = offer(), busy: Boolean = false, installed: Int = 5, visible: Boolean = true,
        tapped: Boolean = false, tried: Boolean = false,
    ) = OfferPolicy.decide(busy, installed, o, visible, tapped, tried)

    @Test fun freshOfferStartsWhileVisible() = assertEquals(Action.START, decide())

    @Test fun oldOfferWaitsForATap() = assertEquals(Action.SHOW, decide(offer(age = OfferPolicy.AUTO_WINDOW_SEC + 1)))

    @Test fun offerWhileClosedNotifies() = assertEquals(Action.NOTIFY, decide(visible = false))

    @Test fun notificationTapStartsEvenAnOldOffer() {
        assertEquals(Action.START, decide(offer(age = 9_000), visible = true, tapped = true))
        assertEquals(Action.START, decide(visible = false, tapped = true))
    }

    @Test fun secondOfferWhileBusyIsIgnored() {
        assertEquals(Action.IGNORE_BUSY, decide(busy = true))
        assertEquals(Action.IGNORE_BUSY, decide(busy = true, tapped = true))
    }

    @Test fun notNewerIsIgnoredUnlessForced() {
        assertEquals(Action.IGNORE_OLD, decide(offer(version = 5)))
        assertEquals(Action.IGNORE_OLD, decide(offer(version = 4)))
        assertEquals(Action.START, decide(offer(version = 5, force = true)))
        assertEquals(Action.IGNORE_OLD, decide(offer(version = 4, force = true)))
    }

    @Test fun anOfferThatAlreadyRanIsNotRestartedByPolling() {
        assertEquals(Action.IGNORE_TRIED, decide(tried = true))
        assertEquals(Action.IGNORE_TRIED, decide(tried = true, visible = false))
        assertEquals(Action.START, decide(tried = true, tapped = true))
    }

    // endregion

    // region lifecycle

    @Test fun happyPath() {
        var s = State.IDLE
        for ((e, want) in listOf(
            Event.OFFER to State.OFFERED, Event.START to State.DOWNLOADING, Event.INSTALL_BEGIN to State.INSTALLING,
            Event.NEEDS_CONFIRM to State.CONFIRM, Event.SUCCESS to State.IDLE,
        )) {
            s = UpdateFlow.next(s, e)
            assertEquals(want, s)
        }
    }

    @Test fun offersAndStartsAreIgnoredWhileBusy() {
        for (busy in listOf(State.DOWNLOADING, State.INSTALLING, State.CONFIRM)) {
            assertTrue(UpdateFlow.isBusy(busy))
            assertEquals(busy, UpdateFlow.next(busy, Event.OFFER))
            assertEquals(busy, UpdateFlow.next(busy, Event.START))
            assertEquals(busy, UpdateFlow.next(busy, Event.DISMISS))
        }
    }

    @Test fun failureFreesTheUpdaterAndAllowsARestart() {
        for (s in State.entries) {
            val failed = UpdateFlow.next(s, Event.FAIL)
            assertEquals(State.FAILED, failed)
            assertFalse(UpdateFlow.isBusy(failed))
            assertEquals(State.DOWNLOADING, UpdateFlow.next(failed, Event.START))
        }
        assertEquals(State.IDLE, UpdateFlow.next(State.FAILED, Event.DISMISS))
    }

    @Test fun installAndConfirmOnlyFromTheRightState() {
        assertEquals(State.IDLE, UpdateFlow.next(State.IDLE, Event.INSTALL_BEGIN))
        assertEquals(State.OFFERED, UpdateFlow.next(State.OFFERED, Event.NEEDS_CONFIRM))
        assertEquals(State.CONFIRM, UpdateFlow.next(State.CONFIRM, Event.NEEDS_CONFIRM))
    }

    @Test fun aWedgedSessionStopsCountingAsBusy() {
        assertTrue(UpdateFlow.isBusy(State.CONFIRM, sinceMs = 0, nowMs = UpdateFlow.BUSY_MAX_MS - 1))
        assertFalse(UpdateFlow.isBusy(State.CONFIRM, sinceMs = 0, nowMs = UpdateFlow.BUSY_MAX_MS))
        assertFalse(UpdateFlow.isBusy(State.FAILED, sinceMs = 0, nowMs = 1))
    }

    // endregion

    // region stale sessions

    private val own = "dev.ilgax.tabremote.wear"

    @Test fun abandonsEveryEarlierSessionOfOurs() {
        val ids = StaleSessions.abandonable(
            listOf(StaleSessions.Ref(1, own), StaleSessions.Ref(2, null), StaleSessions.Ref(3, own)), own,
        )
        assertEquals(listOf(1, 2, 3), ids)
    }

    @Test fun keepsTheLiveSessionAndForeignOnes() {
        val ids = StaleSessions.abandonable(
            listOf(StaleSessions.Ref(1, own), StaleSessions.Ref(2, "com.other"), StaleSessions.Ref(3, own)), own, keepId = 3,
        )
        assertEquals(listOf(1), ids)
    }

    @Test fun nothingToAbandon() = assertTrue(StaleSessions.abandonable(emptyList(), own).isEmpty())

    // endregion
}
