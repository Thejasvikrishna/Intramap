begin;
create table if not exists public.reference_images (
  id uuid primary key default gen_random_uuid(),
  building_id uuid not null references public.buildings(id) on delete cascade,
  version_id uuid not null references public.map_versions(id) on delete cascade,
  reference_key text not null,
  floor_id text not null,
  physical_width_m double precision not null,
  map_x double precision not null,
  map_y double precision not null,
  map_heading_deg double precision not null,
  storage_path text not null unique,
  content_sha256 text not null,
  registered_at timestamptz not null default now(),
  unique (version_id,reference_key)
);
create index if not exists reference_images_building_version_idx on public.reference_images(building_id,version_id);
create index if not exists reference_images_version_idx on public.reference_images(version_id);
do $$ begin
  if not exists (select 1 from pg_policies where schemaname='public' and tablename='reference_images' and policyname='reference_images_owner_all') then
    create policy reference_images_owner_all on public.reference_images
      for all using (private.can_edit_building(building_id)) with check (private.can_edit_building(building_id));
  end if;
end $$;
revoke all on public.reference_images from anon;
grant select,insert,update,delete on public.reference_images to authenticated;
commit;
