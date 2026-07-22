# Lumina 🎧

Reproductor de audiolibros de escritorio para Windows. Arrastra un MP3, un M4B o
una carpeta de capítulos y Lumina lee las etiquetas, extrae la portada, ordena
las pistas y recuerda dónde lo dejaste.

Construido con React 19 + Vite + Electron. Todo funciona sin conexión y nada sale
de tu equipo: la biblioteca vive en IndexedDB, en local.

## Características

- **Importación inteligente** — archivos sueltos, carpetas o carpetas con varios
  audiolibros dentro: se separan por subcarpeta y etiqueta de álbum, y se ordenan
  con orden natural (`Capítulo 2` antes que `Capítulo 10`).
- **Capítulos de verdad** — usa los capítulos embebidos del M4B; si no los hay,
  lee una hoja `.cue` que acompañe al archivo; si tampoco, cada pista es un capítulo.
- **Retoma donde lo dejaste** — progreso guardado por libro y *rebobinado
  inteligente*: cuanto más tiempo pasa sin escuchar, más atrás retoma (de 5 a 30 s).
- **Temporizador de sueño** — por minutos, con desvanecido en los últimos 12 s, o
  «hasta el final del capítulo».
- **Marcadores** con nota, lista de capítulos y estadísticas de escucha por día.
- **Velocidad por libro** (0,5× a 3×), recordada de forma independiente para cada uno.
- **Teclas multimedia del sistema** y controles en el overlay de Windows.
- **Interfaz reactiva** — el color de la app se extrae de la portada del libro, con
  cuatro temas (Noche, Medianoche, Brasa, Claro) y visualización en vinilo o libro 3D.

## Instalación

Descarga el `.exe` desde la [última release](../../releases/latest), descomprime y
ejecuta `Lumina.exe`. No necesita instalador.

O compílalo tú mismo:

```bash
npm install
npm run dist
```

El ejecutable queda en `dist-desktop/Lumina-win32-x64/`.

## Desarrollo

```bash
npm run electron:dev
```

Levanta Vite y abre Electron con recarga en caliente: los cambios en `src/` se ven
al instante, sin recompilar.

| Script | Qué hace |
| --- | --- |
| `npm run electron:dev` | Vite + Electron con HMR (desarrollo diario) |
| `npm run dev` | Solo Vite, para probar en el navegador |
| `npm run electron:build` | Compila y abre Electron sobre el build de producción |
| `npm run dist` | Genera el ejecutable en `dist-desktop/` |

## Atajos de teclado

| Tecla | Acción |
| --- | --- |
| `Espacio` · `K` | Reproducir / pausar |
| `←` · `J` | Atrás 15 s |
| `→` · `L` | Adelante 30 s |
| `↑` · `↓` | Subir / bajar volumen |
| `[` · `]` | Capítulo anterior / siguiente |
| `,` · `.` | Más lento / más rápido |
| `?` | Ver todos los atajos |

## Formatos

MP3, M4A, M4B, MP4, AAC, OGG, OPUS, WAV, FLAC, WEBM y MKA.

WMA, AIFF y los formatos protegidos de Audible (AAX/AA) no están soportados:
Chromium no puede decodificarlos, así que se descartan al importar en lugar de
añadirse y fallar al reproducir.

## Estructura

```
src/
├── lib/            # Lógica pura, sin React
│   ├── db.js           # IndexedDB: libros, progreso, marcadores, ajustes, stats
│   ├── metadata.js     # Etiquetas, portada y extracción de la paleta de color
│   ├── cue.js          # Lectura de hojas .cue
│   ├── importBooks.js  # Agrupar archivos en libros
│   └── theme.js        # Temas de la interfaz
├── player/
│   ├── PlayerContext.jsx      # Toda la máquina de reproducción
│   └── useKeyboardShortcuts.js
└── components/     # Biblioteca, reproductor y paneles
```

## Limitaciones conocidas

- El audio importado se copia dentro de IndexedDB, así que ocupa el doble en disco.
  Pendiente: guardar la ruta del archivo y reproducirlo por `file://`.
- Solo se empaqueta para Windows x64.
