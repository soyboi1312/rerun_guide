// Port of android/app Accessibility.kt. Back at the top level and the one screen-reader string. The screens are pure
// renderings with nothing focusable, so `accessibilityDescription` is what LG Audio Guidance / Samsung Voice Guide read
// after every press (RootView puts it in an aria-live region). Plain strings that mirror what each screen draws for the
// focused thing.
import { ChannelBuilder } from '../core/GuideGenerator'
import { toIntOrNull } from '../core/Lang'
import { Channel, PlayOrder, ServerKind, Slot } from '../core/Models'
import * as Time from '../core/Time'
import { AppModel } from './AppModel'
import { ChannelField } from './AppModelSettings'
import { SpotOnAir } from './AppModelWatch'
import { Platform } from './Platform'
import { ThemeID } from './Theme'

declare module './AppModel' {
  interface AppModel {
    /** Back at the app's top level leaves the app (`Platform.exitApp`). Every other screen has an in-app Back.
     *  Attract mode keeps Back: the first press of any button wakes the guide, Back included. So does the number box. */
    readonly backExitsApp: boolean
    /** The number box while it's up (it has the remote while digits are being typed), else what's focused. */
    readonly accessibilityDescription: string
    /** What's focused (Swift's private `focusDescription`): only `accessibilityDescription` reads it. */
    readonly focusDescription: string
  }
}

Object.defineProperty(AppModel.prototype, 'backExitsApp', {
  get(this: AppModel): boolean {
    return this.overlay == null && this.channelEntry?.missing !== false &&
      ((this.screen === 'guide' && !this.remoteHeld && !this.attracting) || this.screen === 'welcome')
  },
})

/** `i in list.indices`. */
function inRange(i: number | null, list: readonly unknown[]): i is number {
  return i != null && i >= 0 && i < list.length
}

function errorLine(m: AppModel): string {
  return m.authError == null ? '' : `. ${m.authError}`
}

/** "Channel 4 Gumshoe. Columbo, Any Old Port in a Storm. On now, 23 minutes in." */
function describe(m: AppModel, ch: Channel, slot: Slot): string {
  const now = Time.now()
  let when: string
  switch (m.status(slot, now)) {
    case 'live': when = `On now, ${Math.trunc((now - slot.start) / 60)} minutes in`; break
    case 'break': when = 'On now, between programs'; break
    case 'later': when = `Starts in ${Math.ceil((slot.start - now) / 60)} minutes`; break
    case 'past': when = 'Aired earlier'; break
  }
  const what = [slot.item.displayTitle, slot.item.subtitle].filter(s => s !== '').join(', ')
  return `Channel ${ch.number} ${ch.name}. ${what}. ${when}`
}

Object.defineProperty(AppModel.prototype, 'accessibilityDescription', {
  get(this: AppModel): string {
    const e = this.channelEntry
    if (this.overlay != null || e == null) return this.focusDescription
    if (e.missing) return `No channel ${toIntOrNull(e.digits) ?? 0}. ` + this.focusDescription
    const ch = this.entryChannel
    return `Channel number ${e.digits}` + (ch == null ? '' : `. Channel ${ch.number} ${ch.name}`)
  },
})

Object.defineProperty(AppModel.prototype, 'focusDescription', {
  get(this: AppModel): string {
    const o = this.overlay
    if (o != null) {
      switch (o.kind) {
        case 'options': {
          const rows = AppModel.OptionRow.entries
          const row = rows[Math.min(this.focusIndex, rows.length - 1)]
          const label = row === 'profile' ? `${this.profile.name}, Switch profile`
            : row === 'guide' ? 'Guide, all channels'
            : row === 'movies' ? 'Movies guide'
            : row === 'tv' ? 'TV Shows guide'
            : row === 'search' ? 'Search'
            : row === 'channels' ? 'Channels'
            : row === 'settings' ? 'Settings'
            : `Theme, ${ThemeID.displayName(this.profile.theme)}. Left and Right switch, ${Platform.select} closes the menu`
          return `Options. ${label}`
        }
        case 'info': {
          const p = this.program(o.key)
          if (p == null) return 'Program info'
          const acts = this.infoActions(o.key)
          return describe(this, p.channel, p.slot) + (inRange(this.focusIndex, acts) ? `. ${acts[this.focusIndex].label}` : '')
        }
        case 'newChannel': {
          if (this.newChannelStep === 0) {
            const kinds = ChannelBuilder.CandidateKind.entries
            return `New channel. ${ChannelBuilder.CandidateKind.rawValue(kinds[Math.min(this.focusIndex, kinds.length - 1)])}`
          }
          const items = this.candidateCache
          if (!inRange(this.focusIndex, items)) return 'New channel. Nothing to pick'
          const c = items[this.focusIndex]
          return `New channel. ${c.name}, ${c.count} ${this.newChannelType === 'series' ? 'episodes' : 'films'}`
        }
        case 'makeChannel': {
          const e = this.searchSelection
          if (e == null) return 'Make a channel'
          const acts = this.makeActions
          return `${e.title}. ${this.makePeople == null ? this.airsText(e) : 'Pick who the channel follows'}` +
            (inRange(this.focusIndex, acts) ? `. ${acts[this.focusIndex].label}` : '')
        }
      }
    }
    switch (this.screen) {
      case 'welcome': {
        const cards = AppModel.welcomeCards
        const c = cards[Math.min(this.focusIndex, cards.length - 1)]
        return `${c[0]}. ${c[1]}`
      }
      case 'address': {
        const servers = this.foundServers
        const row = this.focusIndex === 0 ? `Server address, ${this.address || 'empty'}`
          : this.focusIndex <= servers.length ? `${servers[this.focusIndex - 1].name}, ${servers[this.focusIndex - 1].baseURL}`
          : 'Continue'
        return row + errorLine(this)
      }
      case 'link': {
        const code = this.linkCode == null ? 'Waiting for the code' : `Code ${this.linkCode.split('').join(' ')}`
        const button = this.serviceKind === 'jellyfin' ? 'Sign in with a password instead' : 'Type a server address instead'
        return `Sign in to ${ServerKind.displayName(this.serviceKind)}. ${code}. ${button}${errorLine(this)}`
      }
      case 'login': {
        const row = this.focusIndex === 0 ? `Username, ${this.username || 'empty'}`
          : this.focusIndex === 1 ? `Password, ${this.password === '' ? 'empty' : 'entered'}` : 'Sign in'
        return row + errorLine(this)
      }
      case 'libraries': {
        if (!inRange(this.focusIndex, this.librarySections)) return `Continue${errorLine(this)}`
        const s = this.librarySections[this.focusIndex]
        return `${s.title}, ${this.librarySelection.has(s.id) ? 'On' : 'Off'}`
      }
      case 'building': {
        if (this.libraryError != null) return `${this.libraryError}. Retry`
        return this.buildDone ? 'Sign-on complete. Start flipping' : 'Configuring channels'
      }
      case 'profiles': {
        const profiles = this.persisted.profiles
        if (!inRange(this.focusIndex, profiles)) return 'Add profile'
        const p = profiles[this.focusIndex]
        const m = this.profileMenu
        if (inRange(m, profiles)) {
          const acts = this.profileActions
          return `${acts[Math.min(this.profileMenuIndex, acts.length - 1)]}, for ${profiles[m].name}`
        }
        const current = p.id === this.persisted.currentProfileID ? `, watching now, ${Platform.select} to rename` : ''
        return `${p.name}, ${this.channels.filter(c => !p.hiddenChannelIDs.has(c.id)).length} channels${current}`
      }
      case 'guide': {
        const k = this.guideKey()
        const pr = k == null ? null : this.program(k)
        if (pr == null) return this.libraryLoading ? 'Loading your library' : 'Nothing to schedule yet'
        return describe(this, pr.channel, pr.slot)
      }
      case 'watch': {
        const info = this.watchInfo()
        if (info == null) return 'Watching'
        if (SpotOnAir.started != null) {   // the house spot, labeled "Ad" on screen
          return 'Ad, Message from Soyboi. ' + (SpotOnAir.skippable ? `${Platform.select} to skip` : 'You can skip it in a few seconds')
        }
        let s = describe(this, info.channel, info.slot)
        if (this.playback?.paused === true) s += '. Paused'
        if (info.isBreak) {
          if (info.next != null) s += `. ${Platform.select} watches ${info.next.item.displayTitle} now`
          s += `. Play Pause watches ${info.slot.item.displayTitle} again`
        }
        const acts = this.watchActions()
        if (this.bannerVisible && inRange(this.bannerFocus, acts)) s += this.barFocused ? '. Progress, Left and Right skip 10 seconds' : `. ${acts[this.bannerFocus].label}`
        return s
      }
      case 'channels': {
        const ch = this.selectedChannel
        if (ch == null) return 'New channel, From a director, an actor, or one show'
        const head = `Channel ${ch.number} ${ch.name}`
        const field = ChannelField.entries[this.channelFieldIndex] as ChannelField | undefined
        if (this.channelColumn !== 1 || field == null) {
          return `${head}, ${this.isHidden(ch.id) ? 'Hidden' : ch.isCustom ? 'Custom' : 'Auto'}`
        }
        let row: string
        switch (field) {
          case 'title': row = `Name, ${ch.name}`; break
          case 'number': row = `Channel number, ${this.numberDraft ?? String(ch.number)}`; break
          case 'order': row = `Order, ${PlayOrder.rawValue(ch.order)}`; break
          case 'breaks': row = `Trailer breaks, ${ch.trailerBreaks ? 'On' : 'Off'}`; break
          case 'tune': row = `When you tune in, ${AppModel.tuneLabel(ch.definition.tuneMode, this.settings.tuneMode)}`; break
          case 'show': row = `Show in ${this.profile.name}’s guide, ${this.isHidden(ch.id) ? 'Off' : 'On'}`; break
          case 'delete': row = ch.isCustom ? 'Delete channel' : 'Reset to automatic'; break
        }
        return `${head}. ${row}`
      }
      case 'search': {
        const e = this.searchSelection
        if (e == null) {
          const q = this.searchQuery, n = this.searchResults.length
          const found = q === '' ? '' : n === 0 ? '. Nothing matches' : `. ${n} found`
          return `Search, ${q === '' ? 'empty' : q}${found}`
        }
        return `${e.title}, ${e.year == null ? '' : `${e.year}, `}${e.isShow ? 'Show' : 'Movie'}. ${this.airsText(e)}`
      }
      case 'settings': {
        const rows = this.settingsRows.filter(r => r.key != null)
        if (!inRange(this.focusIndex, rows)) return 'Settings'
        const r = rows[this.focusIndex]
        return [r.label, r.value].filter(s => s !== '').join(', ') + '. ' + r.desc
      }
    }
  },
})
