// WebVTT for the TV apps' own subtitle drawing (Emby/Jellyfin `Subtitles/{index}/Stream.vtt`): cue times and plain text.
// Web only: Swift and Kotlin show the server's HLS subtitle rendition instead.
// ponytail: cue styling and positions are dropped (tags, {\an8}); every cue sits at the bottom. Add them if anyone misses them.

export interface Cue { readonly start: number; readonly end: number; readonly text: string }

/** Seconds from "hh:mm:ss.mmm" or "mm:ss.mmm" (SRT's comma works too), else NaN. */
function seconds(s: string): number {
  const m = /^(?:(\d+):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})$/.exec(s.trim())
  if (m == null) return NaN
  return Number(m[1] ?? 0) * 3600 + Number(m[2]) * 60 + Number(m[3]) + Number(m[4].padEnd(3, '0')) / 1000
}

/** The cues of a WebVTT file in file order, text without tags. Blocks with no valid timing line (header, NOTE, STYLE) are skipped. */
export function parseWebVTT(src: string): Cue[] {
  const out: Cue[] = []
  for (const block of src.replace(/\r\n?/g, '\n').split(/\n{2,}/)) {
    const lines = block.split('\n')
    const i = lines.findIndex(l => l.indexOf('-->') >= 0)
    if (i < 0) continue
    const [a, b] = lines[i].split('-->')
    const start = seconds(a), end = seconds(b.trim().split(/\s+/)[0])
    if (isNaN(start) || isNaN(end) || end <= start) continue
    const text = lines.slice(i + 1).join('\n')
      .replace(/<[^>]*>/g, '').replace(/\{\\[^}]*\}/g, '')
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').trim()
    if (text !== '') out.push({ start, end, text })
  }
  return out
}

/** The text on screen at `t` seconds: every cue covering it, one per line ('' when none). */
export function cueText(cues: readonly Cue[], t: number): string {
  return cues.filter(c => t >= c.start && t < c.end).map(c => c.text).join('\n')
}
