# Contexto del proyecto Lumina

Documento de traspaso: todo lo necesario para retomar el trabajo sin haber
estado en las sesiones anteriores. Escrito el 29 de julio de 2026.

---

## 1. Qué es

**Lumina** es un reproductor de audiolibros personal, con dos aplicaciones que
comparten la posición de escucha: pausas en el ordenador a las siete horas,
abres el móvil y sigue justo ahí.

| | |
| --- | --- |
| **Repositorio** | `https://github.com/guiramsor/Lumina` · rama `main` · público |
| **Carpeta local** | `C:\Users\GuilleRS\Desktop\AudioPlayerRecorder` |
| **Escritorio** | React 19 + Vite 8 + Electron 42 · datos en IndexedDB |
| **Móvil** | Kotlin 2.2 + Jetpack Compose + Media3 1.10.1 · AGP 9.3 · minSdk 26 |
| **Sincronización** | Supabase (proyecto `LuminaDB`), solo la posición |
| **Usuario** | Habla español. Prefiere explicaciones directas y verificación real, no teórica. |

**Principio de diseño central:** los audios **nunca** se copian ni se suben.
Cada dispositivo reproduce desde donde el archivo ya está, y por la red solo
viajan unos bytes con la posición.

---

## 2. Entorno de la máquina (no estándar — importante)

| Cosa | Dónde |
| --- | --- |
| Android Studio | `E:\AppLibrary\AndroidStudio` (**no** en Program Files) |
| JDK | `E:\AppLibrary\AndroidStudio\jbr` → hay que exportar `JAVA_HOME` para usar `gradlew` |
| SDK de Android | `%LOCALAPPDATA%\Android\Sdk` (ruta normal) |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |
| Móvil | Pixel 7 (`panther`), Android 17 |
| gh CLI | instalado y autenticado como `guiramsor` |

No hay Gradle ni Java fuera de Android Studio. Se dispone de Bash (Git Bash) y
PowerShell; los comandos largos con *here-doc* funcionan mejor en Bash.

### Depuración del móvil

El **cable USB no transmite datos** (Windows da «error de descriptor»), así que
se usa **depuración inalámbrica**. Trucos aprendidos a base de tropezar:

```bash
# Descubrir el móvil (tras el primer emparejamiento reaparece solo por mDNS)
adb mdns services
adb devices -l
```

- Suelen aparecer **dos entradas del mismo teléfono** (por IP y por mDNS) y adb
  falla con *«more than one device»*. Hay que fijar una:
  `adb -s adb-28191FDH200DWN-N5RE9i._adb-tls-connect._tcp ...`
- **Git Bash convierte las rutas de Android** (`/sdcard/...`) en rutas de
  Windows. Solución: `export MSYS_NO_PATHCONV=1` antes de usar adb.
- Al **apagar la pantalla el móvil se desconecta** (el Wi-Fi se duerme). Para
  medir algo con la pantalla apagada hay que hacerlo sin conexión y reconectar
  después.
- Si el móvil está **bloqueado con PIN** no se puede manejar la interfaz; hay
  que pedir al usuario que lo desbloquee.

```bash
# Leer los datos locales de la app (es depurable)
adb -s $DEV shell run-as com.lumina.audiolibros \
  cat /data/data/com.lumina.audiolibros/shared_prefs/lumina_datos.xml
```

### Depuración del escritorio

La app de Electron se puede inspeccionar por el protocolo de Chrome DevTools:

```bash
"dist-desktop/Lumina-win32-x64/Lumina.exe" --remote-debugging-port=9223
# luego, desde Node: conectar al WebSocket de http://127.0.0.1:9223/json
```

Con eso se puede leer IndexedDB, pulsar botones de la interfaz
(`document.querySelector('.book-card-cover').click()`) e incluso simular la
importación de archivos con `DOM.setFileInputFiles`. Notas:

- El **token de Supabase caduca** y el guardado en `localStorage` se queda
  viejo; se renueva haciendo que la app hable con la nube (abrir el panel de
  sincronización o un libro).
- En Node, el marco del WebSocket a veces llega como Blob:
  `typeof e.data === 'string' ? e.data : await new Response(e.data).text()`.
- **Solo se admite una instancia**: si hay una Lumina abierta, la siguiente se
  cierra sola. Hay que cerrarla antes de lanzar pruebas.

---

## 3. Estructura

```
src/                          # Escritorio
├── lib/
│   ├── db.js                 # IndexedDB: libros, progreso, marcadores, ajustes, stats
│   ├── metadata.js           # Etiquetas, portada y paleta de color
│   ├── cue.js                # Hojas .cue (capítulos de un M4B sin capítulos)
│   ├── importBooks.js        # Agrupar archivos sueltos/carpetas en libros
│   ├── fingerprint.js        # ← Identidad portable del libro (contrato)
│   ├── emparejar.js          # ← Reglas de emparejamiento y de quién gana (contrato)
│   ├── archivos.js           # Detectar audios que ya no están en su ruta
│   ├── bookIdentity.js       # Completa huellas de libros antiguos
│   └── sync.js               # Cliente de Supabase + reconciliación
├── player/PlayerContext.jsx  # Toda la máquina de reproducción
└── components/               # Biblioteca, reproductor, paneles

electron.js                   # Proceso principal, instancia única
preload.js                    # Puente mínimo: ruta de un archivo, existe()
audioProtocol.js              # Protocolo lumina:// con soporte de rangos

android/app/src/main/java/com/lumina/audiolibros/
├── sync/
│   ├── Fingerprint.kt        # ← misma spec que fingerprint.js
│   ├── EmparejarLibros.kt    # ← misma spec que emparejar.js
│   ├── SupabaseSync.kt       # Cliente REST (sin SDK) + reconciliación
│   └── UriSource.kt          # Huella sobre un content:// sin copiar
├── library/
│   ├── AudioLibrary.kt       # MediaStore + caché de escaneo + filtro de carpeta
│   └── Metadatos.kt          # Etiquetas, portada, color, portada para la sesión
├── data/AlmacenLocal.kt      # Progreso, marcadores, stats, ajustes, syncIds
├── player/
│   ├── PlaybackService.kt    # MediaLibraryService (segundo plano + Android Auto)
│   └── Catalogo.kt           # Catálogo compartido por la pantalla y el coche
└── ui/                       # Pantallas, iconos portados, tema

docs/SYNC.md                  # CONTRATO de sincronización (leer antes de tocar nada)
supabase/schema.sql           # Tabla progress con RLS
```

---

## 4. Cómo se sincroniza (resumen; el detalle está en `docs/SYNC.md`)

### Dos identidades que no hay que confundir

- **Huella** = identifica *un archivo*. `SHA-256(primer MiB ‖ último MiB ‖ tamaño en ASCII)`.
  Solo lee 2 MiB, así que un M4B de 1,76 GB se resuelve en ~15 ms.
- **`syncId`** = identifica *la fila de la nube*. Empieza siendo la huella, pero
  cuando un dispositivo reconoce el libro del otro **adopta el suyo**. Sin esa
  adopción cada uno escribiría en su propia fila y no volverían a encontrarse.

### Reglas

1. Gana la escucha **más avanzada**, no la más reciente (margen de 5 s).
2. No se pisa una posición más avanzada… **salvo que la haya elegido el
   usuario** (barra, saltos, marcador, capítulo, reabrir un libro terminado).
   Sin esa excepción, retroceder media hora no sobrevivía. Elegir a mano
   también **levanta la marca de final**: si no, mover la barra después de que
   el libro acabara dejaba de guardar hasta en el disco.
3. Si la lectura de la nube **falla**, no se sube nada. «No hay fila» y «no he
   podido leer» son casos distintos.
4. Si las huellas no coinciden (copias recodificadas o etiquetas editadas), se
   empareja **por duración** (tolerancia 0,2 %, mínimo 10 s) y desempata el
   título normalizado. Ante la duda, no se empareja nada.
5. Si aparecen varias filas del mismo libro, se conserva la posición más
   avanzada y se retiran las sobrantes; sobrevive la del identificador menor
   (criterio determinista para que ambos elijan la misma).

**`fingerprint.js` ↔ `Fingerprint.kt` y `emparejar.js` ↔ `EmparejarLibros.kt`
son la misma especificación en dos lenguajes.** Cualquier cambio hay que
hacerlo en los dos, y hay vectores congelados que lo verifican en CI.

---

## 5. Dónde viven los audios

**Escritorio:** la biblioteca guarda la **ruta** del archivo. `preload.js`
resuelve la ruta con `webUtils.getPathForFile` (desde Electron 32 `File.path` ya
no existe) y `audioProtocol.js` sirve el archivo por `lumina://` con soporte de
rangos —imprescindible para saltar a la hora 7 de un archivo de 1,7 GB—.

Dos detalles que costaron encontrar y conviene no deshacer:

- El esquema necesita `corsEnabled: true` **y** cabeceras CORS. Sin ellas el
  audio no suena y **no da ningún error**.
- El elemento `<audio>` necesita `crossOrigin = 'anonymous'`. Sin eso la Web
  Audio API (que alimenta el visualizador) devuelve **silencio absoluto**
  mientras el tiempo avanza con normalidad.

**Móvil:** los audios se copian a mano al teléfono y se leen con MediaStore. Si
existe una carpeta de audiolibros se muestran solo sus archivos, con un botón
para alternar.

---

## 6. Comandos

```bash
# Escritorio
npm run electron:dev      # Vite + Electron con recarga en caliente
npm test                  # 30 tests: huella y emparejamiento
npm run test:protocolo    # 21 comprobaciones del protocolo, dentro de Electron
npm run test:arranque     # comprueba que la app empaquetada abre (sin otra abierta)
npm run dist              # genera dist-desktop/Lumina-win32-x64/

# Móvil (desde android/, con JAVA_HOME apuntando al jbr de Android Studio)
./gradlew testDebugUnitTest        # 103 tests
./gradlew installDebug             # compila e instala
./gradlew connectedDebugAndroidTest # prueba instrumentada del catálogo del coche
```

Un **hook `Stop`** en `.claude/settings.json` ejecuta `scripts/build-if-stale.mjs`,
que recompila el `.exe` solo si el código es más nuevo. El acceso directo del
escritorio (`C:\Users\GuilleRS\Desktop\Lumina.lnk`) apunta a él.

**CI:** cada push a `main` ejecuta los tests de ambas plataformas, comprueba el
arranque y publica una release con el zip de Windows.

---

## 7. Estado actual

### Funciona y está verificado en hardware real

- Sincronización PC ↔ móvil en ambos sentidos, con adopción de `syncId` y
  fusión de filas duplicadas.
- Reproducción en segundo plano con la pantalla apagada (medido: la posición
  avanza y las estadísticas crecen 100 s en 100 s).
- Audio servido desde su ubicación original, sin copias.
- **Android Auto**: catálogo navegable con portada, título y autor; al elegir un
  libro retoma donde se dejó consultando la nube.
- Una sola instancia del escritorio; libros terminados marcados; archivos
  perdidos señalados en la biblioteca.
- **Botón de volver tras un salto grande** (móvil): mover la barra más de un
  minuto deja doce segundos un «Volver a hh:mm:ss» debajo de ella. Los botones
  de 15 s y 30 s no lo disparan, a propósito. Regla en `SaltoGrande`.
- **Tocar la notificación abre el libro que suena** (móvil), no la biblioteca.
  Verificado con la aplicación en segundo plano y con la Activity ya destruida.

### Lo que falta / limitaciones conocidas

- **El móvil trata cada archivo como un libro**: no agrupa carpetas de capítulos
  ni lee capítulos embebidos. Es el hueco más grande frente al escritorio.
- El móvil no tiene: temas, editor de libro, series, visualizaciones.
- Solo se empaqueta para Windows x64.
- **El APK no se publica** en las releases a propósito: el compilado en local
  lleva incrustadas las credenciales de Supabase. Igualmente, el `.exe` de las
  releases de GitHub **sale sin sincronización**, porque `.env.local` no está
  versionado.

### Pendiente de decisión del usuario

- **«El Ritmo de la Guerra» está en 24 h 42 min 56 s (40 %).** Es una posición
  de pruebas, no la del usuario: se movió al verificar el botón de volver y el
  guardado después del final. La real eran 7 h 06 min 33 s. El usuario dijo que
  ya lo corregirá él al terminar las modificaciones.
- Para probar Android Auto hay que activar **«Fuentes desconocidas»** en las
  opciones de desarrollador de la app Android Auto (Lumina se instala fuera de
  Google Play). Sin eso no aparece, por muy bien que esté el código.

---

## 8. Credenciales

Están en **`.env.local`** en la raíz (no versionado, en `.gitignore`):

```
VITE_SUPABASE_URL=...
VITE_SUPABASE_ANON_KEY=...
```

El escritorio las lee con Vite; el móvil las inyecta en `BuildConfig` leyendo
**ese mismo archivo** desde `android/app/build.gradle.kts`, para no mantenerlas
en dos sitios. Cambiarlas obliga a recompilar ambas apps.

La clave es *publishable*: lo que protege los datos es la política RLS de
`supabase/schema.sql`, que solo deja ver el progreso de la propia cuenta.

---

## 9. Cómo se ha trabajado (recomendado mantener)

Lo que ha dado resultado en este proyecto, por si sirve de guía:

- **Verificar en hardware real, no razonar.** Varias sospechas resultaron
  infundadas al medirlas (el reloj con la pantalla apagada, el arrastre de
  carpetas), y varios fallos reales no daban ningún error: el audio mudo por
  CORS, la app que no abría por un refactor, la fila duplicada que rompía la
  sincronización al segundo uso.
- **Que las pruebas fallen cuando deben.** Al añadir la prueba de arranque se
  reintrodujo el fallo a propósito para comprobar que lo detectaba.
- **Limpiar lo que toquen las pruebas.** Se han creado y borrado filas en
  Supabase, archivos en el móvil y libros de prueba; conviene dejar el estado
  como estaba y decirlo.
- Los comentarios del código explican **por qué**, no qué. El usuario valora el
  detalle cuidado y las explicaciones sin adornos.
