package com.rerunguide.app

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.verticalScrollAxisRange
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The seven things a remote can say. D-pad presses, keyboard arrows and swipes all become up/down/left/right. */
@Suppress("EnumEntryName")
enum class RemoteCommand { up, down, left, right, select, play, back }

/** The remote's buttons (and a keyboard: arrows, Enter = Select, Space = Play/Pause, Esc or Delete = Back; a gamepad's
 *  A / B). Shortcuts with Ctrl, Alt or Meta stay the system's. Null for every other key. `inPlayer`: TV remotes' channel
 *  and fast-forward / rewind keys mean what the player's Up / Down (channel) and Right / Left (10 s) already do; off the
 *  player they stay unmapped (the Siri Remote has no such keys, and on the guide CH+ would point the wrong way). */
fun remoteCommand(e: KeyEvent, inPlayer: Boolean = false): RemoteCommand? {
    if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return null
    return when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> RemoteCommand.up            // keyboard arrows arrive as the D-pad's codes
        KeyEvent.KEYCODE_DPAD_DOWN -> RemoteCommand.down
        KeyEvent.KEYCODE_DPAD_LEFT -> RemoteCommand.left
        KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteCommand.right
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> RemoteCommand.select
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_SPACE -> RemoteCommand.play
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BUTTON_B -> RemoteCommand.back
        KeyEvent.KEYCODE_CHANNEL_UP -> if (inPlayer) RemoteCommand.up else null
        KeyEvent.KEYCODE_CHANNEL_DOWN -> if (inPlayer) RemoteCommand.down else null
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (inPlayer) RemoteCommand.right else null
        KeyEvent.KEYCODE_MEDIA_REWIND -> if (inPlayer) RemoteCommand.left else null
        else -> null
    }
}

/** A remote's or keyboard's number keys, 0–9 (the number row or the keypad): channel numbers on the guide and the
 *  player. Not with Ctrl, Alt or Meta. Google TV and Fire TV remotes have none; other Android TV remotes do. */
fun remoteDigit(e: KeyEvent): Int? {
    if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return null
    return when (e.keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> e.keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> e.keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }
}

/** The key side of RemoteCaptureUIView: owns the remote while `isActive` (the text-entry dialog takes the keys
 *  meanwhile). One command per press, sent on press-down, exactly like UIKit's pressesBegan: a held key never repeats
 *  and a long press is just a press (its auto-repeats are swallowed). `passBack` hands a Back press to the system
 *  (which returns to the Home screen) at the app's top level. Keys that are not a command keep their system meaning.
 *  From MainActivity: `override fun dispatchKeyEvent(e: KeyEvent) = remote.dispatchKeyEvent(e) || super.dispatchKeyEvent(e)`
 *  and `remote.install(this)` in onCreate. */
class RemoteCapture(
    private val isActive: () -> Boolean,
    private val passBack: () -> Boolean,
    private val onCommand: (RemoteCommand) -> Unit,
    /** Number keys, 0–9 (`remoteDigit`), taken like commands. */
    private val onDigit: (Int) -> Unit = {},
    /** null off the player; on it, whether playback is paused (for the separate Play / Pause keys below). */
    private val playerPaused: () -> Boolean? = { null },
) {
    private val tvOnlyKeys = setOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)

    /** Keys whose press-down was taken here; their repeats and release are taken too. Everything else is the system's. */
    private val held = mutableSetOf<Int>()

    /** True when the event was taken. */
    fun dispatchKeyEvent(e: KeyEvent): Boolean = when (e.action) {
        KeyEvent.ACTION_DOWN -> if (e.repeatCount > 0) e.keyCode in held else {
            // KEYCODE_BACK itself is left to `install`'s callback: on Android 16 the key's press-down still arrives here
            // and its release also reaches the back callback, so taking it here made one Back act twice.
            val paused = playerPaused()
            val digit = if (isActive()) remoteDigit(e) else null
            val cmd = if (isActive() && e.keyCode != KeyEvent.KEYCODE_BACK) remoteCommand(e, inPlayer = paused != null) else null
            // Remotes that send separate Play and Pause keys (HDMI-CEC, phone remote apps) get only what the key names: Play
            // while playing and Pause while paused (or off the player) are taken and dropped, not toggled.
            val noop = (e.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY && paused == false) || (e.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE && paused != false)
            // CH± / ⏩ / ⏪ off the player are taken and dropped too, rather than reaching another app's media session.
            val tvKey = e.keyCode in tvOnlyKeys && isActive()
            if (digit != null) { held += e.keyCode; onDigit(digit); true }
            else if (cmd != null && !(cmd == RemoteCommand.back && passBack())) { held += e.keyCode; if (!noop) onCommand(cmd); true }
            else if (cmd == null && tvKey) { held += e.keyCode; true } else false
        }
        KeyEvent.ACTION_UP -> held.remove(e.keyCode)
        else -> false
    }

    /** Back that never arrives as a key: the gesture-navigation swipe, and on Android 16+ (targetSdk 36) every Back.
     *  Same rule as the key: a command while active, the system's when `passBack`. */
    fun install(activity: ComponentActivity) {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isActive() && !passBack()) return onCommand(RemoteCommand.back)
                isEnabled = false
                activity.onBackPressedDispatcher.onBackPressed()   // nothing else enabled: the Activity's own Back
                isEnabled = true
            }
        })
    }
}

/** The accessibility side of RemoteCaptureUIView, on the stage's root Box: TalkBack's one element on both TV and
 *  phone. It reads `focusDescription` (announced whenever it changes) with the Swift hint, double-tap is Select,
 *  scrolling is Up / Down, and the custom actions are Left / Right / Play. */
fun Modifier.remoteCapture(focusDescription: String, onCommand: (RemoteCommand) -> Unit): Modifier = composed {
    val command by rememberUpdatedState(onCommand)
    if (focusDescription.isEmpty()) return@composed Modifier
    Modifier.semantics {
        contentDescription = focusDescription
        // accessibilityHint (Compose has none; TalkBack reads the state after the description, as VoiceOver its hint).
        stateDescription = "Scroll up or down to move, double-tap to select, Back to go back."
        liveRegion = LiveRegionMode.Polite
        onClick(label = "select") { command(RemoteCommand.select); true }
        verticalScrollAxisRange = ScrollAxisRange(value = { 1f }, maxValue = { 2f })   // always room both ways
        scrollBy { _, y -> command(if (y > 0) RemoteCommand.down else RemoteCommand.up); true }
        customActions = listOf("Previous program" to RemoteCommand.left, "Next program" to RemoteCommand.right, "Play/Pause" to RemoteCommand.play)
            .map { (name, cmd) -> CustomAccessibilityAction(name) { command(cmd); true } }
    }
}

/** The touch side of RemoteCaptureUIView, on the window (Stage's `window` layer), like Swift's pan recognizers: it
 *  hears every drag and wheel scroll anywhere in the window, margins included, whatever is under the finger (taps
 *  underneath keep working until a drag starts). No-op on TV. `pxPerPoint` is the stage's scale (window px per point).
 *  `onScroll`: Up / Down from drags and the mouse wheel (about one per row): lists follow the finger.
 *  `playerSwipes`: on the player a drag is one remote swipe instead (up = channel up, right = 10 s ahead), sent to `onCommand`.
 *  `onEdgeSwipe`: a swipe in from the left edge of the screen (where the system's Back gesture doesn't take it). */
fun Modifier.remoteDrags(
    pxPerPoint: Float,
    isActive: Boolean = true,
    onCommand: (RemoteCommand) -> Unit,
    onScroll: (RemoteCommand) -> Unit = {},
    playerSwipes: Boolean = false,
    onEdgeSwipe: () -> Unit = {},
): Modifier = composed {
    if (Platform.isTV) return@composed Modifier
    val active by rememberUpdatedState(isActive)
    val command by rememberUpdatedState(onCommand)
    val scroll by rememberUpdatedState(onScroll)
    val swipes by rememberUpdatedState(playerSwipes)
    val edgeSwipe by rememberUpdatedState(onEdgeSwipe)
    val scale by rememberUpdatedState(pxPerPoint)
    val edgePx = 20 * LocalContext.current.resources.displayMetrics.density   // UIScreenEdgePan's ~20 pt, in screen dp

    Modifier.pointerInput(Unit) {
        /** Stage points. */
        fun stage(p: Offset) = p / scale
        fun step(sent: Int, to: Int): Int {
            var n = sent
            while (n < to) { scroll(RemoteCommand.down); n++ }
            while (n > to) { scroll(RemoteCommand.up); n-- }
            return n
        }
        var wheel = 0f
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                // Mouse wheel / trackpad: content follows the fingers, scrolling the content up presses Down.
                // ponytail: one press per wheel notch (Android reports notches, not points; Swift: per 80 stage pt).
                if (ev.type == PointerEventType.Scroll) {
                    if (!active || scale <= 0) continue
                    wheel += ev.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                    while (wheel >= 1) { scroll(RemoteCommand.down); wheel -= 1 }
                    while (wheel <= -1) { scroll(RemoteCommand.up); wheel += 1 }
                    continue
                }
                val down = ev.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                val start = down.position
                val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, start) }
                val fromEdge = start.x < edgePx
                var mode = 0   // 0 not yet a drag (taps underneath still count), 1 the list drag, 2 the edge swipe
                var sent = 0
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    val ok = active && scale > 0
                    val (dx, dy) = stage(c.position - start)
                    if (!c.pressed) {   // ended
                        if (mode == 1 && ok) {
                            if (swipes) {
                                if (max(abs(dx), abs(dy)) > 60) command(if (abs(dy) > abs(dx)) (if (dy < 0) RemoteCommand.up else RemoteCommand.down) else (if (dx > 0) RemoteCommand.right else RemoteCommand.left))
                            } else if (sent != 0 || abs(dy) > abs(dx)) {
                                val vy = stage(Offset(0f, velocity.calculateVelocity().y)).y
                                val coast = (-(dy + vy / 4) / 100).toInt()   // where a quarter second more would land
                                sent = step(sent, max(sent - 6, min(sent + 6, coast)))
                            } else if (abs(dx) > 60) {
                                command(if (dx < 0) RemoteCommand.right else RemoteCommand.left)
                            }
                        }
                        break
                    }
                    velocity.addPosition(c.uptimeMillis, c.position)
                    if (mode == 0 && (c.position - start).getDistance() > viewConfiguration.touchSlop) {
                        // A swipe in from the left edge is the edge swipe, not a list drag (dragPan requires edgePan to fail).
                        mode = if (fromEdge && dx > abs(dy)) 2 else 1
                        if (mode == 2 && ok) edgeSwipe()   // once, as soon as it is recognized
                    }
                    if (mode == 0) continue
                    c.consume()   // a drag, so the tap under the finger fails (as SwiftUI's taps do)
                    if (mode == 1 && ok && !swipes && abs(dy) > abs(dx)) sent = step(sent, (-dy / 100).toInt())
                }
            }
        }
    }
}
