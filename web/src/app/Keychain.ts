// Port of android/app Keychain.kt.
import { defaults } from './Persistence'

/**
 * Just enough Keychain to hold the auth tokens (`token`, `plexAccount`). Android seals them with a key in the hardware
 * keystore; a TV web app has none, and a key stored next to the value would add nothing, so they sit in localStorage
 * in plain text, under `kc.` so they never collide with a `defaults` key. A missing value reads as null, so the app
 * asks to sign in again.
 */
export const Keychain = {
  /** The Swift `set(_ value:, for key:)`. */
  set(value: string, key: string): void { defaults.set('kc.' + key, value) },
  get(key: string): string | null { return defaults.get('kc.' + key) },
  delete(key: string): void { defaults.remove('kc.' + key) },
}
