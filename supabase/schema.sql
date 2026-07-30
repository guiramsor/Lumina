-- Esquema de sincronización de Lumina.
-- Ejecutar una vez en el SQL Editor del proyecto de Supabase.
--
-- Aquí NO viaja ningún audio: solo la posición de escucha. Los archivos siguen
-- siendo locales en cada dispositivo y se relacionan por su huella digital
-- (ver src/lib/fingerprint.js y docs/SYNC.md).

create table if not exists public.progress (
  user_id         uuid        not null default auth.uid() references auth.users on delete cascade,

  -- Huella del libro: SHA-256 de las huellas de sus pistas. Es la misma en el
  -- PC y en el móvil siempre que el archivo sea el mismo.
  book_id         text        not null,

  -- Huella de la pista concreta que se estaba escuchando. Permite retomar la
  -- posición exacta aunque cada dispositivo ordene las pistas de otra forma.
  track_id        text,

  position        double precision not null default 0,  -- segundos dentro de la pista
  global_position double precision not null default 0,  -- segundos desde el inicio del libro
  duration        double precision,                     -- duración total, para el porcentaje
  finished        boolean     not null default false,

  -- Solo para poder identificar filas a simple vista al depurar.
  title           text,
  author          text,
  device          text,

  -- Momento real de la escucha, lo pone el cliente: si el móvil estuvo sin
  -- cobertura y sincroniza más tarde, no debe pisar una escucha posterior.
  updated_at      timestamptz not null default now(),

  primary key (user_id, book_id)
);

alter table public.progress enable row level security;

-- Cada cuenta solo ve y escribe su propio progreso.
drop policy if exists "progreso propio" on public.progress;
create policy "progreso propio" on public.progress
  for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create index if not exists progress_user_updated_idx
  on public.progress (user_id, updated_at desc);

-- ---------------------------------------------------------------------------
-- Guardar la posición sin poder retroceder por accidente
-- ---------------------------------------------------------------------------
--
-- Antes cada dispositivo comparaba con la última posición remota que había
-- leído, y luego escribía con un upsert a ciegas. El problema es que esa
-- referencia solo se refresca al abrir el libro y con las subidas del propio
-- dispositivo: nunca se entera de lo que escribe el otro. Así que entre leer y
-- escribir cabía una sesión de escucha entera, y bastaba con dejar el
-- ordenador sonando y coger el móvil para que el móvil pisara al ordenador en
-- cada subida durante horas.
--
-- La comprobación se hace aquí porque aquí es atómica: no hay hueco entre
-- mirar y escribir, y ningún cliente puede saltársela por olvido.
--
-- `p_incondicional` es la excepción de siempre, la única que puede ir hacia
-- atrás: cuando la posición la ha elegido el usuario (barra, salto, marcador,
-- capítulo, reabrir un libro terminado) o el libro se ha terminado. Eso solo
-- lo sabe el cliente, así que viaja como parámetro.
--
-- Devuelve true si la fila se ha escrito, false si se ha rechazado por ir por
-- detrás. Las dos son respuestas correctas: rechazar no es un error.
create or replace function public.guardar_progreso(
  p_book_id         text,
  p_global_position double precision,
  p_position        double precision default 0,
  p_track_id        text default null,
  p_duration        double precision default null,
  p_finished        boolean default false,
  p_title           text default null,
  p_author          text default null,
  p_device          text default null,
  p_updated_at      timestamptz default now(),
  p_incondicional   boolean default false
) returns boolean
language plpgsql
-- `invoker`: se ejecuta como quien llama, así que la política RLS sigue
-- mandando y nadie puede escribir en el progreso de otra cuenta.
security invoker
as $$
declare
  filas integer;
begin
  insert into public.progress as p (
    user_id, book_id, track_id, position, global_position,
    duration, finished, title, author, device, updated_at
  ) values (
    auth.uid(), p_book_id, p_track_id, p_position, p_global_position,
    p_duration, p_finished, p_title, p_author, p_device, p_updated_at
  )
  on conflict (user_id, book_id) do update set
    track_id        = excluded.track_id,
    position        = excluded.position,
    global_position = excluded.global_position,
    -- Un cliente que aún no sabe la duración o las etiquetas no debe borrar
    -- las que ya había: sin duración la fila se vuelve invisible para la
    -- búsqueda por parecido, y sin autor el desempate deja de funcionar.
    duration        = coalesce(excluded.duration, p.duration),
    title           = coalesce(excluded.title, p.title),
    author          = coalesce(excluded.author, p.author),
    finished        = excluded.finished,
    device          = excluded.device,
    updated_at      = excluded.updated_at
  where p_incondicional or p.global_position < excluded.global_position;

  get diagnostics filas = row_count;
  return filas > 0;
end;
$$;

grant execute on function public.guardar_progreso to authenticated;
