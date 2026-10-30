// Port of android/app AppModelTouch.kt: pointer input (the AppModel half of Touch.swift). The app has one input model, the
// remote's seven commands (`RemoteCommand`, dispatched by `AppModel.press`), so the LG Magic Remote's pointer maps onto
// it instead of adding controls: a click focuses what it hits and presses Select; the wheel arrives through
// `remoteDrags`. Unlike Android (touch only), this is always on: a div with only an onClick never takes the remote's focus.
import { AppModel } from './AppModel'
import { SpotOnAir } from './AppModelWatch'
import { onPoint, onTouch } from './Platform'
import type { RemoteCommand } from './Remote'

declare module './AppModel' {
  interface AppModel {
    /** Up / Down from the wheel; never on the player, where it would flip channels. On Channels it scrolls the channel
     *  list even with the editor's fields focused (seven rows that never scroll), where the remote's Up / Down would move
     *  between fields. */
    scroll(cmd: RemoteCommand): void
    /** A click on the picture: the banner's buttons (the remote's Select), or put the banner away. Never skips the spot:
     *  only its Skip chip does. */
    watchTap(): void
    /** A swipe in from the left edge: the guide's menu (Options), elsewhere Back. */
    edgeSwipe(): void
    /** The guide's menu button and first-launch hint: the menu (Options) slides in from the left. */
    readonly showsMenuButton: boolean
    /** The on-screen Back: the remote's Back, except that from the player it always goes to the guide. */
    touchBack(): void
    /** Not on the top-level screens (the guide has its menu button instead); the player shows Back with its banner. */
    readonly showsTouchBack: boolean
  }
}

/** The remote's way of acting on a list item: focus it (`focus` sets the screen's focus state), then Select. The LG
 *  pointer moving onto it runs `point` (by default the same focus) without the Select.
 *  Spread into an element: `<div {...tapSelects(model, () => { model.focusIndex = i })}>`. */
export function tapSelects(model: AppModel, focus: () => void = () => {}, point: () => void = focus):
  { onClick: (e: MouseEvent) => void; onMouseMove: (e: MouseEvent) => void } {
  return { ...onTouch(() => { focus(); model.press('select') }), ...onPoint(point) }
}

AppModel.prototype.scroll = function (this: AppModel, cmd: RemoteCommand) {
  if (this.screen === 'watch' && this.overlay == null && this.trackMenu == null) return   // the track list scrolls
  if (this.screen === 'channels' && this.overlay == null) this.channelColumn = 0
  this.press(cmd)
}

AppModel.prototype.watchTap = function (this: AppModel) {
  if (this.bannerVisible && this.bannerFocus >= 0) this.hideBanner()
  else if (SpotOnAir.started == null) this.press('select')
}

AppModel.prototype.edgeSwipe = function (this: AppModel) {
  if (this.overlay != null || this.editingField != null) return
  if (this.screen === 'guide') { if (this.attracting) this.attractReset(); else this.openOptions() }
  else if (this.showsTouchBack) this.touchBack()
}

Object.defineProperty(AppModel.prototype, 'showsMenuButton', {
  get(this: AppModel): boolean {
    return this.screen === 'guide' && this.overlay == null && this.editingField == null && !this.attracting
  },
})

AppModel.prototype.touchBack = function (this: AppModel) {
  const pb = this.playback
  if (this.screen === 'watch' && this.overlay == null && this.trackMenu == null && pb != null) this.toGuide(pb.channelID)
  else this.press('back')
}

Object.defineProperty(AppModel.prototype, 'showsTouchBack', {
  get(this: AppModel): boolean {
    if (this.overlay != null || this.editingField != null) return false
    switch (this.screen) {
      case 'welcome': case 'guide': return false
      case 'watch': return this.bannerVisible
      default: return true
    }
  },
})
