package com.rerunguide.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

/** The 1920×1080 stage. `remoteCapture` on the stage's root Box is TalkBack's one element (TV and phone); `remoteDrags` on
 *  the window hears every drag and wheel scroll (phones); keys come through MainActivity. Screens and overlays are pure renderings of `AppModel`; they never
 *  hold focus themselves. On TV the stage fills the 16:9 screen; elsewhere it is scaled into the window (Stage), with
 *  the touch buttons and the menu hint in window space. The text-entry dialog is its own window, on TV and phone alike. */
@Composable
fun RootView() {
    val model = LocalModel.current
    val t = model.theme
    CompositionLocalProvider(LocalTheme provides t) {
        // The player's surround is black, like the picture's own bars; everything else sits on the theme background.
        val background = if (model.screen == Screen.watch) Color.Black else t.bg
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Portrait (Portrait*.kt) draws the screen; the stage stays underneath only for the player, in Now playing's
            // picture box, so turning the device keeps the same player (and its stream) going.
            val portrait = !Platform.isTV && maxHeight > maxWidth && model.portraitReady
            SideEffect { model.portrait = portrait }
            // The margins around the stage: its background, dimmed like the stage while an overlay is up.
            Stage(if (model.overlay != null) t.scrim.compositeOver(background) else background, window = { scale ->
                // Drags and wheel scrolls anywhere in the window, margins included (Swift's pans live on the window).
                Modifier.remoteDrags(scale, isActive = model.editingField == null && !portrait, onCommand = { model.press(it) },
                    onScroll = { model.scroll(it) }, playerSwipes = model.screen == Screen.watch && model.overlay == null,
                    onEdgeSwipe = { model.edgeSwipe() })
            }, videoTop = if (portrait) PORTRAIT_VIDEO_TOP else null) { width ->
                Box(Modifier.fillMaxSize()
                    .then(if (portrait) Modifier else Modifier.remoteCapture(focusDescription = model.accessibilityDescription, onCommand = { model.press(it) }))
                    .background(background)) {
                    // `width` is 1920 except on a phone wider than 16:9. The guide, overlays and toast spread across it;
                    // every other screen keeps its 1920-wide layout, centered.
                    Box(Modifier.align(Alignment.Center).size((if (model.screen == Screen.guide) width else STAGE_W).dp, STAGE_H.dp)) {
                        when (model.screen) {
                            Screen.welcome -> WelcomeScreen()
                            Screen.address -> AddressScreen()
                            Screen.link -> LinkScreen()
                            Screen.login -> LoginScreen()
                            Screen.libraries -> LibrariesScreen()
                            Screen.building -> BuildingScreen()
                            Screen.profiles -> ProfilesScreen()
                            Screen.guide -> if (!portrait) GuideScreen()
                            Screen.watch -> WatchScreen()
                            Screen.channels -> if (!portrait) ChannelsScreen()
                            Screen.settings -> if (!portrait) SettingsScreen()
                            Screen.search -> SearchScreen()
                        }
                    }
                    // A picture-in-picture window shows the picture alone (WatchScreen drops its own chrome).
                    if (!model.pip && !portrait) when (val o = model.overlay) {
                        Overlay.options -> OptionsOverlay()
                        is Overlay.info -> InfoOverlay(o.key)
                        Overlay.newChannel -> NewChannelOverlay()
                        Overlay.makeChannel -> MakeChannelOverlay()
                        null -> {}
                    }
                    if (!model.pip && !portrait) ToastView()
                    if (!model.pip && !portrait) ChannelEntryView()
                }
            }
            if (portrait) PortraitRoot()
            if (!Platform.isTV && !model.pip && !portrait) {
                MenuHint(Modifier.align(Alignment.CenterStart))
                // Centered in a phone's landscape cutout margin when it has one (Stage keeps the stage beside the cutout).
                TouchChrome(Modifier.align(Alignment.TopStart).padding(top = 16.dp, start = 9.dp))
            }
            TextEntry()
        }
    }
}
