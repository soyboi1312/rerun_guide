package com.rerunguide.app

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role

/** Phones, tablets, Google TV and Fire TV from one APK (App/Touch.swift `Platform`). The app has one input model, the remote's
 *  seven commands (`RemoteCommand`, dispatched by `AppModel.press`), so touch maps onto it instead of adding controls:
 *  a tap focuses what it hits and presses Select; drags arrive through `Modifier.remoteDrags`. Every modifier here
 *  is a no-op on TV. Call [init] from MainActivity.onCreate before setContent. */
object Platform {
    var isTV = false
        private set

    /** Amazon's Fire TV (always a TV): it names the remote's center button Select, and channel 99 is the GIF there. */
    var isFireTV = false
        private set

    /** What the copy calls this device. */
    var device = "phone"
        private set

    /** How the copy says "act on it": the remote's OK button, a finger. */
    val verb: String get() = if (isTV) "Press" else "Tap"

    /** The remote's center button as the copy names it: the Siri Remote's, Fire TV's and a tap's Select, Google TV's OK. */
    val select: String get() = if (isTV && !isFireTV) "OK" else "Select"

    /** `forceTV`: debug builds launched with `--ez forceTV true` act as a TV on a phone/TV-profile emulator image;
     *  `forceFireTV` (`--ez forceFireTV true`) as a Fire TV, since there is no Fire OS emulator. */
    fun init(context: Context, forceTV: Boolean = false, forceFireTV: Boolean = false) {
        val ui = context.getSystemService(UiModeManager::class.java)
        // Amazon's documented check; it covers its partner TVs too, where Build.MODEL isn't AFT*.
        isFireTV = forceFireTV || context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
        isTV = forceTV || isFireTV || ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        device = when {
            isFireTV -> "Fire TV"
            isTV -> "Google TV"
            context.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "phone"
        }
    }
}

/** A tap on this element runs `action`; TalkBack reads it as one button (its texts combined). No ripple. */
fun Modifier.onTouch(action: () -> Unit): Modifier =
    if (Platform.isTV) this
    else clickable(interactionSource = null, indication = null, role = Role.Button, onClick = action)

/** A tap on empty space (a scrim, the picture). Elements on top keep their own taps. */
fun Modifier.backgroundTap(action: () -> Unit): Modifier =
    if (Platform.isTV) this
    else composed {
        val current by rememberUpdatedState(action)
        pointerInput(Unit) { detectTapGestures { current() } }
    }
