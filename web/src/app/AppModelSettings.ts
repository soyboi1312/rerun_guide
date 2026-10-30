// Port of android/app AppModelSettings.kt: Profiles, Settings and the Channel editor (the prototype's k_profiles,
// k_settings/setRows, k_channels/commitNum/chIdx). The web is always a TV, so only the TV copy is here.
import { toIntOrNull, uuid } from '../core/Lang'
import { Channel, MediaTrack, PlayOrder, ServerKind, TuneMode } from '../core/Models'
import { AppModel, Overlay, Profile } from './AppModel'
import { Platform } from './Platform'
import type { RemoteCommand } from './Remote'
import { ThemeID } from './Theme'

/** One entry of the Settings list (the prototype's setRows). `key == null` is a section header. */
export class SettingsRow {
  constructor(readonly key: SettingsRow.Key | null, readonly label: string, readonly value = '', readonly desc = '') {}
  get id(): string { return this.key ?? this.label }
  static head(label: string): SettingsRow { return new SettingsRow(null, label) }
  static readonly Key = {
    entries: ['theme', 'crt', 'auto', 'idle', 'attract', 'tune', 'breaks', 'idents', 'quality', 'audio', 'subs', 'subLang',
      'profile', 'server', 'rebuild', 'signout', 'about', 'saver', 'spots'] as readonly SettingsRow.Key[],
  }
}
export namespace SettingsRow {
  export type Key = 'theme' | 'crt' | 'auto' | 'idle' | 'attract' | 'tune' | 'breaks' | 'idents' | 'quality' | 'audio' | 'subs'
    | 'subLang' | 'profile' | 'server' | 'rebuild' | 'signout' | 'about' | 'saver' | 'spots'
}

/** The channel editor's detail fields, in order (the prototype's F array). The Swift `name` case is `title`;
 *  `ChannelField(rawValue: i)` → `ChannelField.entries[i]`, `rawValue` → `ChannelField.entries.indexOf(f)`. */
export type ChannelField = 'title' | 'number' | 'order' | 'breaks' | 'tune' | 'show' | 'delete'
export const ChannelField = {
  entries: ['title', 'number', 'order', 'breaks', 'tune', 'show', 'delete'] as readonly ChannelField[],
}

declare module './AppModel' {
  namespace AppModel {
    /** "Join in progress" / "From the beginning", or "Default · …" when the channel follows the app setting. */
    function tuneLabel(mode: TuneMode | null, defaultMode: TuneMode): string
    /** Settings › About. */
    const aboutText: string
    const version: string
    /** Avatar colors for new profiles, rotating by profile count. */
    const profilePalette: readonly string[]
    /** The languages the Playback rows cycle through (ISO 639-2/B, as Plex and Emby report them). */
    const languages: readonly string[]
  }
  interface AppModel {
    /** Actions in the row Play/Pause opens under a tile: Rename, and Delete while another profile remains. */
    readonly profileActions: string[]
    pressProfiles(cmd: RemoteCommand): void
    commitProfileName(index: number, name: string): void
    readonly settingsRows: SettingsRow[]
    pressSettings(cmd: RemoteCommand): void
    /** Position in the editor list: 0 = "New channel", i + 1 = channels[i] (the prototype's chIdx). */
    readonly channelIndex: number
    readonly selectedChannel: Channel | null
    pressChannels(cmd: RemoteCommand): void
    commitNumber(): void
  }
}

Object.assign(AppModel, {
  tuneLabel(mode: TuneMode | null, defaultMode: TuneMode): string {
    const name = (m: TuneMode) => m === 'live' ? 'Join in progress' : 'From the beginning'
    return mode != null ? name(mode) : `Default · ${name(defaultMode)}`
  },
  /** The beacon is Soyboi's other project (soyboi.tech). Ten lines at most: the pane is 600 high. */
  aboutText: 'From Soyboi LLC in San Diego, makers of the beacon: a pocket counter-surveillance device that spots license-plate ' +
    'readers, body cams and drones. soyboi.tech\n' +
    'Rerun Guide talks to your media server, Plex or Emby if you sign in with them, and Apple for the Sample station. ' +
    'No analytics, no tracking, and nothing about you is sent to us.\n' +
    'Privacy: rerunguide.tv/privacy · Support: rerunguide.tv/support',
  /** BuildConfig's "VERSION_NAME (VERSION_CODE)"; keep in step with appinfo.json (LG) and config.xml (Samsung). */
  version: '1.0 (1)',
  /** The prototype's seed colors + its "Guest" purple. */
  profilePalette: ['#f0a33a', '#7d97ff', '#7fd39a', '#c9a0ff', '#ff8a80'],
  languages: ['eng', 'spa', 'fre', 'ger', 'ita', 'por', 'jpn', 'kor', 'chi'],
})

function nextIn<T>(list: readonly T[], after: T): T {
  return list[(list.indexOf(after) + 1) % list.length]
}

// MARK: Profiles

Object.defineProperty(AppModel.prototype, 'profileActions', {
  get(this: AppModel) { return this.persisted.profiles.length > 1 ? ['Rename', 'Delete'] : ['Rename'] },
})

AppModel.prototype.pressProfiles = function (this: AppModel, cmd: RemoteCommand) {
  const profiles = this.persisted.profiles
  const m = this.profileMenu
  if (m != null) {
    switch (cmd) {
      case 'left': case 'right':
        this.profileMenuIndex = Math.max(0, Math.min(this.profileActions.length - 1, this.profileMenuIndex + (cmd === 'right' ? 1 : -1)))
        break
      case 'back': this.profileMenu = null; break
      case 'select': {
        this.profileMenu = null
        if (this.profileMenuIndex === 0) { this.editingField = 'profileName'; return }
        const p = profiles[m]
        this.persisted = { ...this.persisted, profiles: this.persisted.profiles.filter((_, i) => i !== m) }
        if (p.id === this.persisted.currentProfileID) { this.switchProfile(this.persisted.profiles[0].id); this.guideReset() } else this.save()
        this.focusIndex = Math.min(this.focusIndex, this.persisted.profiles.length)
        this.toast(`${p.name} is gone.`)
        break
      }
    }
    return
  }
  if (this.step(cmd, profiles.length + (profiles.length < 5 ? 1 : 0), true)) return
  if (cmd === 'back') { this.go('guide'); return }
  if (cmd === 'play' && this.focusIndex < profiles.length) { this.profileMenu = this.focusIndex; this.profileMenuIndex = 0; return }
  if (cmd !== 'select') return
  if (this.focusIndex >= profiles.length) {
    const palette = AppModel.profilePalette
    this.persisted = {
      ...this.persisted,
      profiles: profiles.concat(new Profile(uuid(), `Profile ${profiles.length + 1}`, palette[profiles.length % palette.length], 'LATE', new Set())),
    }
    this.save(); this.editingField = 'profileName'; return   // focusIndex already points at the new tile
  }
  const p = profiles[this.focusIndex]
  // Switching to the profile already in use would do nothing, so Select there offers Rename / Delete (as Play/Pause does).
  if (p.id === this.persisted.currentProfileID) { this.profileMenu = this.focusIndex; this.profileMenuIndex = 0; return }
  this.switchProfile(p.id); this.guideReset(); this.go('guide')
  this.toast(`Hi, ${p.name}.`)
}

/** Commit the rename typed on the Profiles screen; an empty name keeps the old one. */
AppModel.prototype.commitProfileName = function (this: AppModel, index: number, name: string) {
  try {
    const n = name.replace(/^[\t\p{Zs}]+|[\t\p{Zs}]+$/gu, '')   // CharacterSet.whitespaces: tabs and space separators
    const profiles = this.persisted.profiles
    if (index < 0 || index >= profiles.length || n === '') return
    const next = profiles.slice()
    next[index] = next[index].copy({ name: n })
    this.persisted = { ...this.persisted, profiles: next }; this.save()
  } finally { this.editingField = null }
}

// MARK: Settings

Object.defineProperty(AppModel.prototype, 'settingsRows', {
  get(this: AppModel): SettingsRow[] {
    const on = (b: boolean) => b ? 'On' : 'Off'
    const s = this.settings, p = this.profile
    const films = this.library.filter(it => it.kind === 'movie').length
    const shows = new Set<string>()
    for (const it of this.library) if (it.kind === 'episode') shows.add(it.seriesID ?? it.seriesTitle ?? it.title)
    const service = this.server != null ? ServerKind.displayName(this.server.kind) : 'your server'
    const fmt = new Intl.NumberFormat()
    const saver = s.screensaver === 'logo' ? 'Bouncing logo' : s.screensaver === 'soyboi' ? 'Bouncing Soyboi' : 'Storefront wall'
    const subs = s.subtitleMode === 'off' ? 'Off' : s.subtitleMode === 'on' ? 'On' : 'Forced only'
    const Row = (key: SettingsRow.Key, label: string, value = '', desc = '') => new SettingsRow(key, label, value, desc)
    return [
      SettingsRow.head('Appearance'),
      Row('theme', 'Theme', ThemeID.displayName(p.theme), 'Late Show is warm and rounded. Midnight Movie is near-black with neon red, in a condensed face. Each profile keeps its own.'),
      Row('crt', 'Old-TV screen lines', on(s.crtLines), 'A faint CRT texture over the guide’s preview window. The show itself is never touched.'),
      SettingsRow.head('Guide'),
      Row('auto', 'Scroll listings on their own', on(s.autoScroll), 'When nobody’s touching the remote, the guide drifts through the channels like the old preview channel.'),
      Row('idle', 'Start drifting again after', `${s.idleSeconds} seconds`, 'How long the guide waits after your last button press before it starts scrolling again.'),
      Row('attract', 'Attract mode after', s.attractMinutes === 0 ? 'Off' : `${s.attractMinutes} min`, 'Leave the guide alone this long and it settles into a slow storefront wall: a bigger preview, a big clock, listings drifting by. Any button wakes it.'),
      Row('saver', 'Screensaver', saver, 'What attract mode shows. The storefront wall keeps the listings drifting under a big clock. The bouncing logo is the Rerun Guide logo drifting around the screen the way the DVD logo used to; Bouncing Soyboi does the same with the soyboi.tech mascot. Any button wakes the guide.'),
      SettingsRow.head('Playback'),
      Row('tune', 'When you tune in', s.tuneMode === 'live' ? 'Join in progress' : 'Start from the beginning', 'Join in progress keeps everyone on the same schedule. Press Play/Pause on any program to start it over either way.'),
      Row('breaks', 'Trailers between programs', on(s.trailerBreaks), 'Fills the gap between programs with trailers from your library. Off shows a station card instead.'),
      Row('spots', 'Messages from Soyboi', on(s.houseSpots), 'One in 15 trailer breaks opens with a 30-second spot for the beacon, Soyboi’s other project. It’s how Rerun Guide stays free. Turn it off and the trailers start right away. To report a spot, write to support@soyboi.tech.'),
      Row('idents', 'Station idents', on(s.stationIdents), 'A quick channel card and a two-note sting when you change channels, and a ‘We’ll be right back’ card before trailer breaks.'),
      Row('quality', 'Streaming quality', s.maxBitrateKbps === 0 ? 'Original' : `${Math.trunc(s.maxBitrateKbps / 1000)} Mbps`, 'Caps the stream the server sends. Lower it for remote servers or a slow connection.'),
      Row('audio', 'Audio language', MediaTrack.languageName(s.audioLanguage) ?? 'Default', 'Picks this language’s audio track when a program has one. Otherwise the server’s default plays. Takes effect on the next program.'),
      Row('subs', 'Subtitles', subs, 'On shows subtitles for every program that has them, in the language below. Forced only shows just the foreign-dialogue subtitles a program marks as needed, like broadcast TV. Off hides them all.'),
      Row('subLang', 'Subtitle language', MediaTrack.languageName(s.subtitleLanguage) ?? `Device language (${MediaTrack.languageName(AppModel.deviceLanguage) ?? 'English'})`, 'Which subtitles to show when they’re on. If a program doesn’t have this language, the first full subtitle track plays instead.'),
      SettingsRow.head('Profiles'),
      Row('profile', 'Who’s watching', p.name, 'Each profile keeps its own lineup of channels.'),
      SettingsRow.head('Library'),
      Row('server', 'Server', this.serverLabel, `Choose which libraries feed the schedule. ${fmt.format(films)} films and ${fmt.format(shows.size)} shows on the schedule.`),
      Row('rebuild', 'Rebuild automatic channels', '', 'Reads your library again and refreshes the automatic channels. Your edits and custom channels stay put.'),
      Row('signout', 'Sign out', this.signOutArmed ? `${Platform.verb} again to sign out` : '', `Disconnects this ${Platform.device} from ${service}. Your channels are kept for when you sign back in.`),
      SettingsRow.head('About'),
      Row('about', 'About Rerun Guide', AppModel.version, AppModel.aboutText),
    ]
  },
})

AppModel.prototype.pressSettings = function (this: AppModel, cmd: RemoteCommand) {
  const keys: SettingsRow.Key[] = []
  for (const r of this.settingsRows) if (r.key != null) keys.push(r.key)
  if (cmd !== 'select') this.signOutArmed = false   // any focus move (or Back) disarms Sign out
  if (this.step(cmd, keys.length)) return
  if (cmd === 'back') { this.go('guide'); return }
  if (cmd !== 'select' || this.focusIndex < 0 || this.focusIndex >= keys.length) return
  switch (keys[this.focusIndex]) {
    case 'theme': this.toggleTheme(); break
    case 'crt': this.updateSettings(it => ({ ...it, crtLines: !it.crtLines })); break
    case 'auto': this.updateSettings(it => ({ ...it, autoScroll: !it.autoScroll })); break
    case 'breaks': this.updateSettings(it => ({ ...it, trailerBreaks: !it.trailerBreaks })); break
    case 'spots': this.updateSettings(it => ({ ...it, houseSpots: !it.houseSpots })); break
    case 'idents': this.updateSettings(it => ({ ...it, stationIdents: !it.stationIdents })); break
    case 'idle': this.updateSettings(it => ({ ...it, idleSeconds: new Map([[5, 8], [8, 15], [15, 30], [30, 5]]).get(it.idleSeconds) ?? 8 })); break
    case 'saver': this.updateSettings(it => ({ ...it, screensaver: nextIn(['wall', 'logo', 'soyboi'], it.screensaver) })); break
    case 'attract': this.updateSettings(it => ({ ...it, attractMinutes: new Map([[0, 1], [1, 3], [3, 5], [5, 10]]).get(it.attractMinutes) ?? 0 })); break
    case 'tune': this.updateSettings(it => ({ ...it, tuneMode: it.tuneMode === 'live' ? 'start' : 'live' })); break
    case 'quality':   // 2 Mbps exists because Plex Relay caps there. The clients read it at init; a playing stream keeps its quality until the next load.
      this.updateSettings(it => ({ ...it, maxBitrateKbps: new Map([[0, 20000], [20000, 8000], [8000, 4000], [4000, 2000]]).get(it.maxBitrateKbps) ?? 0 }))
      this.rebuildServer()
      break
    case 'audio': this.updateSettings(it => ({ ...it, audioLanguage: nextIn([null, ...AppModel.languages], it.audioLanguage) })); break
    case 'subs': this.updateSettings(it => ({ ...it, subtitleMode: nextIn(['forced', 'on', 'off'], it.subtitleMode) })); break
    case 'subLang': this.updateSettings(it => ({ ...it, subtitleLanguage: nextIn([null, ...AppModel.languages], it.subtitleLanguage) })); break
    case 'profile': {
      const i = Math.max(0, this.persisted.profiles.findIndex(p => p.id === this.persisted.currentProfileID))
      this.go('profiles'); this.focusIndex = i
      break
    }
    case 'about': break   // read-only: the credit sits in the description pane
    case 'server': this.librariesOrigin = 'settings'; this.go('libraries'); break
    case 'rebuild':
      void (async () => {
        await this.loadLibrary(true, true)
        this.toast(this.libraryError ?? 'Channels rebuilt. Your edits and custom channels stayed put.')
      })()
      break
    case 'signout':
      if (!this.signOutArmed) {
        this.signOutArmed = true
        this.later('signout', 5, () => { this.signOutArmed = false })
        return
      }
      this.signOutArmed = false; this.signOut()
      break
  }
}

// MARK: Channel editor

Object.defineProperty(AppModel.prototype, 'channelIndex', {
  get(this: AppModel): number {
    const id = this.channelSelection
    const i = id == null ? -1 : this.channels.findIndex(c => c.id === id)
    return i >= 0 ? i + 1 : 0
  },
})

Object.defineProperty(AppModel.prototype, 'selectedChannel', {
  get(this: AppModel): Channel | null {
    const id = this.channelSelection
    return id == null ? null : this.channel(id) ?? null
  },
})

AppModel.prototype.pressChannels = function (this: AppModel, cmd: RemoteCommand) {
  const list = this.channels, idx = this.channelIndex
  if (this.channelColumn === 0) {
    switch (cmd) {
      case 'up': case 'down': {
        const j = Math.max(0, Math.min(list.length, idx + (cmd === 'down' ? 1 : -1)))
        this.channelSelection = j === 0 ? null : list[j - 1].id
        break
      }
      case 'back': this.go('guide'); break
      case 'right': case 'select':
        if (idx === 0) { this.overlay = Overlay.newChannel; this.newChannelStep = 0; this.newChannelType = null; this.focusIndex = 0 }
        else { this.channelColumn = 1; this.channelFieldIndex = 0 }
        break
    }
    return
  }
  switch (cmd) {
    case 'up': case 'down':
      this.channelFieldIndex = Math.max(0, Math.min(ChannelField.entries.length - 1, this.channelFieldIndex + (cmd === 'down' ? 1 : -1)))
      return
    case 'left': case 'back': this.channelColumn = 0; return
    case 'select': break
    default: return
  }
  const ch = this.selectedChannel
  if (ch == null) return
  const field = ChannelField.entries[this.channelFieldIndex]
  switch (field) {
    case 'title': this.editingField = 'chName'; break
    case 'number': this.numberDraft = String(ch.number); this.editingField = 'chNum'; break
    case 'order': this.updateChannel(ch.id, it => it.copy({ order: PlayOrder.next(it.order) })); break
    case 'breaks': this.updateChannel(ch.id, it => it.copy({ trailerBreaks: !it.trailerBreaks })); break
    case 'tune':
      this.updateChannel(ch.id, it => it.copy({ tuneMode: it.tuneMode == null ? 'live' : it.tuneMode === 'live' ? 'start' : null }))
      break
    case 'show': this.setHidden(ch.id, !this.isHidden(ch.id)); break
    case 'delete':
      if (ch.isCustom) {
        const next = idx < list.length ? list[idx] : idx >= 2 ? list[idx - 2] : null
        this.deleteCustomChannel(ch.id); this.channelColumn = 0; this.channelSelection = next != null ? next.id : null
        this.toast(`Channel ${ch.number} is off the air.`)
      } else {
        this.resetChannel(ch.id)
        // The automatic number may have been given to another channel meanwhile: keep the edited one then.
        const auto = this.channel(ch.id)
        if (auto != null && this.channels.some(c => c.number === auto.number && c.id !== ch.id)) {
          this.updateChannel(ch.id, it => it.copy({ number: ch.number }))
        }
        this.toast(`Channel ${this.channel(ch.id)?.number ?? ch.number} is back to automatic.`)
      }
      break
  }
}

/** Validate and apply the channel-number draft (the prototype's commitNum). */
AppModel.prototype.commitNumber = function (this: AppModel) {
  try {
    const ch = this.selectedChannel
    if (ch == null) return
    const v = toIntOrNull(this.numberDraft)
    if (v == null) return
    if (v === ch.number) return
    if (v < 1 || v > 999) { this.toast('Pick a number from 1 to 999.'); return }
    if (this.channels.some(c => c.number === v && c.id !== ch.id)) { this.toast(`Channel ${v} is taken.`); return }
    this.updateChannel(ch.id, it => it.copy({ number: v }))
  } finally { this.numberDraft = null }
}
