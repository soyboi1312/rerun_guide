// Port of android/core LocalDiscovery.kt, as a stub. The Kotlin file sends one UDP broadcast (Jellyfin/Emby "who is …?" on
// 7359, Plex GDM on 32414) and lists the replies. A web page can't send UDP, and neither webOS nor Tizen gives third-party
// web apps a socket API, so there is nothing to find: Plex and Emby link by PIN, Jellyfin by typed address.
// ponytail: an HTTP sweep of the TV's /24 (:32400/identity, :8096/System/Info/Public) could stand in, if typing hurts.
import { ServerKind } from './Models'
import { PlexServer } from './PlexClient'

export const LocalDiscovery = {
  /** Kotlin listens 1.5 s for replies; here it resolves [] at once (never throws, like Kotlin with no network). */
  async find(kind: ServerKind): Promise<PlexServer[]> {
    return []
  },
}
