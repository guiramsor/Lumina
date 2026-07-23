# Lumina

Reproductor de audiolibros para **Windows y Android**, con la posición de
escucha sincronizada entre ambos. Pausas en el ordenador a las siete horas,
abres el móvil y sigue justo ahí.

Los audios **nunca salen de tus dispositivos**. Lo único que viaja por la red
son unos pocos bytes con la posición.

| | |
| --- | --- |
| **Escritorio** | React 19 + Vite + Electron · datos en IndexedDB |
| **Móvil** | Kotlin + Jetpack Compose + Media3 · datos en local |
| **Sincronización** | Supabase (opcional; sin ella todo funciona igual, solo que en local) |

## Características

### Comunes a las dos apps

- **Biblioteca con portadas**, búsqueda y orden por recientes, título, autor o progreso.
- **Continuar escuchando**: el último libro, con su portada y su porcentaje.
- **Rebobinado inteligente** al estilo Audible: cuanto más tiempo llevas sin
  escuchar, más atrás retoma para que recuperes el hilo (de 5 a 30 segundos).
- **Velocidad por libro**, recordada de forma independiente para cada uno.
- **Temporizador de sueño** con desvanecido en los últimos 12 segundos.
- **Marcadores** con nota y **estadísticas** de escucha con racha de días.
- **Teclas multimedia** del sistema y controles en la notificación u overlay.
- **Color de la interfaz extraído de la portada** del libro en curso.

### Solo en el escritorio

- **Capítulos**: los embebidos del M4B o, si no los hay, una hoja `.cue` que
  acompañe al archivo.
- **Libros de varias pistas**: una carpeta de capítulos se agrupa como un libro.
- **Cuatro temas** (Noche, Medianoche, Brasa, Claro) y fondo reactivo.
- **Vinilo y libro 3D** como visualizaciones del reproductor.
- **Editor de libro**: título, autor, serie y portada.
- **Atajos de teclado**.

### Solo en el móvil

- **Reproducción en segundo plano** con la pantalla apagada, mediante un
  servicio de medios propio.
- **Biblioteca leída del teléfono** con MediaStore: concedes el permiso una vez
  y aparecen todos tus audios, sin buscarlos en el selector de documentos.
- **Deslizar hacia abajo** para releer la biblioteca.

## Instalación

**Windows** — descarga el zip de la [última release](../../releases/latest),
descomprime y ejecuta `Lumina.exe`. No necesita instalador.

**Android** — no se publica APK en las releases: el que se compila en local
lleva incrustadas las credenciales de Supabase. Compílalo tú con
`cd android && ./gradlew installDebug`.

O compila el escritorio tú mismo:

```bash
npm install
npm run dist
```

El ejecutable queda en `dist-desktop/Lumina-win32-x64/`.

## Sincronización entre dispositivos

Cada dispositivo guarda su **propia copia** del archivo de audio. Los libros se
emparejan por una **huella calculada del contenido**, no por su nombre ni sus
etiquetas, así que la misma copia se reconoce en los dos sitios.

```
huella de pista = SHA-256( primer MiB || último MiB || tamaño en ASCII )
huella de libro = SHA-256( huellas de sus pistas, ordenadas y unidas por \n )
```

Solo se leen 2 MiB por archivo: un M4B de 1,76 GB se resuelve en unos 15 ms. El
algoritmo, la tabla y las reglas de resolución de conflictos están en
[docs/SYNC.md](docs/SYNC.md), y hay **vectores congelados** que verifican en CI
que JavaScript y Kotlin producen los mismos hashes. Si dejaran de coincidir, los
dos dispositivos no se reconocerían y la compilación falla antes de publicar.

### Activarla

1. Crea un proyecto gratuito en [supabase.com](https://supabase.com).
2. En el SQL Editor, ejecuta [`supabase/schema.sql`](supabase/schema.sql).
3. En Authentication → Users, créate un usuario.
4. Copia `.env.example` a `.env.local` con las credenciales de tu proyecto
   (Project Settings → API) y recompila.

`.env.local` está en el `.gitignore`, así que tus credenciales no llegan al
repositorio. Como consecuencia, **el `.exe` de las releases de GitHub sale sin
sincronización**: solo la llevan las compilaciones locales.

## Desarrollo

```bash
npm run electron:dev
```

Levanta Vite y abre Electron con recarga en caliente: los cambios en `src/` se
ven al instante, sin recompilar.

| Script | Qué hace |
| --- | --- |
| `npm run electron:dev` | Vite + Electron con HMR (desarrollo diario) |
| `npm run dev` | Solo Vite, para probar en el navegador |
| `npm run test` | Tests de la huella de audiolibros |
| `npm run electron:build` | Compila y abre Electron sobre el build de producción |
| `npm run dist` | Genera el ejecutable en `dist-desktop/` |

Para el móvil, desde `android/`:

```bash
./gradlew installDebug          # compilar e instalar en el dispositivo
./gradlew testDebugUnitTest     # tests
```

Cada push a `main` compila el ejecutable de Windows y publica una release, y
ejecuta los tests de las dos plataformas.

## Atajos de teclado (escritorio)

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
src/                     # Aplicación de escritorio
├── lib/                     # Lógica pura, sin React
│   ├── db.js                    # IndexedDB: libros, progreso, marcadores, stats
│   ├── metadata.js              # Etiquetas, portada y paleta de color
│   ├── cue.js                   # Lectura de hojas .cue
│   ├── importBooks.js           # Agrupar archivos en libros
│   ├── fingerprint.js           # Identidad portable de los libros
│   └── sync.js                  # Cliente de Supabase
├── player/PlayerContext.jsx # Toda la máquina de reproducción
└── components/              # Biblioteca, reproductor y paneles

android/app/src/main/java/com/lumina/audiolibros/
├── sync/                    # Huella (misma spec) y cliente REST de Supabase
├── library/                 # MediaStore, etiquetas y portadas
├── data/AlmacenLocal.kt     # Progreso, marcadores y estadísticas
├── player/PlaybackService.kt# Servicio de medios en segundo plano
└── ui/                      # Pantallas, iconos y tema

docs/SYNC.md             # Contrato compartido entre ambas apps
supabase/schema.sql      # Tabla de progreso con RLS
test/                    # Tests de la huella, en Node y en la JVM
```

## Dónde viven los audios

Ninguna de las dos apps copia tus audiolibros: **se reproducen desde donde
están**. En el escritorio la biblioteca guarda la ruta del archivo y un
protocolo propio, `lumina://`, lo sirve con soporte de rangos, que es lo que
permite saltar a la hora siete de un archivo de 1,7 GB sin leerlo entero.

Si mueves o borras un audio, el libro te avisa al abrirlo. Reimportarlo desde
su nueva ubicación lo actualiza en su sitio: como los libros se identifican por
su huella, conservas el progreso y los marcadores en lugar de crear un
duplicado.

## Limitaciones conocidas

- El móvil trata cada archivo como un libro: no agrupa carpetas de capítulos ni
  lee capítulos embebidos.
- Solo se empaqueta para Windows x64.
