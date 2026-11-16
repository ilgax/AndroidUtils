package dev.ilgax.utils.speaker;

// Runs in a Shizuku UserService (shell uid), which holds MODIFY_AUDIO_ROUTING: it hosts the dynamic AudioPolicy that
// captures audio into a loopback record and throws it away. Answers are text, "OK ..." or "ERR ...", so a failure
// arrives as a message instead of an exception that binder would flatten. Method ids are explicit because Shizuku
// calls transaction 16777115 to destroy the service (AIDL adds 1 to the id, so destroy() is 16777114).
interface ISpeakerService {
    /** Registers the mix and starts draining it. [uids] empty = every app; otherwise only those uids. Replaces a running one. */
    String startRedirect(in int[] uids) = 0;
    String stopRedirect() = 1;
    String status() = 2;

    /** Shizuku calls this when the service is unbound; the process exits and the policy goes with it. */
    void destroy() = 16777114;
}
