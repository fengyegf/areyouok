#!/usr/bin/env node
/**
 * Install / uninstall the dsh-gateway plugin into a DSH profile.
 *
 * The DSH desktop app reads `%USERPROFILE%/.dsh/profiles/<profile>/package.json`
 * and its `cordis.patch.yml` at boot (and the loader watches them). This script
 * performs the two package.json edits the handoff doc requires, with a backup,
 * and prints the follow-up commands. It never restarts DSH.
 *
 * Usage (dry-run is the default):
 *   node tools/install-gateway.mjs
 *   node tools/install-gateway.mjs --profile desktop --apply
 *   node tools/install-gateway.mjs --remove --apply
 *   node tools/install-gateway.mjs --plugin D:/other/dsh-gateway --apply
 */

import { copyFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const WORKSPACE = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const argv = process.argv.slice(2)

/** Read `--flag value` pairs and boolean flags. */
function parseArgs(args) {
  const options = { profile: 'desktop', apply: false, remove: false, plugin: join(WORKSPACE, 'dsh-gateway') }
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index]
    if (arg === '--apply') options.apply = true
    else if (arg === '--remove') options.remove = true
    else if (arg === '--profile') options.profile = args[++index]
    else if (arg === '--plugin') options.plugin = resolve(args[++index])
    else if (arg === '--help' || arg === '-h') {
      console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('*/')[0])
      process.exit(0)
    } else {
      console.error(`unknown argument: ${arg}`)
      process.exit(2)
    }
  }
  return options
}

const options = parseArgs(argv)
const dshHome = process.env.DSH_HOME?.trim() || join(homedir(), '.dsh')
const profileDir = join(dshHome, 'profiles', options.profile)
const packageFile = join(profileDir, 'package.json')
const pluginDir = resolve(options.plugin)
const linkSpec = `link:${pluginDir.replaceAll('\\', '/')}`

if (!existsSync(packageFile)) {
  console.error(`profile package.json not found: ${packageFile}`)
  process.exit(1)
}
if (!options.remove && !existsSync(join(pluginDir, 'package.json'))) {
  console.error(`plugin package.json not found: ${join(pluginDir, 'package.json')}`)
  process.exit(1)
}

const original = readFileSync(packageFile, 'utf8')
let profile
try {
  profile = JSON.parse(original)
} catch (error) {
  console.error(`profile package.json is not valid JSON: ${error.message}`)
  process.exit(1)
}

profile.dependencies ??= {}
profile.dsh ??= {}
profile.dsh.profile ??= {}
const bundles = Array.isArray(profile.dsh.profile.bundles) ? profile.dsh.profile.bundles : []
profile.dsh.profile.bundles = bundles

const hadDependency = Object.hasOwn(profile.dependencies, 'dsh-gateway')
const hadBundle = bundles.includes('dsh-gateway')

if (options.remove) {
  delete profile.dependencies['dsh-gateway']
  profile.dsh.profile.bundles = bundles.filter((bundle) => bundle !== 'dsh-gateway')
} else {
  profile.dependencies['dsh-gateway'] = linkSpec
  if (!hadBundle) profile.dsh.profile.bundles.push('dsh-gateway')
}

const next = `${JSON.stringify(profile, null, 2)}\n`
const changed = next !== original

console.log(`profile:   ${packageFile}`)
console.log(`plugin:    ${pluginDir}`)
console.log(`mode:      ${options.remove ? 'uninstall' : 'install'}${options.apply ? '' : ' (dry-run)'}`)
console.log(`dependency dsh-gateway: ${hadDependency ? 'present' : 'absent'} -> ${options.remove ? 'absent' : linkSpec}`)
console.log(`bundle dsh-gateway:     ${hadBundle ? 'present' : 'absent'} -> ${options.remove ? 'absent' : 'present'}`)

if (!changed) {
  console.log('package.json already in the requested state; nothing to write.')
  process.exit(0)
}
if (!options.apply) {
  console.log('\n(dry-run) pass --apply to write the change. Then run:')
  console.log(`  cd "${profileDir}" && pnpm install`)
  console.log('  fully quit and restart DeepSeek Harness')
  process.exit(0)
}

const backup = join(profileDir, `package.json.dsh-gateway-backup-${Date.now()}`)
copyFileSync(packageFile, backup)
writeFileSync(packageFile, next, 'utf8')
console.log(`\nwrote ${packageFile}`)
console.log(`backup ${backup}`)
if (!options.remove) {
  console.log('\nnext:')
  console.log(`  cd "${profileDir}" && pnpm install`)
  console.log('  fully quit and restart DeepSeek Harness')
  console.log('  verify: curl http://127.0.0.1:7788/v1/health')
} else {
  console.log('\nnext:')
  console.log(`  cd "${profileDir}" && pnpm install`)
  console.log('  fully quit and restart DeepSeek Harness')
}
