// Port of android/app Scramble.kt for LG and Samsung: channel 99 is only the silent GIF (web/README.md, "Channel 99"),
// the Kotlin Fire TV path (`ScrambleGif`). No shader, no tearing fallback, no backdrop fetch, no frame clock, no warble.
import type { JSX } from 'preact'

/**
 * Channel 99's picture: `scramble.gif` (the Metal shader's maths over colour bars, a seamless 10 s loop at 640×360)
 * covering the box, cropped by its middle (16:9 fills the stage exactly and the Late Show's 4:3 tube by its centre).
 * An `<img>`, so it never takes the TVs' one video decoder; the GIF only decodes while it's mounted, so mount it only
 * while it shows. `seed` (the slot start, which picked the backdrop) and `audio` (the warble) are kept so callers read
 * as in Kotlin; the GIF is the same for every program and silent.
 */
export function ScrambleView(props: { seed: number; style?: JSX.CSSProperties; audio?: boolean }) {
  return (
    <div style={{ ...props.style, background: '#000', overflow: 'hidden' }}>
      <img src="scramble.gif" alt="" style={{ display: 'block', width: '100%', height: '100%', objectFit: 'cover' }} />
    </div>
  )
}
