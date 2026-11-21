-- Lynxos Retro — Supabase setup.
-- Run this in the Supabase SQL Editor for the "Lynxos Retro" project.
-- Creates the game_library table with Row Level Security (each user
-- only sees their own rows) and the private roms storage bucket.
-- SQL only, no prose.

create table if not exists public.game_library (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  title text not null,
  system text not null,
  core text not null,
  rom_url text,
  source text,
  added_at timestamptz not null default now()
);
alter table public.game_library enable row level security;
create policy "Users can view their own library" on public.game_library for select using (auth.uid() = user_id);
create policy "Users can add to their own library" on public.game_library for insert with check (auth.uid() = user_id);
create policy "Users can update their own library" on public.game_library for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "Users can delete from their own library" on public.game_library for delete using (auth.uid() = user_id);
-- The table needs explicit privileges for the signed-in role, or every
-- read/write fails with "permission denied for table game_library"
-- before RLS is even checked (found Nov 20, 2026: uploads worked but
-- inserts failed with 403 until this grant was added).
grant select, insert, update, delete on public.game_library to authenticated;
insert into storage.buckets (id, name, public) values ('roms', 'roms', false) on conflict (id) do nothing;
create policy "Users can upload their own ROMs" on storage.objects for insert with check (bucket_id = 'roms' and auth.uid() = owner);
create policy "Users can download their own ROMs" on storage.objects for select using (bucket_id = 'roms' and auth.uid() = owner);
create policy "Users can delete their own ROMs" on storage.objects for delete using (bucket_id = 'roms' and auth.uid() = owner);
