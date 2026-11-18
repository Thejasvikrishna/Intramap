begin;
alter table public.map_versions add column places_schema_version integer not null default 0
  check(places_schema_version between 0 and 1);
update public.map_versions v set places_schema_version = 1
  where exists(select 1 from public.map_places p where p.version_id = v.id);
create function private.mark_version_place_schema()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  update public.map_versions set places_schema_version = 1
    where id = new.version_id and places_schema_version = 0;
  return new;
end;
$$;
revoke all on function private.mark_version_place_schema() from public,anon,authenticated;
create trigger mark_version_place_schema after insert on public.map_places
for each row execute function private.mark_version_place_schema();
notify pgrst,'reload schema';
commit;
