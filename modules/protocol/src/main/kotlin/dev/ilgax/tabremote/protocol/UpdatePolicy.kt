package dev.ilgax.tabremote.protocol

/** Download retry decisions of the watch updater. Pure. */
object RetryPolicy {
    const val MAX_ATTEMPTS = 3

    /** Why one download attempt did not produce a verified APK. */
    enum class AttemptFailure {
        NO_WIFI,
        /** Connect/read errors, truncated body: the same one-shot token may still be good. */
        UNREACHABLE,
        /** HTTP 404: the tablet's token is spent or expired. */
        GONE,
        /** The body arrived whole but hashed wrong; the one-shot server has already finished. */
        HASH_MISMATCH,
    }

    sealed interface Decision {
        /** Wait [delayMs], then try again; [freshOffer] = ask the tablet for a new offer (new token) first. */
        class Retry(val delayMs: Long, val freshOffer: Boolean) : Decision
        class GiveUp(val failure: UpdateReport.Failure) : Decision
    }

    /** Delay after attempt [attempt] (1-based) failed: 2 s, 6 s, 18 s, capped at 20 s. */
    fun backoffMs(attempt: Int): Long {
        var d = 2_000L
        repeat((attempt - 1).coerceAtLeast(0)) { d = minOf(d * 3, 20_000L) }
        return d
    }

    fun decide(attemptsDone: Int, failure: AttemptFailure): Decision {
        if (attemptsDone >= MAX_ATTEMPTS) return Decision.GiveUp(giveUpReason(failure))
        return Decision.Retry(backoffMs(attemptsDone), freshOffer = failure == AttemptFailure.GONE || failure == AttemptFailure.HASH_MISMATCH)
    }

    fun giveUpReason(failure: AttemptFailure): UpdateReport.Failure = when (failure) {
        AttemptFailure.NO_WIFI -> UpdateReport.Failure.NO_WIFI
        AttemptFailure.UNREACHABLE, AttemptFailure.GONE -> UpdateReport.Failure.DOWNLOAD
        AttemptFailure.HASH_MISMATCH -> UpdateReport.Failure.HASH_MISMATCH
    }
}

/** What to do with an offer that was just read from the tablet. Pure. */
object OfferPolicy {
    /** A fresh offer starts by itself while the app is visible; an older one waits for a tap. */
    const val AUTO_WINDOW_SEC = 180

    enum class Action {
        /** An update is already running or waiting for the system. */
        IGNORE_BUSY,
        /** Not newer than what is installed (and not a forced reinstall). */
        IGNORE_OLD,
        /** This exact offer already ran (and failed or finished); only a new offer from the tablet restarts it. */
        IGNORE_TRIED,
        START,
        /** Show "Update to vX" and wait for a tap. */
        SHOW,
        /** App not visible: post the "update ready" notification. */
        NOTIFY,
    }

    fun decide(
        busy: Boolean,
        installedVersion: Int,
        offer: UpdateOffer,
        visible: Boolean,
        userRequested: Boolean,
        alreadyTried: Boolean,
    ): Action = when {
        busy -> Action.IGNORE_BUSY
        offer.versionCode < installedVersion || (offer.versionCode == installedVersion && !offer.force) -> Action.IGNORE_OLD
        alreadyTried && !userRequested -> Action.IGNORE_TRIED
        userRequested -> Action.START
        !visible -> Action.NOTIFY
        offer.ageSec <= AUTO_WINDOW_SEC -> Action.START
        else -> Action.SHOW
    }
}

/** The watch updater's lifecycle as a state machine, so "busy" cannot get stuck. Pure. */
object UpdateFlow {
    enum class State { IDLE, OFFERED, DOWNLOADING, INSTALLING, CONFIRM, FAILED }

    enum class Event { OFFER, START, INSTALL_BEGIN, NEEDS_CONFIRM, SUCCESS, FAIL, DISMISS }

    /** A session the system never answered (or a confirm nobody tapped) stops counting as busy after this. */
    const val BUSY_MAX_MS = 10 * 60 * 1000L

    fun isBusy(s: State) = s == State.DOWNLOADING || s == State.INSTALLING || s == State.CONFIRM

    fun isBusy(s: State, sinceMs: Long, nowMs: Long) = isBusy(s) && nowMs - sinceMs < BUSY_MAX_MS

    fun next(s: State, e: Event): State = when (e) {
        Event.OFFER -> if (isBusy(s)) s else State.OFFERED
        Event.START -> if (isBusy(s)) s else State.DOWNLOADING
        Event.INSTALL_BEGIN -> if (s == State.DOWNLOADING) State.INSTALLING else s
        Event.NEEDS_CONFIRM -> if (s == State.INSTALLING || s == State.CONFIRM) State.CONFIRM else s
        Event.SUCCESS -> State.IDLE
        Event.FAIL -> State.FAILED
        Event.DISMISS -> if (isBusy(s)) s else State.IDLE
    }
}

/** Decides which earlier PackageInstaller sessions to abandon before creating a new one. Pure. */
object StaleSessions {
    class Ref(val id: Int, val appPackage: String?)

    /** Every session of ours (this package, or unnamed) except [keepId]; sessions naming another package are never touched. */
    fun abandonable(sessions: List<Ref>, ownPackage: String, keepId: Int? = null): List<Int> =
        sessions.filter { it.id != keepId && (it.appPackage == null || it.appPackage == ownPackage) }.map { it.id }
}
