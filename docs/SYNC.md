# Contrato de sincronización

Este documento es la especificación que **la app de escritorio y la de Android
deben cumplir igual**. Si las dos implementaciones no coinciden byte a byte, los
libros no se emparejan y no hay sincronización.

## Idea general

Los audios **nunca** salen del dispositivo. Cada uno guarda su propia copia del
archivo y lo único que viaja por la red es la posición de escucha: unos pocos
bytes por libro.

Para saber que «ese MP3 del PC» y «ese MP3 del móvil» son el mismo libro se usa
una **huella digital calculada del contenido del archivo**, no su nombre ni sus
etiquetas (que pueden diferir entre copias).

## Huella digital

### Pista

```
huella_pista = SHA-256( primer_MiB || ultimo_MiB || tamaño_en_ASCII )
```

- `primer_MiB`: los primeros 1.048.576 bytes (o el archivo entero si es menor).
- `ultimo_MiB`: los últimos 1.048.576 bytes, **solo si no solapan** con el
  primer bloque. Si el archivo mide 2 MiB o menos, este bloque va vacío.
  Formalmente, el bloque final empieza en `max(longitud_del_primer_bloque, tamaño − 1 MiB)`.
- `tamaño_en_ASCII`: el tamaño en bytes escrito en decimal como texto ASCII.
  Un archivo de 5000 bytes aporta los caracteres `5` `0` `0` `0`.
- Resultado: 64 caracteres hexadecimales **en minúsculas**.

Solo se leen 2 MiB por archivo, así que un M4B de 1,76 GB se resuelve en ~11 ms.

### Libro

```
huella_libro = SHA-256( huellas_de_sus_pistas ordenadas alfabeticamente y unidas por "\n" )
```

El orden alfabético es deliberado: así la identidad del libro no depende de
cómo ordene las pistas cada plataforma. La posición exacta dentro del libro no
se pierde porque también se guarda la huella de la pista concreta.

### Vectores de referencia

La implementación en Kotlin **debe** reproducir estos valores. Están fijados en
`test/fingerprint.test.mjs`:

| Entrada | Huella esperada |
| --- | --- |
| Archivo de 10 bytes `00 01 02 03 04 05 06 07 08 09` | `83fe3c54f403ec66e809df9dceb0f308fa20394de604b54e9c1a59d805e2e5b7` |
| Libro con pistas `00×32` y `ff×32` (en hex) | `f7ee6e27721feb087d5ad6f99251059d05183104ae909d2b9830b12cadd4f822` |

El algoritmo ya está verificado en la JVM, que es lo que ejecutará Android:
`test/jvm/Huella.java` reproduce los vectores y, si se le pasa la ruta de un
audio, su huella. Sobre `El Ritmo de la Guerra` (1,76 GB) las tres
implementaciones —la app, los tests de Node y la JVM— coinciden en
`518b995ad39e66aa7f480ba96c1df69e48d0541ecdd72b47af8c76783b53388e`.

```
"E:\AppLibrary\AndroidStudio\jbr\bin\java.exe" test/jvm/Huella.java [ruta-de-un-audio]
```

## Almacenamiento

Tabla `public.progress` en Supabase (esquema completo en `supabase/schema.sql`).
Una fila por cuenta y libro:

| Columna | Significado |
| --- | --- |
| `user_id` | Cuenta; lo rellena Supabase y lo protege la política RLS |
| `book_id` | Huella del libro |
| `track_id` | Huella de la pista que se estaba escuchando |
| `position` | Segundos **dentro de esa pista** |
| `global_position` | Segundos desde el inicio del libro (para el porcentaje) |
| `duration` | Duración total del libro |
| `finished` | Si se terminó |
| `title`, `author` | Etiquetas del libro; **obligatorias**, ver regla 8 |
| `updated_at` | Momento **real de la escucha**, no el de la subida |

## Reglas de sincronización

1. **Gana la escucha más avanzada, no la más reciente.** Al abrir un libro se
   compara la posición local con la remota y se toma la mayor. Así ningún
   dispositivo puede hacer retroceder lo escuchado en el otro, que es el
   error que de verdad molesta: perder media hora de audiolibro.
2. **Margen de 5 segundos** para que una diferencia de un par de segundos no
   provoque un salto perceptible.
3. **Nunca se pisa una posición más avanzada.** Antes de subir se comprueba la
   última posición remota conocida: si va por delante de la nuestra, no se
   sube. Un dispositivo que se quedó atrás no borra el avance del otro.
4. **Lo que decide el usuario manda sobre la regla anterior.** Mover la barra,
   saltar 15 o 30 segundos, ir a un marcador o a un capítulo, o reabrir un
   libro terminado, marcan la posición como *intencionada*, y esa se sube
   aunque sea anterior a la de la nube.

   La regla 3 existe para que un dispositivo rezagado no borre el avance del
   otro **solo por abrir el libro**, no para impedirte decidir dónde quieres
   estar. Sin esta distinción, retroceder media hora porque te has perdido no
   sobrevivía: al reabrir, la nube te devolvía al punto adelantado.
5. **No se sube lo que no se ha podido leer.** Si la consulta a la nube falla,
   no se sube nada: sin saber por dónde va el otro dispositivo, escribir es
   una apuesta. «No hay fila» y «no he podido leer» son casos distintos y se
   tratan como tales.
6. **Los fallos de red nunca bloquean**: si no hay conexión, se escucha en
   local y se sube más tarde. La sincronización es una mejora, no un requisito.
7. **`position` manda sobre `global_position`**: para retomar se busca la pista
   por su `track_id` y se salta a `position`. `global_position` es solo
   informativo, porque depende del orden de las pistas.
8. **Nunca se escribe una fila sin `duration`, ni sin `title` y `author`.** No
   son adornos para depurar: la búsqueda por parecido filtra por rango de
   duración, así que una fila con `duration` nula **no la encuentra nadie** y
   deja el libro partido en dos para siempre; y `title`+`author` forman la
   clave con la que se desempata cuando dos libros duran casi lo mismo, de
   modo que si un dispositivo no manda el autor, su clave (`titulo|`) no
   coincide nunca con la del otro y el desempate deja de desempatar.
9. **El guardado pasa por un solo sitio.** En cada plataforma hay una única
   puerta que aplica estas reglas —`GuardadoDeProgreso` en Android,
   `persistProgress` en el escritorio— y ninguna otra ruta escribe en la nube.
   Ni el cierre de la app, ni el fin del libro, ni el arranque desde Android
   Auto. Una segunda puerta es una puerta que alguien se olvidará de cerrar.
10. **Quien comprueba que no se retrocede es el servidor.** Nadie escribe en la
    tabla `progress` directamente: se llama a la función `guardar_progreso`,
    que solo acepta la posición si va por delante de la que ya hay.

    La regla 3 no bastaba. Compara con la **última posición remota conocida**,
    y esa solo se refresca al abrir el libro y con las subidas del propio
    dispositivo: nunca se entera de lo que escribe el otro. Así que entre leer
    y escribir no cabe un instante, cabe **una sesión de escucha entera**.
    Bastaba con dejar el ordenador sonando y coger el móvil para que el móvil
    pisara al ordenador en cada subida durante horas. Se vio pasar: una fila
    bajó de 53828 s a 52452 s.

    En el servidor la comprobación es atómica —no hay hueco entre mirar y
    escribir— y ningún cliente puede saltársela por olvido.

    La excepción de la regla 4 viaja como el parámetro `p_incondicional`: eso
    solo lo sabe el cliente, porque depende de si has sido tú quien ha elegido
    la posición. `escrituraIncondicional` decide cuándo se activa, y está
    fijada con tests que comprueban que se abre **exactamente** por donde se
    abre `debeSubir`. Si las dos se separaran, habría posiciones que el cliente
    deja subir y el servidor rechaza.

### El orden de las pistas y la posición global

Un libro puede ser varios archivos, y **cada dispositivo puede repartirlo de
otra forma**: el mismo libro puede estar en el ordenador como doce capítulos y
en el móvil como un archivo único.

Por eso lo que viaja es la posición **global**: los segundos desde el principio
del libro, no desde el principio del archivo.

```
global = duración de las pistas anteriores + lo que llevas de la actual
```

Eso obliga a que las dos plataformas **ordenen las pistas igual**. Si no, «hora
6» cae en un capítulo distinto en cada una. El orden es por nombre de archivo,
con los números comparados como números («Parte 2» antes que «Parte 10») y sin
distinguir mayúsculas ni acentos. Está en `ordenarPistas` de
`src/lib/ordenPistas.js` y en `OrdenDePistas` de Kotlin, con los mismos
vectores congelados en los tests de ambas.

La traducción entre global y «pista + segundo dentro» vive en
`posicionDelLibro.js` y `PosicionDelLibro.kt`, también con vectores comunes.

Y de aquí sale un requisito para el móvil: **tiene que agrupar los archivos de
una carpeta en un libro**, con el mismo criterio que el escritorio (carpeta +
etiqueta de álbum). Mientras trató cada archivo como un libro, la huella de un
libro de doce capítulos no podía coincidir nunca con doce huellas de una pista,
ni las duraciones tampoco —trece horas contra una—, así que esos libros no
sincronizaban y no había ningún error que lo dijera.

### Cómo se lee la posición de una fila

Las dos plataformas deben leer idéntico el mismo dato:

```
posicion_absoluta = global_position > 0 ? global_position : position
```

`position` es el segundo **dentro de su pista**. En un libro que el ordenador
tiene partido en capítulos, la pista 12 puede empezar en la hora 6 y su
`position` valer 40; tomarlo por absoluto mandaría la reproducción al minuto
0:40. Ojo con escribirlo como `global_position ?? position`: la columna es
`not null default 0`, así que una fila antigua no trae `null` sino un cero, y
esa versión se quedaba en el cero mientras la otra plataforma leía los 40 s.

Está en `posicionAbsoluta` de `src/lib/emparejar.js` y en
`EmparejarLibros.posicionAbsoluta` de Kotlin, con los mismos vectores fijados
en los tests de ambas.

## Dos identidades distintas: la huella y el `syncId`

Conviene no confundirlas, porque el error de mezclarlas rompe la
sincronización de una forma que no da la cara:

- **La huella** identifica *un archivo concreto*. Dos copias del mismo
  audiolibro con distinta codificación tienen huellas distintas.
- **El `syncId`** identifica *la fila de la nube* donde vive ese libro. Es lo
  que ambos dispositivos deben compartir.

Al principio el `syncId` de un libro es su propia huella. Cuando un
dispositivo reconoce por duración el libro del otro, **adopta el `syncId`
ajeno** y lo guarda: a partir de ahí los dos escriben en la misma fila.

Sin esa adopción, reconocer el libro serviría para leer una vez y nada más:
al guardar, cada dispositivo crearía su propia fila con su propia huella y no
volverían a encontrarse nunca.

Si al reconciliar aparecen varias filas del mismo libro (por ejemplo porque
los dos dispositivos escucharon sin conexión antes de verse), se conserva la
posición más avanzada y se retiran las sobrantes. La fila que se conserva es
la del **identificador menor**, criterio determinista para que los dos
dispositivos elijan la misma sin hablar entre ellos.

## Emparejar el mismo libro en archivos distintos

La huella identifica copias idénticas byte a byte. Pero el mismo audiolibro
puede estar en cada dispositivo con distinta codificación, o con las etiquetas
editadas a mano, y entonces las huellas no coinciden aunque sea el mismo libro.

Para eso hay una segunda vía, que se usa **solo si no existe fila para la
huella**:

```
clave_blanda = normalizar(titulo) + "|" + normalizar(autor)

normalizar(s):
  1. pasar a minúsculas
  2. quitar los diacríticos (NFD y eliminar las marcas)
  3. sustituir por espacio todo lo que no sea [a-z0-9]
  4. colapsar espacios repetidos y recortar
```

Así «El Ritmo de la Guerra», «el ritmo de la guerra» y «EL RITMO DE LA GUERRA»
son la misma clave.

La búsqueda se hace **por duración**, que es el dato más fiable porque no
depende de cómo estén escritas las etiquetas:

```
tolerancia = max(10 s, duración × 0,2 %)
candidatos = filas cuya duration esté dentro de [duración − tolerancia, duración + tolerancia]

si hay un solo candidato            -> es el mismo libro
si hay varios                       -> gana el que además tenga la misma clave blanda
si hay varios y ninguno coincide    -> no se empareja ninguno
```

La duración manda y la clave blanda solo desempata: los títulos se editan, la
duración no.

## Resolver la pista al retomar

```
si existe una pista local con huella == track_id  ->  ir a esa pista, segundo `position`
si no                                             ->  ir a `global_position` desde el inicio
```

El segundo caso cubre que el móvil tenga el libro partido en archivos distintos
(por ejemplo un M4B único frente a una carpeta de capítulos). En ese supuesto
las huellas de libro tampoco coincidirían, así que en la práctica es un
salvavidas, no el camino normal.
