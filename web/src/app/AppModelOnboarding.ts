// Port of android/app AppModelOnboarding.kt. First launch: welcome → (address) → link/login → libraries → building (the
// design prototype's k_welcome…k_building, startLink, startBuild), wired to the real servers: Plex PIN / plex.tv password,
// Jellyfin Quick Connect / password, Emby PIN (emby.media/pin) / password, demo library.
// The PIN / Quick Connect poll loop is `linkTask` (the Swift `static var linkTask`).
import { DemoMediaServer } from '../core/DemoServer'
import { EmbyConnect } from '../core/EmbyConnect'
import { JellyfinClient } from '../core/JellyfinClient'
import { LocalDiscovery } from '../core/LocalDiscovery'
import { MediaServerError, isCancellation } from '../core/MediaServer'
import type { LibrarySection } from '../core/Models'
import { PlexAuth, PlexClient, PlexServer } from '../core/PlexClient'
import { AppModel, ServerConfig, errorText } from './AppModel'
import { Keychain } from './Keychain'
import { Platform } from './Platform'
import type { RemoteCommand } from './Remote'

declare module './AppModel' {
  namespace AppModel {
    /** The Welcome cards, name and line: Plex, Jellyfin, Emby, then the sample library. The screen and the screen reader read these. */
    const welcomeCards: readonly (readonly [string, string])[]
    /** Trim, prepend http:// when there is no scheme, drop a trailing slash. */
    function normalized(s: string): string
  }
  interface AppModel {
    /** Plex 4-char / Emby 5-digit / Jellyfin 6-digit code, as boxes. */
    readonly linkCodeChars: string[]
    readonly buildDone: boolean
    pressWelcome(cmd: RemoteCommand): void
    pressAddress(cmd: RemoteCommand): void
    startDiscovery(): void
    startLink(): void
    pressLink(cmd: RemoteCommand): void
    pressLogin(cmd: RemoteCommand): void
    /** Fetch the server's libraries into `librarySections`; LibrariesScreen calls it on appear and aborts `signal` when it
     *  goes (Kotlin cancels the screen's LaunchedEffect). */
    fetchLibraries(signal: AbortSignal): Promise<void>
    pressLibraries(cmd: RemoteCommand): void
    startBuild(): void
    pressBuilding(cmd: RemoteCommand): void
  }
}

/** Focus index of the Continue button on the address screen (input, found servers…, Continue). */
function continueIndex(m: AppModel): number {
  return m.foundServers.length + 1
}

/** Plex and Emby link an account (plex.tv/link, emby.media/pin) and then pick one of its servers; Jellyfin's Quick Connect
 *  signs in to the one server whose address was typed. */
function linksAccount(m: AppModel): boolean {
  return m.serviceKind !== 'jellyfin'
}

Object.defineProperty(AppModel.prototype, 'linkCodeChars', {
  get(this: AppModel): string[] {
    const n = this.serviceKind === 'plex' ? 4 : this.serviceKind === 'emby' ? 5 : 6
    return (this.linkCode ?? ' '.repeat(n)).split('')
  },
})

Object.defineProperty(AppModel.prototype, 'buildDone', {
  get(this: AppModel): boolean { return !this.libraryLoading && this.libraryError == null && this.builtCount >= this.channels.length },
})

/** `URL(string:)?.host`: null when the address doesn't parse or names no host. */
function hostOf(address: string): string | null {
  try { return new URL(address).hostname || null } catch { return null }
}

/** Kotlin's `errorText` is defined here; it lives in AppModel.ts so AppModel needn't import this file. */
export { errorText }

/** Kotlin's CancellationException for a task Back cancelled (`isCancellation` reads it). */
function cancelled(): Error {
  const e = new Error('The operation was aborted.')
  e.name = 'AbortError'
  return e
}

/** `scope.launch` for a task that can be cancelled (`linkTask`, `loadTask`). Nothing aborts an await: the block checks
 *  `signal` after each one (Kotlin's suspension points), so a cancelled task writes nothing more. */
function launch(block: (signal: AbortSignal) => Promise<void>): AppModel.Job {
  const ctrl = new AbortController()
  block(ctrl.signal).catch(e => { if (!isCancellation(e)) console.error(e) })
  return { cancel: () => ctrl.abort() }
}

// MARK: Welcome

Object.defineProperty(AppModel, 'welcomeCards', {
  get(): readonly (readonly [string, string])[] {
    return [
      ['Plex', `Link this ${Platform.device} to your Plex account`],
      ['Jellyfin', 'Point it at your Jellyfin server'],
      ['Emby', `Link this ${Platform.device} to your Emby account`],
      ['Sample station', 'Take a look with a made-up library'],
    ]
  },
})

AppModel.prototype.pressWelcome = function (this: AppModel, cmd: RemoteCommand) {
  // Plex / Jellyfin / Emby are one row (k_welcome's step(a, 3, true)); the demo card sits above it, over Emby.
  if (cmd === 'up' && this.focusIndex < 3) { this.focusIndex = 3; return }
  if (cmd === 'down' && this.focusIndex === 3) { this.focusIndex = 2; return }
  if (this.focusIndex < 3 && this.step(cmd, 3, true)) return
  if (cmd !== 'select') return
  this.authError = null
  this.foundServers = []; this.pendingToken = null   // Plex PIN results never leak onto another service's address screen
  const picked = (['plex', 'jellyfin', 'emby', 'demo'] as const)[Math.min(this.focusIndex, 3)]
  if (picked !== this.serviceKind) this.address = ''   // a Plex address (port 32400) must not pre-fill the Emby/Jellyfin form
  switch (this.focusIndex) {
    case 0: this.serviceKind = 'plex'; this.go('link'); break
    case 1: this.serviceKind = 'jellyfin'; this.go('address'); break
    case 2: this.serviceKind = 'emby'; this.go('link'); break
    default:
      this.connect(new DemoMediaServer(), new ServerConfig('demo', 'demo://local', 'Demo library'), '')
      this.go('libraries')
  }
}

// MARK: Address

AppModel.prototype.pressAddress = function (this: AppModel, cmd: RemoteCommand) {
  const ci = continueIndex(this)
  if (this.step(cmd, ci + 1)) return
  if (cmd === 'back') {
    this.go('welcome')
    this.focusIndex = Math.max(0, (['plex', 'jellyfin', 'emby'] as const).indexOf(this.serviceKind as 'plex'))
    return
  }
  if (cmd !== 'select') return
  const i = this.focusIndex
  if (i === 0) this.editingField = 'addr'
  else if (i < ci) {
    const s = this.foundServers[i - 1]
    // A server from the Plex or Emby account carries its access token, so Select connects. One typed in still needs sign-in.
    if (this.serviceKind === 'plex' && s.accessToken !== '') { connectPlex(this, s); return }
    const user = this.pendingToken
    if (this.serviceKind === 'emby' && s.accessToken !== '' && user != null) {
      this.address = s.baseURL   // the check mark follows the pick while it signs in
      connectEmby(this, s, user); return
    }
    this.address = s.baseURL; this.focusIndex = ci
  } else continueFromAddress(this)
}

function continueFromAddress(m: AppModel) {
  m.address = AppModel.normalized(m.address)
  if (hostOf(m.address) == null) { m.authError = 'Enter an address like 192.168.1.20:32400'; return }
  m.authError = null
  switch (m.serviceKind) {
    case 'plex': {
      const found = m.foundServers.find(s => s.baseURL === m.address && s.accessToken !== '')
      if (found != null) { connectPlex(m, found); return }
      // A typed address goes through the pendingToken / password path.
      const token = m.pendingToken
      if (token == null) { m.go('login'); return }
      if (m.authBusy) return
      m.authBusy = true; m.authSerial += 1
      const url = m.address, serial = m.authSerial
      void (async () => {
        try {
          const s = await plexServer(m, url, token, serial)
          if (s != null) connectPlex(m, s, token)
        } finally { if (m.authSerial === serial) m.authBusy = false }
      })()
      break
    }
    case 'jellyfin': m.go('link'); break
    case 'emby': {
      const s = m.foundServers.find(it => it.baseURL === m.address && it.accessToken !== '')
      const user = m.pendingToken
      if (s != null && user != null) connectEmby(m, s, user); else m.go('login')
      break
    }
    default: m.go('login')
  }
}

/** Look for servers of `serviceKind` while the address screen has nothing to list (go(Screen.address) calls this). A web
 *  app can't send UDP, so LocalDiscovery finds nothing: `discovering` stays false (Kotlin sets it while the broadcast
 *  listens) and the address screen never shows "Looking around the network…". */
AppModel.prototype.startDiscovery = function (this: AppModel) {
  void LocalDiscovery.find(this.serviceKind).then(found => {
    if (this.screen !== 'address' || this.foundServers.length > 0) return
    this.foundServers = found
    if (this.focusIndex > 0) this.focusIndex = continueIndex(this)   // already on Continue → stay on Continue as rows appear
  })
}

Object.assign(AppModel, {
  normalized(s: string): string {
    let a = s.trim()
    if (a !== '' && a.indexOf('://') < 0) a = 'http://' + a
    while (a.endsWith('/')) a = a.slice(0, -1)
    return a
  },
})

/**
 * The account's server a typed Plex address names, or null with `authError` set. No token goes to the address itself:
 * `PlexAuth.server(at)` finds the server on the account and links to an address plex.tv lists. A result that lands after
 * the viewer moved on (`authSerial`, or another service or sign-in) is dropped.
 */
async function plexServer(m: AppModel, url: string, account: string, serial: number): Promise<PlexServer | null> {
  const current = () => m.authSerial === serial && m.serviceKind === 'plex' && m.pendingToken === account
  try {
    const s = await PlexAuth.server(url, account, AppModel.deviceID)
    return current() ? s : null
  } catch (e) {
    if (isCancellation(e)) throw e
    if (!current()) return null
    const host = hostOf(url) ?? 'that address'
    if (e instanceof PlexAuth.AddressError) {
      m.authError = e.kind === 'noServer' ? `No Plex server answered at ${host}. Check the address.`
        : e.kind === 'notOnAccount' ? `${host} is a Plex server, but not one on this Plex account.`
        : 'That’s your server, but no address Plex lists for it answered. On a VPN or proxy? Add it to the server’s Custom server access URLs.'
    } else if (e instanceof MediaServerError && e.kind === 'unauthorized') {
      m.pendingToken = null; m.authError = 'Plex didn’t accept this sign-in. Sign in again.'
    } else m.authError = errorText(e)
    return null
  }
}

/** `account`: the plex.tv account token, kept so a server that moves (new LAN or public address) can be found again. */
function connectPlex(m: AppModel, s: PlexServer, account: string | null = null) {
  const a = account ?? m.pendingToken
  if (a != null) Keychain.set(a, 'plexAccount')
  const addresses = s.addresses != null && s.addresses.length > 0 ? s.addresses : null
  m.connect(new PlexClient(s.baseURL, s.accessToken, AppModel.deviceID, s.name),
    new ServerConfig('plex', s.baseURL, s.name, null, addresses, s.id), s.accessToken)
  m.go('libraries')
}

/** An Emby Connect server (`EmbyConnect.servers`): trade its access key for a sign-in, then pick libraries. */
function connectEmby(m: AppModel, s: PlexServer, connectUserID: string) {
  if (m.authBusy) return
  m.authBusy = true; m.authError = null
  void (async () => {
    try {
      const c = await JellyfinClient.connectExchange(s, connectUserID, AppModel.deviceID)
      if (m.screen !== 'address' && m.screen !== 'link') return
      m.connect(c, new ServerConfig('emby', c.baseURL, c.serverName, c.userID), c.token)
      m.go('libraries')
    } catch (e) {
      if (m.screen !== 'address' && m.screen !== 'link') return
      m.authError = `Couldn’t sign in to ${s.name}: ${errorText(e)}`
      if (m.screen === 'link') m.linked = false
    } finally {
      m.authBusy = false
    }
  })()
}

// MARK: Link (Plex PIN / Emby PIN / Jellyfin Quick Connect)

AppModel.prototype.startLink = function (this: AppModel) {
  this.linkTask?.cancel()
  this.linked = false; this.linkCode = null; this.authError = null
  this.linkTask = launch(signal => runLink(this, signal))
}

async function runLink(m: AppModel, signal: AbortSignal) {
  const id = AppModel.deviceID
  /** A suspension point: once the task is cancelled, nothing after the await runs. */
  const live = async <T>(p: Promise<T>): Promise<T> => {
    const v = await p
    if (signal.aborted) throw cancelled()
    return v
  }
  const delay = (ms: number) => live(new Promise<void>(resolve => setTimeout(resolve, ms)))
  try {
    if (m.serviceKind === 'plex') {
      const pin = await live(PlexAuth.requestPIN(id))
      m.linkCode = pin.code
      let token: string | null = null
      while (token == null) {
        await delay(3000)
        token = await live(PlexAuth.pollPIN(pin, id))
      }
      m.linked = true
      const servers = await live(PlexAuth.servers(token, id))
      if (servers.length === 0) { m.linked = false; m.authError = 'No Plex Media Server is linked to this account.' }
      else if (servers.length === 1) connectPlex(m, servers[0], token)
      else {   // land on the first server, not the address field
        m.foundServers = servers; m.pendingToken = token; m.address = servers[0].baseURL; m.go('address'); m.focusIndex = 1
      }
    } else if (m.serviceKind === 'emby') {
      let account: EmbyConnect.Account | null = null
      let pins = 0
      while (account == null) {   // an expired PIN is replaced by a fresh one; the viewer just reads the new code
        pins += 1
        if (pins > 10) { m.authError = 'The code expired. Go back and try again.'; return }
        const pin = await live(EmbyConnect.requestPIN(id))
        m.linkCode = pin.code
        try {
          while (account == null) {
            await delay(3000)
            account = await live(EmbyConnect.pollPIN(pin))
          }
        } catch (e) {
          if (e instanceof MediaServerError && e.kind === 'notFound') continue
          throw e
        }
      }
      m.linked = true
      const servers = await live(EmbyConnect.servers(account))
      if (servers.length === 0) {
        m.linked = false
        m.authError = 'No server is linked to that Emby Connect account. In your server’s dashboard, add your Emby Connect email to a user, then try again.'
      } else if (servers.length === 1) connectEmby(m, servers[0], account.userID)
      else { m.foundServers = servers; m.pendingToken = account.userID; m.address = servers[0].baseURL; m.go('address'); m.focusIndex = 1 }
    } else {
      const url = m.address
      if (hostOf(url) == null) throw MediaServerError.invalidResponse('bad server address')
      const qc = await live(JellyfinClient.quickConnectStart(url, id))
      m.linkCode = qc.code
      while (!(await live(JellyfinClient.quickConnectIsApproved(url, qc.secret, id)))) await delay(3000)
      m.linked = true
      const client = await live(JellyfinClient.loginWithQuickConnect(url, qc.secret, id))
      m.connect(client, new ServerConfig('jellyfin', client.baseURL, client.serverName, client.userID), client.token)
      m.go('libraries')
    }
  } catch (e) {
    if (isCancellation(e) || signal.aborted || m.screen !== 'link') return
    m.authError = errorText(e)
  }
}

AppModel.prototype.pressLink = function (this: AppModel, cmd: RemoteCommand) {
  if (cmd !== 'back' && cmd !== 'select') return
  this.linkTask?.cancel()
  const la = linksAccount(this)
  if (cmd === 'back') {
    this.go(la ? 'welcome' : 'address')
    this.focusIndex = la ? (this.serviceKind === 'plex' ? 0 : 2) : continueIndex(this)
  } else this.go(la ? 'address' : 'login')
}

// MARK: Login (plex.tv / Jellyfin / Emby password)

AppModel.prototype.pressLogin = function (this: AppModel, cmd: RemoteCommand) {
  if (this.step(cmd, 3)) return
  if (cmd === 'back') { this.go('address'); this.focusIndex = continueIndex(this); return }
  if (cmd !== 'select' || this.authBusy) return
  switch (this.focusIndex) {
    case 0: this.editingField = 'user'; break
    case 1: this.editingField = 'pass'; break
    default: signIn(this)
  }
}

function signIn(m: AppModel) {
  m.authBusy = true; m.authError = null; m.authSerial += 1
  const serial = m.authSerial, kind = m.serviceKind
  void (async () => {
    try {
      if (kind === 'plex') {
        let token: string
        try { token = await PlexAuth.signIn(m.username, m.password, AppModel.deviceID) }
        catch (e) {
          if (!(e instanceof MediaServerError && e.kind === 'unauthorized')) throw e
          // plex.tv answers 401 both for bad credentials and for a missing 2FA code.
          if (m.authSerial !== serial) return
          m.authError = 'Wrong email or password. Two-factor on? Type your password followed by the 6-digit code.'; return
        }
        if (m.authSerial !== serial) return   // moved on while plex.tv answered
        m.pendingToken = token
        if (hostOf(m.address) == null) { m.go('address'); return }
        const s = await plexServer(m, m.address, token, serial)
        if (s != null) connectPlex(m, s, token)
      } else {
        if (hostOf(m.address) == null) throw MediaServerError.invalidResponse('bad server address')
        const c = await JellyfinClient.login(m.address, m.username, m.password, AppModel.deviceID, kind)
        if (m.authSerial !== serial) return   // moved on while the server answered
        m.connect(c, new ServerConfig(kind, c.baseURL, c.serverName, c.userID), c.token)
        m.go('libraries')
      }
    } catch (e) {
      if (m.authSerial === serial) m.authError = errorText(e)
    } finally {
      if (m.authSerial === serial) m.authBusy = false
    }
  })()
}

// MARK: Libraries

/** The saved choice is preselected, else movies/shows default to On (a re-pick from Settings with nothing saved means
 *  "every library"). Leaving the screen aborts `signal`, Kotlin's cancel: authBusy clears at once (a stuck one would
 *  block sign-in on the screen Back leads to) and the answer, or its error, is dropped. */
/** What the Libraries rows show for `sections` before the viewer changes any: the saved choice, else movies and shows
 *  (every library on a Settings re-pick, where nothing saved means every library). */
function libraryPicks(m: AppModel, sections: readonly LibrarySection[]): Set<string> {
  return new Set(m.persisted.selectedLibraryIDs ?? sections.filter(it => m.librariesOrigin === 'settings' || it.kind !== 'other').map(it => it.id))
}

AppModel.prototype.fetchLibraries = async function (this: AppModel, signal: AbortSignal): Promise<void> {
  this.authBusy = true; this.authError = null
  // The rows show what Continue builds until the list lands (a failed load keeps the saved choice; nothing saved: all).
  this.librarySelection = libraryPicks(this, this.librarySections)
  const cancel = () => { this.authBusy = false }
  signal.addEventListener('abort', cancel)
  try {
    const server = this.server
    if (server == null) { this.authError = 'Could not connect to the server.'; return }   // say so, not "No libraries reported"
    const sections = (await server.libraries()).filter(s => s.itemCount !== 0)   // empty (Emby's Collections, Playlists): nothing to schedule; unknown counts stay
    if (signal.aborted) return
    this.librarySections = sections
    this.librarySelection = libraryPicks(this, sections)
  } catch (e) {
    if (!signal.aborted) this.authError = errorText(e)
  } finally {
    signal.removeEventListener('abort', cancel)
    if (!signal.aborted) this.authBusy = false   // aborted: `cancel` cleared it, and it may be another step's flag by now
  }
}

/** Back: Settings re-pick → Settings (saved choice untouched); a Plex or Emby account link with other servers still in
 *  memory → the server list (no new PIN); otherwise Welcome is the signed-out state, as in the prototype's k_libraries. */
AppModel.prototype.pressLibraries = function (this: AppModel, cmd: RemoteCommand) {
  const n = this.librarySections.length
  if (this.step(cmd, n + 1)) return
  if (cmd === 'back') {
    if (this.librariesOrigin === 'settings') { this.librariesOrigin = null; this.go('settings') }
    else if (linksAccount(this) && this.pendingToken != null && this.foundServers.length > 0) {   // back to the account's server list
      const token = this.pendingToken, rows = this.foundServers
      this.signOut(); this.pendingToken = token; this.foundServers = rows; this.go('address'); this.focusIndex = continueIndex(this)
    } else this.signOut()
    return
  }
  // Its list and picks aren't in yet, or there's no client: nothing to save. A list that failed to load keeps the saved
  // choice: Continue builds that, rows don't toggle.
  if (cmd !== 'select' || this.authBusy || this.server == null) return
  if (this.authError != null) { if (this.focusIndex >= n) this.go('building'); return }
  if (this.focusIndex < n) {
    const id = this.librarySections[this.focusIndex].id
    const next = new Set(this.librarySelection)
    if (next.has(id)) next.delete(id); else next.add(id)
    this.librarySelection = next
  } else {
    this.persisted = { ...this.persisted, selectedLibraryIDs: this.librarySelection.size === n ? null : Array.from(this.librarySelection) }
    this.save()
    this.go('building')
  }
}

// MARK: Building

/** Kept in `loadTask` so Back can cancel it. An empty result is not an error: the view reads `buildDone &&
 *  channels.length === 0` and offers "Pick other libraries"; `libraryError` means the load threw (Retry). */
AppModel.prototype.startBuild = function (this: AppModel) {
  this.builtCount = 0; this.libraryError = null; this.libraryLoading = true
  this.loadTask?.cancel()
  this.loadTask = launch(async signal => {
    try { await this.loadLibrary(true, true, false, signal) }   // force: a changed selection must be fetched, however fresh the cache is
    catch (e) { if (isCancellation(e)) this.libraryError = null; throw e }   // Back: the cancelled request is not a server error
    if (signal.aborted) return
    if (this.screen !== 'building') return
    reveal(this)
  })
}

/** One tile every 170 ms until every channel is on the board (the screen animates each step in, easeOut 0.3 s). Paced by
 *  the clock from the first step, not by counting ticks: a slow TV whose timers stretch adds the steps it missed, so the
 *  board still finishes when tvOS's does. */
function reveal(m: AppModel, start = performance.now()) {
  if (m.screen !== 'building' || m.builtCount >= m.channels.length) return
  const n = m.channels.length
  const per = Math.max(1, Math.trunc(n / 24))   // ≤ ~4 s however big the lineup
  const due = Math.trunc((performance.now() - start) / 170) + 1
  // Catch-up never passes the count tvOS's += per ends on, or the board scrolls (rows) past its last tile.
  m.builtCount = Math.min(Math.trunc((n + per - 1) / per) * per, Math.max(m.builtCount + per, due * per))
  m.later('build', 0.17, () => reveal(m, start))
}

AppModel.prototype.pressBuilding = function (this: AppModel, cmd: RemoteCommand) {
  if (cmd === 'back') {
    this.loadTask?.cancel(); this.cancelTimer('build')
    if (this.librariesOrigin === 'settings') { this.librariesOrigin = null; this.go('settings') } else this.go('libraries')
    return
  }
  if (cmd !== 'select') return
  if (this.libraryError != null) { this.startBuild(); return }
  if (!this.buildDone) return
  if (this.channels.length === 0) { this.go('libraries'); return }
  this.librariesOrigin = null
  this.guideReset(); this.go('guide')
  this.toast('We’re live. Press any arrow to start flipping.')
}
