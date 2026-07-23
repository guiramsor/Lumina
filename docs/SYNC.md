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
4. **Reiniciar un libro sí se propaga.** Volver casi al principio (menos de
   30 s, o un libro marcado como terminado) se considera intencionado y se
   sube aunque haga retroceder la posición.
5. **Los fallos de red nunca bloquean**: si no hay conexión, se escucha en
   local y se sube más tarde. La sincronización es una mejora, no un requisito.
6. **`position` manda sobre `global_position`**: para retomar se busca la pista
   por su `track_id` y se salta a `position`. `global_position` es solo
   informativo, porque depende del orden de las pistas.

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
