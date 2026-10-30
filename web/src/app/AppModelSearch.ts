// Port of android/app AppModelSearch.kt: Options › Search. Find a film or a show, see when it's on next, open it, or
// make a channel of it.
import { ChannelBuilder } from '../core/GuideGenerator'
import { TitleSearch } from '../core/TitleSearch'
import { Instant, now } from '../core/Time'
import { AppModel, Overlay } from './AppModel'
import type { RemoteCommand } from './Remote'
import { Clock } from './Text'

declare module './AppModel' {
  namespace AppModel {
    /** How many of a film's billed actors count as its leads. */
    const leadActors: number
  }
  interface AppModel {
    /** The library's titles keyed for matching, built on first use after the library changed. */
    readonly titleIndex: TitleSearch.Index
    /** The result the page's focus is on: what Program info or the Make a channel page over it is about. */
    readonly searchSelection: TitleSearch.Entry | null
    /** Options › Search: an empty page with its text entry open. */
    openSearch(): void
    /** When everything airs in the next 24 hours, across the channels this profile sees: as the page opens, on its 10 s
     *  clock (a program ended, the lineup was rebuilt, the app came back) and before OK opens a result. Never per keystroke. */
    refreshAirings(at?: Instant): void
    /** "Tonight 10:00 PM · Ch 5 Fright Night", in Clock's time. */
    airsText(e: TitleSearch.Entry, at?: Instant): string
    /** Up / Down move between the field and the results, Select types in the field or opens a result, Back goes to the guide. */
    pressSearch(cmd: RemoteCommand): void
    /** Make a channel of a result, as Channels › New channel does: a show's marathon in episode order at once; for a film,
     *  the choice of its director(s) and lead actors with enough films for a channel. Says so when nothing qualifies. */
    makeChannel(e: TitleSearch.Entry): void
    /** The Make a channel page's buttons: Make a channel, then a film's people to pick from. */
    readonly makeActions: AppModel.InfoAction[]
    pressMakeChannel(cmd: RemoteCommand): void
  }
}

Object.assign(AppModel, { leadActors: 3 })

Object.defineProperty(AppModel.prototype, 'titleIndex', {
  get(this: AppModel): TitleSearch.Index {
    const i = this.searchIndex
    if (i != null) return i
    const built = TitleSearch.index(this.library.concat(this.freshItems))
    this.searchIndex = built
    return built
  },
})

Object.defineProperty(AppModel.prototype, 'searchSelection', {
  get(this: AppModel): TitleSearch.Entry | null { return this.searchResults[this.searchFocus - 1] ?? null },
})

AppModel.prototype.openSearch = function (this: AppModel) {
  this.go('search')
  this.refreshAirings()
  this.searchQuery = ''; this.searchFocus = 0
  this.editingField = 'search'
}

AppModel.prototype.refreshAirings = function (this: AppModel, t: Instant = now()) {
  const end = t + TitleSearch.window
  this.searchAirings = TitleSearch.nextAirings(
    this.channels.filter(c => !this.isHidden(c.id)).map(c => ({ channel: c, slots: this.slots(c, t, end) })), this.titleIndex, t)
}

AppModel.prototype.airsText = function (this: AppModel, e: TitleSearch.Entry, at: Instant = now()): string {
  return TitleSearch.airsText(this.searchAirings.get(e.id), at, d => `${Clock.time(d)} ${Clock.ap(d)}`)
}

AppModel.prototype.pressSearch = function (this: AppModel, cmd: RemoteCommand) {
  switch (cmd) {
    case 'up': case 'down':
      this.searchFocus = Math.max(0, Math.min(this.searchResults.length, this.searchFocus + (cmd === 'down' ? 1 : -1)))
      break
    case 'back': this.go('guide'); break
    case 'select': {
      const e = this.searchSelection
      if (e == null) { this.editingField = 'search'; return }
      this.makePeople = null; this.refreshAirings()
      // On in the next 24 hours: its Program info, which adds Make a channel over this page. Otherwise just that.
      const a = this.searchAirings.get(e.id)
      if (a != null) this.openInfo({ channelID: a.channelID, start: a.start })
      else { this.overlay = Overlay.makeChannel; this.focusIndex = 0 }
    }
  }
}

AppModel.prototype.makeChannel = function (this: AppModel, e: TitleSearch.Entry) {
  if (e.isShow) {
    const c = ChannelBuilder.candidates('series', this.library).find(it => it.name === e.title)
    if (c == null) { this.toast(`${e.title} doesn’t have enough episodes for a channel.`); return }
    made(this, 'series', c); return
  }
  const m = e.item
  const leads = m.actors.slice(0, AppModel.leadActors)
  const directors = ChannelBuilder.candidates('director', this.library).filter(c => m.directors.indexOf(c.name) >= 0)
  const actors = ChannelBuilder.candidates('actor', this.library).filter(c => leads.indexOf(c.name) >= 0)
  const people = directors.map(c => ({ kind: 'director' as ChannelBuilder.CandidateKind, candidate: c }))
    .concat(actors.map(c => ({ kind: 'actor' as ChannelBuilder.CandidateKind, candidate: c })))
  if (people.length === 0) { this.toast(`No one from ${e.title} has enough films for a channel.`); return }
  this.makePeople = people; this.overlay = Overlay.makeChannel; this.focusIndex = 0
}

/** The new channel lands in the channel editor, as after Channels › New channel. */
function made(m: AppModel, kind: ChannelBuilder.CandidateKind, c: ChannelBuilder.Candidate) {
  m.createChannel(kind, c); m.makePeople = null; m.go('channels')
}

Object.defineProperty(AppModel.prototype, 'makeActions', {
  get(this: AppModel): AppModel.InfoAction[] {
    const e = this.searchSelection
    if (e == null) return []
    const people = this.makePeople
    if (people == null) return [new AppModel.InfoAction('Make a channel', () => this.makeChannel(e))]
    return people.map(p => new AppModel.InfoAction(`${p.candidate.name} · ${ChannelBuilder.CandidateKind.rawValue(p.kind)}`,
      () => made(this, p.kind, p.candidate)))
  },
})

AppModel.prototype.pressMakeChannel = function (this: AppModel, cmd: RemoteCommand) {
  const acts = this.makeActions
  if (this.step(cmd, acts.length, true)) return
  if (cmd === 'select' && acts.length > 0) acts[Math.min(this.focusIndex, acts.length - 1)].run()
  else if (cmd === 'back') { this.makePeople = null; this.closeOverlay() }
}
