// Signs dist/tizen into dist/RerunGuide.wgt with the Tizen CLI (`npm run package:tizen` builds first).
// TIZEN_PROFILE names the security profile; without it the CLI's active profile is used. Setup: web/README.md.
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const dist = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'dist')
const app = path.join(dist, 'tizen')
const profile = process.env.TIZEN_PROFILE
const args = ['package', '-t', 'wgt', ...(profile ? ['-s', profile] : []), '--', app]

const r = spawnSync('tizen', args, { stdio: 'inherit' })
if (r.error && r.error.code === 'ENOENT') {
  console.error(`The Tizen CLI (tizen) isn't on PATH. To package for Samsung:
  1. Install Tizen Studio (CLI is enough; on Apple Silicon it runs under Rosetta) with the TV Extension and
     Samsung Certificate Extension 2.0.73 or later from its Package Manager.
  2. export PATH="$HOME/tizen-studio/tools/ide/bin:$HOME/tizen-studio/tools:$PATH"
  3. Create a Samsung certificate in Certificate Manager (Samsung account sign-in, the TV's DUID in the
     distributor certificate) and note the security profile's name.
  4. TIZEN_PROFILE=<profile> npm run package:tizen
See "Samsung (Tizen)" in web/README.md.`)
  process.exit(1)
}
if (r.status !== 0) process.exit(r.status ?? 1)

// The CLI writes "<name>.wgt" into the app folder; move it beside the .ipk under a name without a space.
const wgt = fs.readdirSync(app).find(f => f.endsWith('.wgt'))
if (!wgt) {
  console.error(`tizen package reported success but left no .wgt in ${app}`)
  process.exit(1)
}
fs.renameSync(path.join(app, wgt), path.join(dist, 'RerunGuide.wgt'))
console.log(path.join(dist, 'RerunGuide.wgt'))
