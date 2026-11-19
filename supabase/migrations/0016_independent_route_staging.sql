begin;
create table public.independent_route_imports (
  version_id uuid not null references public.map_versions(id) on delete cascade,
  recording_id uuid not null references public.path_recordings(id),
  floor_key text not null,
  floor_name text not null check (length(btrim(floor_name)) between 1 and 120),
  floor_number integer not null,
  placement_status text not null default 'unpositioned' check (placement_status = 'unpositioned'),
  points jsonb not null check (jsonb_typeof(points) = 'array'),
  places jsonb not null check (jsonb_typeof(places) = 'array'),
  imported_by uuid not null references auth.users(id),
  imported_at timestamptz not null default now(),
  primary key (version_id,recording_id)
);
alter table public.independent_route_imports enable row level security;
revoke all on public.independent_route_imports from public,anon,authenticated;
grant select on public.independent_route_imports to authenticated;
create policy independent_routes_editor_read on public.independent_route_imports
for select to authenticated using (
  exists(select 1 from public.map_versions v where v.id = version_id and private.can_edit_building(v.building_id))
);
create function public.stage_independent_walking_recording(
  p_recording uuid,p_draft uuid,p_floor_name text,p_floor_number integer
) returns jsonb language plpgsql security definer set search_path = '' set lock_timeout = '5s' as $$
declare
  v public.map_versions%rowtype;
  r public.path_recordings%rowtype;
  existing public.independent_route_imports%rowtype;
  point_payload jsonb; place_payload jsonb;
  n integer; lo integer; hi integer;
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  select q.* into v from public.map_versions q where q.id = p_draft for update;
  if not found then raise exception 'Draft not found'; end if;
  if v.status <> 'draft' or not private.can_edit_building(v.building_id) then
    raise exception 'Draft edit permission required';
  end if;
  select q.* into r from public.path_recordings q where q.id = p_recording for update;
  if not found then raise exception 'Recording not found'; end if;
  if r.building_id is distinct from v.building_id or r.status <> 'submitted' then
    raise exception 'Choose a submitted recording from the same building';
  end if;
  if r.start_anchor_key is not null or r.end_anchor_key is not null then
    raise exception 'Anchored recordings require the saved-anchor import workflow';
  end if;
  if p_floor_name is null or length(btrim(p_floor_name)) not between 1 and 120 or p_floor_number is null then
    raise exception 'Floor name and number required';
  end if;
  if exists(select 1 from public.walking_imports i where i.recording_id = p_recording) then
    raise exception 'Recording is already imported into a map graph';
  end if;
  select i.* into existing from public.independent_route_imports i
    where i.version_id = p_draft and i.recording_id = p_recording;
  if found then
    if existing.floor_name is distinct from btrim(p_floor_name) or existing.floor_number is distinct from p_floor_number then
      raise exception 'Independent import already exists with different floor metadata';
    end if;
    return jsonb_build_object('recordingId',p_recording,'versionId',p_draft,'placementStatus','unpositioned','pointCount',jsonb_array_length(existing.points),'placeCount',jsonb_array_length(existing.places));
  end if;
  select count(*),min(p.seq),max(p.seq) into n,lo,hi from public.path_recording_points p where p.recording_id = p_recording;
  if n not between 2 and 10000 or lo <> 0 or hi <> n-1 then
    raise exception 'Complete contiguous recording points required';
  end if;
  if exists(select 1 from public.path_recording_points p where p.recording_id = p_recording
    and (p.x::text in ('NaN','Infinity','-Infinity') or p.y::text in ('NaN','Infinity','-Infinity') or abs(p.x)>10000 or abs(p.y)>10000)) then
    raise exception 'Invalid recording coordinates';
  end if;
  select jsonb_agg(to_jsonb(p) order by p.seq) into point_payload
    from public.path_recording_points p where p.recording_id = p_recording;
  select coalesce(jsonb_agg(to_jsonb(p) order by p.place_id),'[]'::jsonb) into place_payload
    from public.path_recording_places p where p.recording_id = p_recording;
  insert into public.independent_route_imports(version_id,recording_id,floor_key,floor_name,floor_number,points,places,imported_by)
    values(p_draft,p_recording,r.floor_key,btrim(p_floor_name),p_floor_number,point_payload,place_payload,auth.uid());
  return jsonb_build_object('recordingId',p_recording,'versionId',p_draft,'placementStatus','unpositioned','pointCount',n,'placeCount',jsonb_array_length(place_payload));
end;
$$;
revoke all on function public.stage_independent_walking_recording(uuid,uuid,text,integer) from public,anon;
grant execute on function public.stage_independent_walking_recording(uuid,uuid,text,integer) to authenticated;
comment on table public.independent_route_imports is 'Editor-only immutable snapshots in recording-local coordinates. Not graph imports or published destinations. Requires Studio staging UI and explicit placement before graph materialization.';
notify pgrst,'reload schema';
commit;
