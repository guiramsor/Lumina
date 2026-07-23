import { app, protocol, net, BrowserWindow } from 'electron';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { registrarEsquema, servirAudio, urlDeAudio } from '../../audioProtocol.js';

/**
 * Verifica el protocolo `lumina://` dentro de Electron de verdad.
 *
 * Lo que se comprueba, y por qué importa: sin soporte de rangos, saltar a la
 * hora siete de un audiolibro de 1,7 GB obligaría a leerlo entero. Es un fallo
 * que no da error, solo lentitud, así que conviene tenerlo bajo prueba.
 *
 *   npm run test:protocolo
 */

registrarEsquema();

let fallos = 0;

function comprobar(etiqueta, obtenido, esperado) {
  const bien = String(obtenido) === String(esperado);
  if (!bien) fallos++;
  console.log(`  ${bien ? 'OK  ' : 'FALLA'}  ${etiqueta}${bien ? '' : `  (esperado ${esperado}, obtenido ${obtenido})`}`);
}

app.whenReady().then(async () => {
  protocol.handle('lumina', servirAudio);

  // Archivo de prueba con contenido conocido: 1000 bytes 0..255 repetidos.
  const ruta = path.join(os.tmpdir(), `lumina-protocolo-${process.pid}.mp3`);
  const datos = Buffer.from(Array.from({ length: 1000 }, (_, i) => i % 256));
  fs.writeFileSync(ruta, datos);

  try {
    console.log('Protocolo lumina://');

    const completa = await net.fetch(urlDeAudio(ruta));
    const cuerpo = Buffer.from(await completa.arrayBuffer());
    comprobar('descarga completa: estado', completa.status, 200);
    comprobar('descarga completa: tamaño', cuerpo.length, 1000);
    comprobar('descarga completa: contenido intacto', cuerpo.equals(datos), true);
    comprobar('anuncia soporte de rangos', completa.headers.get('Accept-Ranges'), 'bytes');
    comprobar('tipo de contenido', completa.headers.get('Content-Type'), 'audio/mpeg');

    // Un salto en la reproducción se traduce en una petición como esta.
    const parcial = await net.fetch(urlDeAudio(ruta), { headers: { Range: 'bytes=100-199' } });
    const trozo = Buffer.from(await parcial.arrayBuffer());
    comprobar('rango: estado 206', parcial.status, 206);
    comprobar('rango: tamaño', trozo.length, 100);
    comprobar('rango: Content-Range', parcial.headers.get('Content-Range'), 'bytes 100-199/1000');
    comprobar('rango: bytes correctos', trozo.equals(datos.subarray(100, 200)), true);

    // Rango abierto: "desde aquí hasta el final".
    const abierto = await net.fetch(urlDeAudio(ruta), { headers: { Range: 'bytes=900-' } });
    comprobar('rango abierto: estado', abierto.status, 206);
    comprobar('rango abierto: tamaño', (await abierto.arrayBuffer()).byteLength, 100);

    const fuera = await net.fetch(urlDeAudio(ruta), { headers: { Range: 'bytes=5000-6000' } });
    comprobar('rango imposible: 416', fuera.status, 416);

    const inexistente = await net.fetch(urlDeAudio(path.join(os.tmpdir(), 'no-existe-jamas.mp3')));
    comprobar('archivo ausente: 404', inexistente.status, 404);

    // El preload corre con sandbox activado; conviene confirmar que aun asi
    // puede exponer webUtils, que es de donde sale la ruta de los archivos.
    console.log('\nPuente del preload (con sandbox)');
    const ventana = new BrowserWindow({
      show: false,
      webPreferences: {
        preload: path.join(import.meta.dirname, '..', '..', 'preload.js'),
        contextIsolation: true,
        sandbox: true,
        nodeIntegration: false,
      },
    });
    await ventana.loadURL('about:blank');
    const puente = await ventana.webContents.executeJavaScript(
      `({
        existe: typeof window.lumina === 'object',
        rutaDeArchivo: typeof window.lumina?.rutaDeArchivo,
        existeArchivo: typeof window.lumina?.existe,
        url: window.lumina?.urlDeAudio?.('C:/audios/x y.mp3'),
      })`
    );
    comprobar('window.lumina expuesto', puente.existe, true);
    comprobar('rutaDeArchivo disponible', puente.rutaDeArchivo, 'function');
    comprobar('existe() disponible', puente.existeArchivo, 'function');
    comprobar('urlDeAudio escapa la ruta', puente.url, 'lumina://audio/?p=C%3A%2Faudios%2Fx%20y.mp3');

    // El elemento <audio> lee desde la interfaz, no desde el proceso
    // principal: es otro camino y depende de los privilegios del esquema.
    const desdeLaInterfaz = await ventana.webContents.executeJavaScript(`
      fetch(${JSON.stringify(urlDeAudio(ruta))}, { headers: { Range: 'bytes=0-49' } })
        .then(async (r) => ({ estado: r.status, bytes: (await r.arrayBuffer()).byteLength }))
        .catch((e) => ({ estado: 'error', bytes: String(e) }))
    `);
    comprobar('la interfaz puede leer el audio', desdeLaInterfaz.estado, 206);
    comprobar('la interfaz recibe el rango pedido', desdeLaInterfaz.bytes, 50);

    ventana.destroy();
  } catch (error) {
    console.error('  ERROR inesperado:', error);
    fallos++;
  } finally {
    fs.rmSync(ruta, { force: true });
  }

  console.log(fallos === 0 ? '\nTodo correcto' : `\n${fallos} comprobaciones fallidas`);
  app.exit(fallos === 0 ? 0 : 1);
});
