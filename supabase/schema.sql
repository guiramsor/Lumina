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
