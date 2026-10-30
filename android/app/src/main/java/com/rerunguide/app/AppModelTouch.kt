package com.rerunguide.app

import androidx.compose.ui.Modifier

/* Phone and tablet input (the AppModel half of Touch.swift). The app has one input model, the remote's seven commands
 * (`RemoteCommand`, dispatched by `AppModel.press`), so touch maps onto it instead of adding controls: a tap focuses what
 * it hits and presses Select; drags arrive through `Modifier.remoteDrags`. Every modifier here is a no-op on TV. */

/** The remote's way of acting on a list item: focus it (`focus` sets the screen's focus state), then Select. */
fun Modifier.tapSelects(model: AppModel, focus: () -> Unit = {}): Modifier = onTouch { focus(); model.press(RemoteCommand.select) }

/** Up / Down from drags and the scroll wheel; never on the player, where they would flip channels. On Channels they
 *  scroll the channel list even with the editor's fields focused (seven rows that never scroll), where the remote's
 *  Up / Down would move between fields. */
fun AppModel.scroll(cmd: RemoteCommand) {
    if (screen == Screen.watch && overlay == null && trackMenu == null) return   // the track list scrolls
    if (screen == Screen.channels && overlay == null) channelColumn = 0
    press(cmd)
}

/** A tap on the picture: the banner's buttons (the remote's Select), or put the banner away. Never skips the spot:
 *  only its Skip chip does. */
fun AppModel.watchTap() {
    closeEntry()   // a tap isn't the number box's OK
    if (bannerVisible && bannerFocus >= 0) hideBanner() else if (SpotOnAir.started == null) press(RemoteCommand.select)
}

/** A swipe in from the left edge: the guide's menu (Options), elsewhere Back. */
fun AppModel.edgeSwipe() {
    if (overlay != null || editingField != null) return
    if (screen == Screen.guide) { if (attracting) attractReset() else openOptions() }
    else if (showsTouchBack) touchBack()
}

/** The guide's menu button and first-launch hint: the menu (Options) slides in from the left. Portrait has its tab bar. */
val AppModel.showsMenuButton: Boolean get() = screen == Screen.guide && overlay == null && editingField == null && !attracting && !portrait

/** The screens Portrait*.kt draws when the window is taller than wide. The rest keep the stage: a phone turns to
 *  landscape for them (MainActivity), a tablet letterboxes it. */
val AppModel.portraitReady: Boolean
    get() = screen in listOf(Screen.guide, Screen.watch, Screen.channels, Screen.settings) && overlay != Overlay.options && !fullScreen

/** The on-screen Back: the remote's Back, except that from the player it always goes to the guide. */
fun AppModel.touchBack() {
    if (screen == Screen.watch && fullScreen) { fullScreen = false; return }   // back to portrait Now playing (which puts the banner away)
    val pb = playback
    if (screen == Screen.watch && overlay == null && trackMenu == null && pb != null) toGuide(pb.channelID) else press(RemoteCommand.back)
}

/** Not on the top-level screens (the guide has its menu button instead); the player shows Back with its banner. */
val AppModel.showsTouchBack: Boolean
    get() {
        if (overlay != null || editingField != null || portrait) return false
        return when (screen) {
            Screen.welcome, Screen.guide -> false
            Screen.watch -> bannerVisible
            else -> true
        }
    }
