create or replace function private.can_edit_building(b uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.buildings x where x.id = b and x.owner_id = (select auth.uid()))
      or exists (select 1 from public.building_members m where m.building_id = b and m.user_id = (select auth.uid()));
$$;

create or replace function private.version_building(v uuid)
returns uuid language sql stable security definer set search_path = '' as $$
  select building_id from public.map_versions where id = v;
$$;

create or replace function private.version_published(v uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.map_versions where id = v and status = 'published');
$$;

grant usage on schema private to anon, authenticated;
grant execute on all functions in schema private to anon, authenticated;

alter table public.profiles enable row level security;
alter table public.buildings enable row level security;
alter table public.building_members enable row level security;
alter table public.map_versions enable row level security;
alter table public.floors enable row level security;
alter table public.map_nodes enable row level security;
alter table public.map_edges enable row level security;
alter table public.reference_anchors enable row level security;
alter table public.path_recordings enable row level security;
alter table public.path_recording_points enable row level security;

grant select on public.buildings, public.map_versions, public.floors, public.map_nodes, public.map_edges, public.reference_anchors to anon;
grant select, insert, update, delete on public.profiles, public.buildings, public.building_members, public.map_versions, public.floors, public.map_nodes, public.map_edges, public.reference_anchors, public.path_recordings, public.path_recording_points to authenticated;

create policy profiles_own on public.profiles for all to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));

create policy buildings_read on public.buildings for select to anon, authenticated using (
  exists (select 1 from public.map_versions v where v.building_id = id and v.status = 'published')
  or private.can_edit_building(id));
create policy buildings_insert on public.buildings for insert to authenticated with check (owner_id = (select auth.uid()));
create policy buildings_update on public.buildings for update to authenticated using (private.can_edit_building(id)) with check (private.can_edit_building(id));
create policy buildings_delete on public.buildings for delete to authenticated using (owner_id = (select auth.uid()));

create policy members_read on public.building_members for select to authenticated using (user_id = (select auth.uid()) or private.can_edit_building(building_id));
create policy members_write on public.building_members for all to authenticated using (private.can_edit_building(building_id)) with check (private.can_edit_building(building_id));

create policy versions_read on public.map_versions for select to anon, authenticated using (status = 'published' or private.can_edit_building(building_id));
create policy versions_write on public.map_versions for all to authenticated using (private.can_edit_building(building_id) and status <> 'published') with check (private.can_edit_building(building_id) and created_by = (select auth.uid()) and status <> 'published');

create policy floors_read on public.floors for select to anon, authenticated using (private.version_published(version_id) or private.can_edit_building(private.version_building(version_id)));
create policy floors_write on public.floors for all to authenticated using (private.can_edit_building(private.version_building(version_id)) and not private.version_published(version_id)) with check (private.can_edit_building(private.version_building(version_id)) and not private.version_published(version_id));

create policy nodes_read on public.map_nodes for select to anon, authenticated using (private.version_published(version_id) or private.can_edit_building(private.version_building(version_id)));
create policy nodes_write on public.map_nodes for all to authenticated using (private.can_edit_building(private.version_building(version_id)) and not private.version_published(version_id)) with check (private.can_edit_building(private.version_building(version_id)) and not private.version_published(version_id));

create policy edges_read on public.map_edges for select to anon, authenticated using (private.version_published(version_id) or private.can_edit_building(private.version_building(version_id)));
create policy edges_write on public.map_edges for all to authenticated using (private.can_edit_building(private.version_building(version_id)) and not private.version_published(version_id)) with check (private.can_edit_building(private.version_building(version_id)) and not private.version_published(version_id));

create policy anchors_read on public.reference_anchors for select to anon, authenticated using (private.version_published(version_id) or private.can_edit_building(private.version_building(version_id)));
create policy anchors_write on public.reference_anchors for all to authenticated using (private.can_edit_building(private.version_building(version_id)) and not private.version_published(version_id)) with check (private.can_edit_building(private.version_building(version_id)) and not private.version_published(version_id));

create policy recordings_own on public.path_recordings for all to authenticated
  using (recorder_id = (select auth.uid()) or private.can_edit_building(building_id))
  with check (recorder_id = (select auth.uid()) and (status in ('draft','submitted') or private.can_edit_building(building_id)));

create policy rec_points_own on public.path_recording_points for all to authenticated
  using (exists (select 1 from public.path_recordings r where r.id = recording_id and (r.recorder_id = (select auth.uid()) or private.can_edit_building(r.building_id))))
  with check (exists (select 1 from public.path_recordings r where r.id = recording_id and r.recorder_id = (select auth.uid())));

create or replace function public.publish_map_version(v uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare b uuid;
begin
  select building_id into b from public.map_versions where id = v;
  if b is null or not private.can_edit_building(b) then raise exception 'not allowed'; end if;
  if not exists (select 1 from public.floors where version_id = v) then raise exception 'version has no floors'; end if;
  if exists (select 1 from public.map_edges e where e.version_id = v and (not exists (select 1 from public.map_nodes n where n.version_id = v and n.node_key = e.from_key) or not exists (select 1 from public.map_nodes n where n.version_id = v and n.node_key = e.to_key))) then raise exception 'edge references missing node'; end if;
  update public.map_versions set status = 'archived' where building_id = b and status = 'published';
  update public.map_versions set status = 'published', published_at = now() where id = v;
end; $$;
revoke execute on function public.publish_map_version(uuid) from public, anon;
grant execute on function public.publish_map_version(uuid) to authenticated;
