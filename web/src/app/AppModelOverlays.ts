// Port of android/app AppModelOverlays.kt: Options menu, Program info sheet, New-channel picker (the prototype's
// k_options, k_info/infoActs, k_newch/createCh). `AppModel.OptionRow` and `AppModel.InfoAction` are declared with
// AppModel (AppModel.ts), as in Kotlin, where extensions can't add nested types.
import { ChannelBuilder } from '../core/GuideGenerator'
import { uuid } from '../core/Lang'
import { ChannelDefinition, RuleChip } from '../core/Models'
import { now } from '../core/Time'
import { AppModel } from './AppModel'
import type { ProgramKey } from './AppModel'
import type { RemoteCommand } from './Remote'

declare module './AppModel' {
  interface AppModel {
    pressOptions(cmd: RemoteCommand): void
    infoActions(key: ProgramKey): AppModel.InfoAction[]
    pressInfo(cmd: RemoteCommand): void
    pressNewChannel(cmd: RemoteCommand): void
    createChannel(kind: ChannelBuilder.CandidateKind, c: ChannelBuilder.Candidate): void
  }
}

// MARK: Options (k_options)

AppModel.prototype.pressOptions = function (this: AppModel, cmd: RemoteCommand) {
  const rows = AppModel.OptionRow.entries
  if (this.step(cmd, rows.length)) return
  const row = rows[Math.min(this.focusIndex, rows.length - 1)]
  if (row === 'theme' && (cmd === 'left' || cmd === 'right')) {
    this.setTheme(cmd === 'left' ? 'LATE' : 'MIDNIGHT'); return
  }
  if (cmd === 'back' || cmd === 'right') { this.closeOverlay(); return }
  if (cmd !== 'select') return
  switch (row) {
    case 'profile':
      this.go('profiles')
      this.focusIndex = Math.max(0, this.persisted.profiles.findIndex(p => p.id === this.persisted.currentProfileID))
      break
    case 'guide': this.setGuideFilter('all'); break
    case 'movies': this.setGuideFilter('movies'); break
    case 'tv': this.setGuideFilter('tv'); break
    case 'search': this.openSearch(); break
    case 'channels': this.go('channels'); break
    case 'settings': this.go('settings'); break
    case 'theme': this.closeOverlay(); break   // Left/Right pick the theme; the center button confirms by closing the menu, never flips it again
  }
}

// MARK: Program info (infoActs / k_info)

AppModel.prototype.infoActions = function (this: AppModel, key: ProgramKey): AppModel.InfoAction[] {
  const pr = this.program(key)
  if (pr == null) return []
  const ch = pr.channel, slot = pr.slot
  const st = this.status(slot, now())
  const fromStart = new AppModel.InfoAction(st === 'later' ? 'Watch now' : 'Start from the beginning', () => this.tune(ch.id, 'start', slot.start))
  const acts = st === 'live' || st === 'break'
    ? [new AppModel.InfoAction('Watch live', () => this.tune(ch.id, 'live', null, true)), fromStart]
    : [fromStart, new AppModel.InfoAction(`Watch channel ${ch.number} now`, () => this.tune(ch.id, 'live'))]
  acts.push(new AppModel.InfoAction('Edit channel', () => {
    this.go('channels'); this.channelSelection = ch.id; this.channelColumn = 1; this.channelFieldIndex = 0
  }))
  // Opened from Search (over its page): the result can also become a channel.
  const e = this.searchSelection
  if (this.screen === 'search' && e != null) acts.push(new AppModel.InfoAction('Make a channel', () => this.makeChannel(e)))
  return acts
}

AppModel.prototype.pressInfo = function (this: AppModel, cmd: RemoteCommand) {
  const o = this.overlay
  if (o == null || o.kind !== 'info') return
  const acts = this.infoActions(o.key)
  if (this.step(cmd, acts.length, true)) return
  if (cmd === 'select' && acts.length > 0) acts[Math.min(this.focusIndex, acts.length - 1)].run()
  else if (cmd === 'back') { this.closeOverlay(); if (this.screen === 'watch') this.bannerLater() }
}

// MARK: New channel (k_newch / createCh)

AppModel.prototype.pressNewChannel = function (this: AppModel, cmd: RemoteCommand) {
  const kinds = ChannelBuilder.CandidateKind.entries
  const items = this.candidateCache
  if (this.newChannelStep === 1 && (cmd === 'up' || cmd === 'down')) {   // 3 per row
    const j = this.focusIndex + (cmd === 'down' ? 3 : -3)
    if (j >= 0 && j < items.length) this.focusIndex = j
    return
  }
  if (this.step(cmd, this.newChannelStep === 0 ? kinds.length : items.length, true)) return
  if (cmd === 'back') {
    if (this.newChannelStep === 1) {
      this.newChannelStep = 0
      const t = this.newChannelType
      this.focusIndex = t != null ? Math.max(0, kinds.indexOf(t)) : 0
    } else { this.candidateCache = []; this.closeOverlay() }
    return
  }
  if (cmd !== 'select') return
  if (this.newChannelStep === 0) {
    const kind = kinds[Math.min(this.focusIndex, kinds.length - 1)]
    this.newChannelType = kind; this.candidateCache = ChannelBuilder.candidates(kind, this.library); this.newChannelStep = 1; this.focusIndex = 0
  } else {
    const kind = this.newChannelType
    if (kind != null && this.focusIndex >= 0 && this.focusIndex < items.length) this.createChannel(kind, items[this.focusIndex])
  }
}

AppModel.prototype.createChannel = function (this: AppModel, kind: ChannelBuilder.CandidateKind, c: ChannelBuilder.Candidate) {
  const n = this.nextFreeNumber
  const chip = kind === 'director' ? RuleChip.director(c.name)
    : kind === 'actor' ? RuleChip.actor(c.name)
    : RuleChip.series(c.id ?? c.name, c.name)
  const d = new ChannelDefinition({
    id: 'custom:' + uuid(), number: n, name: c.name,
    tag: kind === 'series' ? 'Marathon' : ChannelBuilder.CandidateKind.rawValue(kind), rule: [chip],
    order: kind === 'series' ? 'inOrder' : 'shuffle', trailerBreaks: true, isCustom: true,
  })
  this.addCustomChannel(d)
  this.candidateCache = []; this.overlay = null; this.focusIndex = 0
  this.channelSelection = d.id; this.channelColumn = 1; this.channelFieldIndex = 0
  this.toast(`Channel ${n}, ${c.name}, just signed on.`)
}
