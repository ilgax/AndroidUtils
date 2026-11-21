package dev.ilgax.utils.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

object Haptics {
    fun success(context: Context) = vibrate(context, VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))

    /** One light tick, for steps of the rotary input and taps on the player. */
    fun tick(context: Context) = vibrate(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))

    fun failure(context: Context) = vibrate(context, VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))

    private fun vibrate(context: Context, effect: VibrationEffect) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(effect)
    }
}
