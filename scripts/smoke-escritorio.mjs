/**
 * Prueba de arranque de la aplicación de escritorio.
 *
 * Existe por un fallo real: al refactorizar `electron.js` se perdieron dos
 * funciones y la app dejó de abrirse. Ni `node --check`, ni los tests
 * unitarios, ni el empaquetado lo detectaron, porque era un error de
 * ejecución. Lo único que lo delata es arrancarla y mirar.
 *
 *   npm run test:arranque
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const raiz = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const empaquetada = path.join(raiz, 'dist-desktop', 'Lumina-win32-x64', 'Lumina.exe')

const SEGUNDOS = 15
// Señales de que el proceso principal ha reventado al arrancar.
const SINTOMAS = /ReferenceError|TypeError|SyntaxError|Cannot find module|is not defined|UnhandledPromiseRejection/i

async function main() {
  let comando
  let argumentos
  if (fs.existsSync(empaquetada)) {
    comando = empaquetada
    argumentos = []
    console.log('Probando la aplicación empaquetada')
  } else {
    const { default: electron } = await import('electron')
    comando = electron
    argumentos = ['.']
    console.log('Probando con electron . (no hay empaquetado)')
  }

  const proceso = spawn(comando, argumentos, { cwd: raiz, stdio: ['ignore', 'pipe', 'pipe'] })
  let salida = ''
  proceso.stdout.on('data', (d) => (salida += d))
  proceso.stderr.on('data', (d) => (salida += d))

  let terminoSolo = false
  proceso.on('exit', () => (terminoSolo = true))

  await new Promise((r) => setTimeout(r, SEGUNDOS * 1000))

  const problemas = []
  if (terminoSolo) problemas.push('la aplicación se cerró sola nada más abrirse')
  const sintoma = salida.match(SINTOMAS)
  if (sintoma) {
    problemas.push(`error en el arranque: ${sintoma[0]}`)
  }

  if (!terminoSolo) proceso.kill()

  if (problemas.length) {
    console.error('\nFALLA el arranque:')
    for (const p of problemas) console.error(`  - ${p}`)
    if (salida.trim()) console.error(`\nSalida del proceso:\n${salida.trim().split('\n').slice(0, 20).join('\n')}`)
    process.exit(1)
  }

  console.log(`  OK    sigue viva tras ${SEGUNDOS} s`)
  console.log('  OK    sin errores en el proceso principal')
  console.log('\nArranque correcto')
}

main()
