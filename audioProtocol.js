import fs from 'fs';
import path from 'path';
import { Readable } from 'stream';
import { protocol } from 'electron';

/**
 * Protocolo `lumina://` para servir los audiolibros desde su ubicación
 * original, sin copiarlos a ninguna parte.
 *
 * La alternativa, `file://` directo, no sirve: en desarrollo la interfaz se
 * carga por http y Chromium bloquea los subrecursos `file://`. Con un
 * protocolo propio funciona igual en desarrollo y en la app empaquetada.
 *
 * El soporte de rangos no es un adorno: sin él, saltar al minuto 400 de un MP3
 * de 1,7 GB obligaría a leerlo entero desde el principio.
 */

const TIPOS = {
  '.mp3': 'audio/mpeg',
  '.m4a': 'audio/mp4',
  '.m4b': 'audio/mp4',
  '.mp4': 'audio/mp4',
  '.aac': 'audio/aac',
  '.ogg': 'audio/ogg',
  '.oga': 'audio/ogg',
  '.opus': 'audio/ogg',
  '.wav': 'audio/wav',
  '.flac': 'audio/flac',
  '.webm': 'audio/webm',
  '.weba': 'audio/webm',
  '.mka': 'audio/x-matroska',
};

export function tipoDe(ruta) {
  return TIPOS[path.extname(ruta).toLowerCase()] || 'application/octet-stream';
}

/** Debe llamarse antes de que la aplicación esté lista. */
export function registrarEsquema() {
  protocol.registerSchemesAsPrivileged([
    {
      scheme: 'lumina',
      privileges: {
        standard: true,
        secure: true,
        supportFetchAPI: true,
        stream: true,
        bypassCSP: true,
        // Sin esto Electron no procesa las cabeceras CORS del esquema y la
        // interfaz no puede leer el audio, por mucho que se las enviemos.
        corsEnabled: true,
      },
    },
  ]);
}

export function urlDeAudio(ruta) {
  return `lumina://audio/?p=${encodeURIComponent(ruta)}`;
}

/**
 * `lumina://` es un origen distinto al de la interfaz (file:// en la app
 * empaquetada, http://localhost en desarrollo), así que sin esta cabecera el
 * navegador bloquea la lectura por CORS. Abrirlo no supone un riesgo real
 * porque la aplicación nunca carga contenido remoto: solo su propia interfaz.
 */
const CORS = { 'Access-Control-Allow-Origin': '*' };

export function servirAudio(request) {
  const ruta = decodeURIComponent(new URL(request.url).searchParams.get('p') || '');
  if (!ruta) return new Response('Ruta vacía', { status: 400, headers: CORS });

  let info;
  try {
    info = fs.statSync(ruta);
  } catch {
    // El archivo se movió o se borró desde que se importó el libro.
    return new Response('No encontrado', { status: 404, headers: CORS });
  }

  const tipo = tipoDe(ruta);
  const rango = request.headers.get('Range');

  if (!rango) {
    return new Response(Readable.toWeb(fs.createReadStream(ruta)), {
      status: 200,
      headers: {
        'Content-Type': tipo,
        'Content-Length': String(info.size),
        'Accept-Ranges': 'bytes',
        ...CORS,
      },
    });
  }

  const coincidencia = /bytes=(\d*)-(\d*)/.exec(rango);
  const inicio = coincidencia?.[1] ? Number(coincidencia[1]) : 0;
  const fin = coincidencia?.[2] ? Math.min(Number(coincidencia[2]), info.size - 1) : info.size - 1;

  if (inicio >= info.size || inicio > fin) {
    return new Response('Rango no satisfacible', {
      status: 416,
      headers: { 'Content-Range': `bytes */${info.size}`, ...CORS },
    });
  }

  return new Response(Readable.toWeb(fs.createReadStream(ruta, { start: inicio, end: fin })), {
    status: 206,
    headers: {
      'Content-Type': tipo,
      'Content-Length': String(fin - inicio + 1),
      'Content-Range': `bytes ${inicio}-${fin}/${info.size}`,
      'Accept-Ranges': 'bytes',
      ...CORS,
    },
  });
}
