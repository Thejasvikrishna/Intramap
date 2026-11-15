-- Apply after 0001 and 0002. This creates draft geometry, never publishes it.
begin;

alter policy buildings_read on public.buildings using (
  owner_id = (select auth.uid()) or private.can_edit_building(buildings.id)
  or exists (select 1 from public.map_versions v
             where v.building_id = buildings.id and v.status = 'published')
);
alter policy versions_read on public.map_versions using (
  status = 'published' or private.can_edit_building(building_id)
);

create table public.walking_imports (
  recording_id uuid primary key references public.path_recordings(id),
  version_id uuid not null references public.map_versions(id),
  imported_by uuid not null references auth.users(id),
  imported_at timestamptz not null default now()
);
alter table public.walking_imports enable row level security;
grant select on public.walking_imports to authenticated;
create policy walking_imports_read on public.walking_imports for select to authenticated
using (exists (select 1 from public.map_versions v where v.id = walking_imports.version_id
               and private.can_edit_building(v.building_id)));

create function public.import_walking_recording(
  recording uuid, draft uuid, floor_name text, floor_number integer,
  start_node text default null, facing_node text default null, end_node text default null
) returns void language plpgsql security definer set search_path = '' as $$
declare
  v public.map_versions%rowtype;
  r public.path_recordings%rowtype;
  a public.map_nodes%rowtype;
  b public.map_nodes%rowtype;
  e public.map_nodes%rowtype;
  p record;
  first_x double precision; first_y double precision;
  angle double precision := 0;
  ox double precision; oy double precision;
  px double precision; py double precision;
  last_x double precision; last_y double precision;
  low_x double precision; low_y double precision;
  high_x double precision; high_y double precision;
  k text; previous_key text; label text; kind text;
  first_seq integer; last_seq integer; point_count integer;
  length_m double precision; walked double precision := 0;
  prior_import uuid;
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  select * into v from public.map_versions where id = draft for update;
  if not found then raise exception 'Draft not found'; end if;
  if v.status <> 'draft' or not private.can_edit_building(v.building_id) then
    raise exception 'Only editors may import into a draft';
  end if;
  select * into r from public.path_recordings where id = recording for update;
  if not found then raise exception 'Recording not found'; end if;
  if r.building_id <> v.building_id or r.status <> 'submitted' then
    raise exception 'Choose a submitted recording from the same building';
  end if;
  select version_id into prior_import from public.walking_imports where recording_id = recording;
  if found then
    if prior_import = draft then return; end if;
    raise exception 'This recording has already been imported into another version';
  end if;
  if floor_name is null or btrim(floor_name) = '' or length(floor_name) > 120
     or floor_number is null then raise exception 'Enter floor name and number'; end if;
  select count(*), min(seq), max(seq) into point_count, first_seq, last_seq
    from public.path_recording_points where recording_id = recording;
  if point_count < 2 or point_count > 10000 then raise exception 'Recording needs 2 to 10000 points'; end if;
  if exists (select 1 from public.path_recording_points where recording_id = recording and
      (x::text in ('NaN','Infinity','-Infinity') or y::text in ('NaN','Infinity','-Infinity')
       or abs(x) > 10000 or abs(y) > 10000)) then raise exception 'Invalid recording coordinates'; end if;
  select x, y into first_x, first_y from public.path_recording_points
    where recording_id = recording and seq = first_seq;
  if start_node is not null then
    select * into a from public.map_nodes where version_id = draft and node_key = start_node and floor_key = r.floor_key;
    if not found then raise exception 'Starting landmark is not on this draft floor'; end if;
    select * into b from public.map_nodes where version_id = draft and node_key = facing_node and floor_key = r.floor_key;
    if not found or b.node_key = a.node_key then raise exception 'Select a distinct facing landmark'; end if;
    if sqrt(power(b.x-a.x,2)+power(b.y-a.y,2)) < 0.5 then raise exception 'Facing landmarks are too close'; end if;
    angle := atan2((b.y-a.y)::double precision, (b.x-a.x)::double precision) + pi()/2;
    ox := a.x; oy := a.y;
  else
    if exists (select 1 from public.map_nodes where version_id = draft and floor_key = r.floor_key) then
      raise exception 'Existing floor: select the actual starting and facing landmarks';
    end if;
    select min(x-first_x), min(y-first_y) into low_x, low_y
      from public.path_recording_points where recording_id = recording;
    ox := 2-low_x; oy := 2-low_y;
  end if;
  if end_node is not null then
    select * into e from public.map_nodes where version_id = draft and node_key = end_node and floor_key = r.floor_key;
    if not found then raise exception 'End landmark is not on this draft floor'; end if;
  end if;
  -- A floor's dimensions are observed route extents, not architectural measurements.
  insert into public.floors(version_id,floor_key,floor_number,name,width_m,height_m)
    values(draft,r.floor_key,floor_number,btrim(floor_name),1,1)
    on conflict(version_id,floor_key) do nothing;
  for p in select * from public.path_recording_points where recording_id = recording order by seq loop
    px := ox + (p.x-first_x)*cos(angle) - (p.y-first_y)*sin(angle);
    py := oy + (p.x-first_x)*sin(angle) + (p.y-first_y)*cos(angle);
    k := 'walk_' || replace(recording::text,'-','') || '_' || p.seq;
    if p.seq = first_seq and start_node is not null then k := start_node; px := a.x; py := a.y; end if;
    if p.seq = last_seq and end_node is not null then
      if sqrt(power(px-e.x,2)+power(py-e.y,2)) > 3 then raise exception 'End mismatch exceeds 3 m; correct or re-record'; end if;
      k := end_node; px := e.x; py := e.y;
    end if;
    if px::text in ('NaN','Infinity','-Infinity') or py::text in ('NaN','Infinity','-Infinity')
       or px < 0 or py < 0 or px > 20000 or py > 20000 then
      raise exception 'Trace falls outside the supported frame; correct alignment';
    end if;
    if previous_key is not null then
      length_m := sqrt(power(px-last_x,2)+power(py-last_y,2));
      if length_m > 5 then raise exception 'Discontinuous trace; review tracking'; end if;
      if length_m < 0.05 then continue; end if;
      walked := walked + length_m;
    end if;
    kind := case when p.seq = first_seq then 'ENTRANCE' else 'JUNCTION' end;
    label := case when p.seq = first_seq then 'Recorded start' else 'Path point' end;
    if split_part(coalesce(p.marker,''),':',1) in ('ROOM','ENTRANCE','STAIRS','ELEVATOR','JUNCTION','TURN')
       and position(':' in p.marker) > 0 then
      kind := split_part(p.marker,':',1);
      if kind = 'TURN' then kind := 'JUNCTION'; end if;
      label := left(substr(p.marker,position(':' in p.marker)+1),120);
    end if;
    insert into public.map_nodes(version_id,node_key,floor_key,name,node_type,x,y)
      values(draft,k,r.floor_key,label,kind,px,py) on conflict(version_id,node_key) do nothing;
    if previous_key is not null and previous_key <> k then
      insert into public.map_edges(version_id,edge_key,from_key,to_key,distance_m)
        values(draft,'walk_'||replace(recording::text,'-','')||'_'||p.seq,previous_key,k,length_m);
    end if;
    previous_key := k; last_x := px; last_y := py;
  end loop;
  if walked < 0.5 then raise exception 'Recording has no usable walking movement'; end if;
  select max(x)+2, max(y)+2 into high_x, high_y from public.map_nodes
    where version_id = draft and floor_key = r.floor_key;
  update public.floors set width_m = greatest(width_m,high_x), height_m = greatest(height_m,high_y)
    where version_id = draft and floor_key = r.floor_key;
  insert into public.walking_imports(recording_id,version_id,imported_by) values(recording,draft,auth.uid());
end;
$$;
revoke all on function public.import_walking_recording(uuid,uuid,text,integer,text,text,text) from public, anon;
grant execute on function public.import_walking_recording(uuid,uuid,text,integer,text,text,text) to authenticated;
commit;
