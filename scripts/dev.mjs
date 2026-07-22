/**
 * Desarrollo con recarga en caliente: levanta el servidor de Vite y abre
 * Electron apuntando a él, en lugar de compilar a `dist/` en cada cambio.
 * La URL viaja por VITE_DEV_SERVER_URL, así que electron.js no tiene que
 * adivinar el puerto (Vite salta al siguiente si el 5173 está ocupado).
 */
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import electronPath from 'electron'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const server = await createServer({
  root,
  // El navegador sobra: la app se ve dentro de Electron.
  server: { open: false },
})
await server.listen()

const url = server.resolvedUrls?.local?.[0] ?? `http://localhost:${server.config.server.port}`
console.log(`\n  Vite listo en ${url}\n  Abriendo Lumina…\n`)

const electron = spawn(electronPath, ['.'], {
  cwd: root,
  stdio: 'inherit',
  env: { ...process.env, NODE_ENV: 'development', VITE_DEV_SERVER_URL: url },
})

let closing = false
const shutdown = async (code = 0) => {
  if (closing) return
  closing = true
  await server.close().catch(() => {})
  process.exit(code)
}

// Cerrar la ventana termina la sesión de desarrollo; Ctrl+C cierra ambos.
electron.on('close', (code) => shutdown(code ?? 0))
process.on('SIGINT', () => {
  electron.kill()
  shutdown(0)
})
