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

1. **Al abrir un libro**: se descarga la fila remota y se compara con la local.
   Gana la de `updated_at` más reciente.
2. **Margen de 60 segundos**: la remota solo gana si es más nueva que la local
   por más de un minuto. Evita que un pequeño desfase de reloj entre
   dispositivos haga saltar la reproducción hacia atrás sin motivo.
3. **Al pausar, buscar o cada pocos segundos**: se sube la posición actual con
   `updated_at` = el momento de la escucha.
4. **Los fallos de red nunca bloquean**: si no hay conexión, se escucha en local
   y se sube más tarde. La sincronización es una mejora, no un requisito.
5. **`position` manda sobre `global_position`**: para retomar se busca la pista
   por su `track_id` y se salta a `position`. `global_position` es solo
   informativo, porque depende del orden de las pistas.

## Resolver la pista al retomar

```
si existe una pista local con huella == track_id  ->  ir a esa pista, segundo `position`
si no                                             ->  ir a `global_position` desde el inicio
```

El segundo caso cubre que el móvil tenga el libro partido en archivos distintos
(por ejemplo un M4B único frente a una carpeta de capítulos). En ese supuesto
las huellas de libro tampoco coincidirían, así que en la práctica es un
salvavidas, no el camino normal.
