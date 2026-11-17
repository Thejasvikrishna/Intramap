begin;

create table public.path_recording_places (
  recording_id uuid not null references public.path_recordings(id) on delete cascade,
  place_id uuid not null,
  sample_seq integer not null,
  name text not null check (length(name) between 1 and 120 and name = btrim(name) and name !~ '[[:cntrl:]]'),
  category text not null check (category in ('ROOM','SHOP','OFFICE','ENTRANCE','JUNCTION','STAIRS','ELEVATOR')),
  primary key(recording_id,place_id),
  foreign key(recording_id,sample_seq) references public.path_recording_points(recording_id,seq)
);
alter table public.path_recording_places enable row level security;
revoke all on public.path_recording_places from public,anon,authenticated;
grant select on public.path_recording_places to authenticated;
create policy recording_places_read on public.path_recording_places for select to authenticated
using (private.can_read_recording(recording_id));

create function public.submit_walking_recording_with_places(
  p_recording uuid,p_expected_points integer,p_places jsonb
) returns void language plpgsql security definer set search_path = '' set lock_timeout = '5s' as $$
declare
  r public.path_recordings%rowtype;
  canonical jsonb; stored jsonb; point_count integer; first_seq integer; last_seq integer;
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  select q.* into r from public.path_recordings q where q.id = p_recording for update;
  if not found or r.recorder_id is distinct from auth.uid() or not private.can_edit_building(r.building_id) then
    raise exception 'Recording owner and building edit permission required';
  end if;
  if p_expected_points is null or p_expected_points not between 2 and 10000 then raise exception 'Invalid expected point count'; end if;
  if jsonb_typeof(p_places) is distinct from 'array' then raise exception 'Place array required'; end if;
  if jsonb_array_length(p_places) > 1000 then raise exception 'Too many places'; end if;
  if exists(select 1 from jsonb_array_elements(p_places) x where jsonb_typeof(x) is distinct from 'object') then
    raise exception 'Invalid place object';
  end if;
  if exists(select 1 from jsonb_to_recordset(p_places) as p("placeId" uuid,"sampleIndex" integer,name text,category text)
    where p."placeId" is null or p."sampleIndex" is null or p."sampleIndex" < 0 or p."sampleIndex" >= p_expected_points
      or p.name is null or length(p.name) not between 1 and 120 or p.name <> btrim(p.name) or p.name ~ '[[:cntrl:]]'
      or p.category is null or p.category not in ('ROOM','SHOP','OFFICE','ENTRANCE','JUNCTION','STAIRS','ELEVATOR')) then
    raise exception 'Invalid place identity, position, name or category';
  end if;
  if (select count(distinct p."placeId") from jsonb_to_recordset(p_places) as p("placeId" uuid)) <> jsonb_array_length(p_places) then
    raise exception 'Duplicate place IDs';
  end if;
  select coalesce(jsonb_agg(jsonb_build_object('placeId',p."placeId",'sampleIndex',p."sampleIndex",'name',p.name,'category',p.category) order by p."placeId"),'[]'::jsonb)
    into canonical from jsonb_to_recordset(p_places) as p("placeId" uuid,"sampleIndex" integer,name text,category text);
  select count(*),min(seq),max(seq) into point_count,first_seq,last_seq from public.path_recording_points where recording_id = p_recording;
  if point_count <> p_expected_points or first_seq <> 0 or last_seq <> p_expected_points-1 then
    raise exception 'Upload every route point before submission';
  end if;
  if r.status in ('submitted','approved') then
    select coalesce(jsonb_agg(jsonb_build_object('placeId',p.place_id,'sampleIndex',p.sample_seq,'name',p.name,'category',p.category) order by p.place_id),'[]'::jsonb)
      into stored from public.path_recording_places p where p.recording_id = p_recording;
    if stored is distinct from canonical then raise exception 'Submitted places are frozen; retry the same snapshot'; end if;
    return;
  end if;
  if r.status <> 'draft' or exists(select 1 from public.walking_imports i where i.recording_id = p_recording) then
    raise exception 'Only an unimported draft recording may be submitted';
  end if;
  delete from public.path_recording_places where recording_id = p_recording;
  insert into public.path_recording_places(recording_id,place_id,sample_seq,name,category)
    select p_recording,p."placeId",p."sampleIndex",p.name,p.category
    from jsonb_to_recordset(canonical) as p("placeId" uuid,"sampleIndex" integer,name text,category text);
  update public.path_recordings set status = 'submitted' where id = p_recording;
end;
$$;
revoke all on function public.submit_walking_recording_with_places(uuid,integer,jsonb) from public,anon;
grant execute on function public.submit_walking_recording_with_places(uuid,integer,jsonb) to authenticated;
comment on table public.path_recording_places is 'Private numeric-route place metadata. Import and visitor integration must be deployed before enabling place-aware submission in the app.';
notify pgrst,'reload schema';
commit;
