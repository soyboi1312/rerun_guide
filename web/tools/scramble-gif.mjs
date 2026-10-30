// Renders channel 99's scramble as a silent looping GIF for the LG and Samsung builds (web/assets/scramble.gif).
// Same math as App/Shaders/Scramble.metal (scrambleWarp, then scrambleColor) over the colour bars the app shows when the
// library has no art. The time frequencies are nudged to whole cycles per loop so the GIF has no seam.
// Run from the repo root (needs ffmpeg): node web/tools/scramble-gif.mjs [out.gif]
import { spawn } from 'node:child_process'

const out = process.argv[2] ?? new URL('../assets/scramble.gif', import.meta.url).pathname
const W = 640, H = 360, K = 1920 / W   // GIF pixels; K = stage points per pixel
const SW = 1920, SH = 1080             // the shader's `size`, in stage points
const FPS = 15, LOOP = 10              // seconds
const TAU = 2 * Math.PI
// Metal value -> whole cycles in LOOP seconds: 2.1 -> 1.885, 5.7 -> 5.655, 1.7 -> 1.885, 23 -> 23.25, 0.23 -> 0.2 (0.9 already fits).
const f = (cycles) => TAU * cycles / LOOP
const WAVE1 = f(3), WAVE2 = f(9), SWING = f(3), GLOW = f(37), ROLL_BAR = 2 / LOOP, BAND = 0.9

// SwiftUI's white, yellow, cyan, green, purple, red, blue (dark appearance), as in Scramble.kt.
const bars = ['ffffff', 'ffd60a', '64d2ff', '30d158', 'bf5af2', 'ff453a', '0a84ff']
  .map(h => [0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16) / 255))

const fract = (x) => x - Math.floor(x)
const smoothstep = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t) }
const mix = (a, b, t) => a + (b - a) * t

function frame(time, buf) {
  const step = Math.floor(time * 3), tick = Math.floor(time * 60)
  for (let py = 0; py < H; py++) {
    const posY = (py + 0.5) * K, y = posY / SH
    const wave = Math.sin(y * 9 + time * WAVE1) * 0.10 + Math.sin(y * 31 - time * WAVE2) * 0.03
    const band = Math.floor(y * 14 + time * BAND)
    const tear = (fract(Math.sin(band * 12.9898 + step * 78.233) * 43758.5453) - 0.5) * 0.22
    const jitter = (fract(Math.sin((posY + tick) * 0.37) * 1e4) - 0.5) * 0.012
    // At 1/3 scale the 3-point scanlines alias away, so alternate GIF rows instead (6-point pitch on a 1080p TV).
    const scan = py % 2 ? 0.72 : 1
    const bar = smoothstep(0, 0.035, Math.abs(fract(y - time * ROLL_BAR) - 0.5))
    const swing = 0.5 + 0.5 * Math.sin(time * SWING + y * 11)
    const glow = 1.15 + 0.15 * Math.sin(time * GLOW + y * 90)
    const gain = scan * (0.25 + 0.75 * bar) * glow
    for (let px = 0; px < W; px++) {
      const x = (px + 0.5) * K + (wave + tear + jitter) * SW
      const o = (py * W + px) * 3
      // Past the edge reads transparent on the Apple TV (maxSampleOffset), so the torn-off edge is black.
      if (x < 0 || x >= SW) { buf[o] = buf[o + 1] = buf[o + 2] = 0; continue }
      const [r, g, b] = bars[Math.floor(x / SW * 7)]
      const m = [mix(r, g * 1.3, swing), mix(g, b * 0.8, swing), mix(b, r * 1.4, swing)]
      for (let c = 0; c < 3; c++) buf[o + c] = Math.max(0, Math.min(255, Math.round(m[c] * gain * 255)))
    }
  }
}

const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', `${W}x${H}`, '-r', String(FPS), '-i', '-',
  '-filter_complex', '[0:v]split[a][b];[a]palettegen=max_colors=128:stats_mode=full[p];[b][p]paletteuse=dither=none',
  '-loop', '0', out], { stdio: ['pipe', 'inherit', 'inherit'] })
const buf = Buffer.alloc(W * H * 3)
for (let i = 0; i < FPS * LOOP; i++) {
  frame(i / FPS, buf)
  if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r))
}
ff.stdin.end()
ff.on('close', (code) => { if (code) process.exit(code); console.log('wrote', out) })
