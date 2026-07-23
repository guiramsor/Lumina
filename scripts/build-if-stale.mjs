/**
 * Recompila la aplicación solo si hace falta.
 *
 * Compara la fecha del código fuente con la del ejecutable ya empaquetado y
 * lanza `npm run dist` únicamente cuando el código es más nuevo. Pensado para
 * ejecutarse desde el hook Stop de Claude Code: en la mayoría de turnos no hay
 * cambios y el script sale en milisegundos.
 *
 * Escribe en stdout un JSON con `systemMessage` para avisar del resultado.
 */
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const EXE = path.join(root, 'dist-desktop', 'Lumina-win32-x64', 'Lumina.exe')
// .env.local entra aquí porque Vite incrusta sus valores en el build: cambiar
// las credenciales de sincronización obliga a reempaquetar la aplicación.
const WATCHED = ['src', 'electron.js', 'index.html', 'package.json', 'vite.config.js', '.env.local']

const say = (systemMessage) => {
  process.stdout.write(JSON.stringify({ systemMessage, suppressOutput: true }))
  process.exit(0)
}

/** mtime más reciente de un archivo o de un directorio, recursivamente. */
function newestMtime(target) {
  let stat
  try {
    stat = fs.statSync(target)
  } catch {
    return 0
  }
  if (!stat.isDirectory()) return stat.mtimeMs
  let newest = 0
  for (const entry of fs.readdirSync(target)) {
    newest = Math.max(newest, newestMtime(path.join(target, entry)))
  }
  return newest
}

const sourceMtime = Math.max(...WATCHED.map((rel) => newestMtime(path.join(root, rel))))
const builtMtime = newestMtime(EXE)

// Nada que hacer: el ejecutable ya incluye los últimos cambios.
if (builtMtime && builtMtime >= sourceMtime) process.exit(0)

const started = Date.now()
const result = spawnSync('npm', ['run', 'dist'], {
  cwd: root,
  stdio: ['ignore', 'pipe', 'pipe'],
  shell: true, // npm es un .cmd en Windows
  encoding: 'utf8',
})

const seconds = Math.round((Date.now() - started) / 1000)

if (result.status === 0) {
  say(`Lumina recompilada en ${seconds}s → dist-desktop/Lumina-win32-x64/Lumina.exe`)
}

const err = (result.stderr || result.stdout || result.error?.message || '').trim()
say(`No se pudo recompilar Lumina (${seconds}s). Últimas líneas:\n${err.split('\n').slice(-12).join('\n')}`)
