package dev.ilgax.utils.speaker;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;

/**
 * Context that claims to be com.android.shell, so the audio service's uid/package checks pass for the Shizuku shell
 * process (same trick as the hotspot engine's). Java, because getOpPackageName() is hidden and Kotlin cannot override it.
 */
class SpeakerShellContext extends ContextWrapper {
    private static final String PKG = "com.android.shell";

    SpeakerShellContext(Context base) {
        super(base);
    }

    public String getPackageName() {
        return PKG;
    }

    public String getOpPackageName() {
        return PKG;
    }

    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.myUid()).setPackageName(PKG).build();
    }

    public Context getApplicationContext() {
        return this;
    }

    public String getAttributionTag() {
        return null;
    }
}
