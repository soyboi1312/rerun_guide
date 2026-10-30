// Port of android/app RemoteImage.kt.
import type { JSX } from 'preact'
import { useState } from 'preact/hooks'

const FILL: JSX.CSSProperties = { position: 'absolute', left: 0, top: 0, width: '100%', height: '100%' }

/**
 * SwiftUI's `AsyncImage(url:) { $0.resizable().scaledToFill() } placeholder: { Color.clear }`: fills the box `style`
 * gives (cropped to fill, the only content scale the app uses), clear while loading or when the load fails, no crossfade.
 * `overlay` (a CSS background, e.g. the preview's scrim) is drawn over the picture only once it has loaded, as Swift's
 * sits in AsyncImage's content. The browser decodes off the main thread (`decoding=async`) and its HTTP cache stands in
 * for the Kotlin LRU; the servers already size the art (Plex transcode, Jellyfin/Emby maxWidth), so nothing is
 * downsampled here.
 */
export function RemoteImage(props: { url: string | null | undefined; style?: JSX.CSSProperties; overlay?: string }) {
  const { url, overlay } = props
  // The url whose picture the img holds: a new url hides the old picture until its own has loaded (Kotlin `remember(url)`).
  const [loaded, setLoaded] = useState<string | null>(null)
  const shown = url != null && loaded === url
  return (
    <div style={{ position: 'relative', ...props.style }}>
      {url != null && (
        <img src={url} alt="" decoding="async" onLoad={() => setLoaded(url)} onError={() => setLoaded(null)}
          style={{ ...FILL, objectFit: 'cover', visibility: shown ? 'visible' : 'hidden' }} />
      )}
      {shown && overlay != null && <div style={{ ...FILL, background: overlay }} />}
    </div>
  )
}
