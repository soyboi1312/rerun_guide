// Port of android/app RootView.kt: the 1920×1080 stage, the screen, the overlay, the toast, the number box and the
// text-entry panel.
// Screens and overlays are pure renderings of `AppModel`; they never hold focus (keys come through Remote.ts, set up by
// main.tsx). TouchChrome sits outside the stage, in window space.
import type { JSX } from 'preact'
import { useEffect } from 'preact/hooks'
import { AppModel, LocalModel, useModel } from './AppModel'
import { GuideScreen } from './GuideScreen'
import {
  AddressScreen, BuildingScreen, LibrariesScreen, LinkScreen, LoginScreen, WelcomeScreen,
} from './OnboardingScreens'
import { ChannelEntryView, InfoOverlay, MakeChannelOverlay, NewChannelOverlay, OptionsOverlay, ToastView } from './Overlays'
import { SearchScreen } from './SearchScreen'
import { ChannelsScreen, ProfilesScreen, SettingsScreen } from './SettingsScreens'
import { STAGE_H, STAGE_W, Stage, at } from './Stage'
import { LocalTheme } from './Theme'
import { TextEntry, TouchChrome } from './TouchViews'
import { WatchScreen } from './WatchScreen'

/** Off screen but read by LG Audio Guidance / Samsung Voice Guide (TalkBack's one element in Kotlin). */
const visuallyHidden: JSX.CSSProperties = {
  position: 'absolute', left: 0, top: 0, width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)', whiteSpace: 'nowrap',
}

/** `model` is main.tsx's observed model (MainActivity provides LocalModel); without it, the one already provided. */
export function RootView(props: { model?: AppModel }) {
  const provided = useModel()
  const model = props.model ?? provided
  const t = model.theme
  useEffect(() => { document.body.style.setProperty('--hov', t.focusBg) }, [t])   // the pointer's hover outline (styles.css)
  // The player's surround is black, like the picture's own bars; everything else sits on the theme background.
  const background = model.screen === 'watch' ? '#000000' : t.bg
  const o = model.overlay
  return (
    <LocalModel.Provider value={model}>
      <LocalTheme.Provider value={t}>
        {/* The margins around the stage (a browser window that isn't 16:9): its background, dimmed like the stage while
            an overlay is up (the scrim over it). */}
        <Stage background={o != null ? `linear-gradient(${t.scrim}, ${t.scrim}), ${background}` : background}>
          <div style={{ ...at(0, 0, STAGE_W, STAGE_H), background }}>
            <div style={at(0, 0, STAGE_W, STAGE_H)}>{screen(model)}</div>
            {o == null ? null : o.kind === 'options' ? <OptionsOverlay /> : o.kind === 'info' ? <InfoOverlay k={o.key} />
              : o.kind === 'newChannel' ? <NewChannelOverlay /> : <MakeChannelOverlay />}
            <ToastView />
            <ChannelEntryView />
          </div>
          <TextEntry />
          <div aria-live="polite" style={visuallyHidden}>{model.accessibilityDescription}</div>
        </Stage>
        <TouchChrome />
      </LocalTheme.Provider>
    </LocalModel.Provider>
  )
}

function screen(model: AppModel): JSX.Element {
  switch (model.screen) {
    case 'welcome': return <WelcomeScreen />
    case 'address': return <AddressScreen />
    case 'link': return <LinkScreen />
    case 'login': return <LoginScreen />
    case 'libraries': return <LibrariesScreen />
    case 'building': return <BuildingScreen />
    case 'profiles': return <ProfilesScreen />
    case 'guide': return <GuideScreen />
    case 'watch': return <WatchScreen />
    case 'channels': return <ChannelsScreen />
    case 'settings': return <SettingsScreen />
    case 'search': return <SearchScreen />
  }
}
