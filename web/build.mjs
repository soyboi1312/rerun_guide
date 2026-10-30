// Builds the web app: `node build.mjs build` (dist/webos, dist/tizen, dist/browser), `dev` (the browser build, rebuilt
// on change and served at http://127.0.0.1:8080), `test` (bundles test/*.test.ts for Node and runs them).
import * as esbuild from 'esbuild'
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const web = path.dirname(fileURLToPath(import.meta.url))
const repo = path.join(web, '..')
const src = path.join(web, 'src')
const variants = ['webos', 'tizen', 'browser']

function bundleOptions(variant) {
  return {
    entryPoints: [path.join(src, 'main.tsx')],
    outfile: path.join(web, 'dist', variant, 'app.js'),
    bundle: true,
    minify: true,
    format: 'iife',          // one classic script: webOS won't load module scripts from file://
    target: 'chrome68',      // webOS 5; esbuild lowers syntax but adds no polyfills (web/README.md lists the banned APIs)
    jsx: 'automatic',
    jsxImportSource: 'preact',
    define: { __DEV__: String(variant === 'browser') },
    sourcemap: variant === 'browser',
    logLevel: 'warning',
  }
}

function copyFiles(from, to, filter = () => true, rename = f => f) {
  fs.mkdirSync(to, { recursive: true })
  for (const f of fs.readdirSync(from).filter(filter)) fs.copyFileSync(path.join(from, f), path.join(to, rename(f)))
}

/** The page and its styles. Tizen loads Samsung's webapis.js first; the browser build gets hls.js. */
function copyShell(variant) {
  const out = path.join(web, 'dist', variant)
  let html = fs.readFileSync(path.join(src, 'index.html'), 'utf8')
  const app = '<script src="app.js"></script>'
  if (variant === 'tizen') html = html.split(app).join('<script src="$WEBAPIS/webapis/webapis.js"></script>\n' + app)
  // Development only: the player uses window.Hls where the browser can't play HLS itself.
  if (variant === 'browser') html = html.split(app).join('<script src="hls.min.js"></script>\n' + app)
  fs.writeFileSync(path.join(out, 'index.html'), html)
  fs.copyFileSync(path.join(src, 'styles.css'), path.join(out, 'styles.css'))
}

/** Everything but app.js: the shell, fonts, house spots, images, channel 99, the platform manifests and icons. */
function copyAssets(variant) {
  const out = path.join(web, 'dist', variant)
  copyShell(variant)
  if (variant === 'browser') fs.copyFileSync(path.join(web, 'node_modules/hls.js/dist/hls.min.js'), path.join(out, 'hls.min.js'))
  copyFiles(path.join(repo, 'App/Fonts'), path.join(out, 'fonts'), f => f.endsWith('.ttf'))
  // The Android copies of the spots: their end card names iPhone and Android (App/Media's name only iPhone, for the App Store).
  copyFiles(path.join(repo, 'android/app/src/main/res/raw'), path.join(out, 'media'),
    f => f.startsWith('beacon_') && f.endsWith('.mp4'), f => f.replace(/_/g, '-'))
  copyFiles(path.join(repo, 'android/app/src/main/res/drawable-xhdpi'), path.join(out, 'images'),
    f => f === 'bounce_logo.png' || f === 'soyboi_mascot.png')
  fs.copyFileSync(path.join(web, 'assets/scramble.gif'), path.join(out, 'scramble.gif'))
  const platform = path.join(web, 'platform', variant)
  if (fs.existsSync(platform)) fs.cpSync(platform, out, { recursive: true })
}

/** web/seed.json (git-ignored: it holds a server token) is served next to the page in development. */
function copySeed() {
  const seed = path.join(web, 'seed.json')
  if (fs.existsSync(seed)) fs.copyFileSync(seed, path.join(web, 'dist/browser/seed.json'))
}

async function build() {
  for (const v of variants) {
    const out = path.join(web, 'dist', v)
    fs.rmSync(out, { recursive: true, force: true })
    fs.mkdirSync(out, { recursive: true })
    await esbuild.build(bundleOptions(v))
    copyAssets(v)
    console.log(`dist/${v}`)
  }
}

async function dev() {
  const out = path.join(web, 'dist/browser')
  fs.rmSync(out, { recursive: true, force: true })
  fs.mkdirSync(out, { recursive: true })
  copyAssets('browser')
  const ctx = await esbuild.context({
    ...bundleOptions('browser'),
    plugins: [{ name: 'shell', setup: b => b.onEnd(() => { copyShell('browser'); copySeed() }) }],
  })
  // The page and styles aren't in the bundle graph: copy them again when they change.
  for (const f of ['index.html', 'styles.css']) fs.watch(path.join(src, f), () => copyShell('browser'))
  await ctx.watch()
  // http on 127.0.0.1: the Plex server allows token-less CORS only from http://localhost:PORT / 127.0.0.1:PORT.
  const { hosts, port } = await ctx.serve({ host: '127.0.0.1', port: 8080, servedir: out })
  console.log(`http://${hosts[0]}:${port}/`)
}

async function test() {
  const dir = path.join(web, 'test')
  const outdir = path.join(web, '.test-build')
  const tests = fs.readdirSync(dir).filter(f => f.endsWith('.test.ts'))
  fs.rmSync(outdir, { recursive: true, force: true })
  await esbuild.build({
    entryPoints: tests.map(f => path.join(dir, f)),
    outdir,
    outExtension: { '.js': '.mjs' },
    bundle: true,
    platform: 'node',
    format: 'esm',
    packages: 'external',
    jsx: 'automatic',
    jsxImportSource: 'preact',
    define: { __DEV__: 'true' },
    logLevel: 'warning',
  })
  const files = tests.map(f => path.join(outdir, f.replace(/\.ts$/, '.mjs')))
  // Pinned zone and locale: the parity fixture is Los Angeles time, and the Clock checks expect English names.
  const env = { ...process.env, TZ: 'America/Los_Angeles', LANG: 'en_US.UTF-8', LC_ALL: 'en_US.UTF-8' }
  const r = spawnSync(process.execPath, ['--test', ...files], { stdio: 'inherit', env, cwd: web })
  process.exit(r.status ?? 1)
}

const modes = { build, dev, test }
const mode = modes[process.argv[2]]
if (!mode) {
  console.error('usage: node build.mjs build|dev|test')
  process.exit(2)
}
await mode()
