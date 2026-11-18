-- Apply after 0011-0014. Does not publish a map or change RLS policies.
begin;

create or replace function public.import_walking_recording_with_places(
  recording uuid,draft uuid,floor_name text,floor_number integer,
  start_node text default null,facing_node text default null,end_node text default null
) returns void language plpgsql security definer set search_path = '' set lock_timeout = '5s' as $$
declare
  b uuid; r public.path_recordings%rowtype;
  prior public.walking_imports%rowtype; p public.path_recording_places%rowtype;
  first_seq integer; last_seq integer; anchor_key text;
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  select v.building_id into b from public.map_versions v where v.id = draft and v.status = 'draft' for update;
  if b is null or not private.can_edit_building(b) then raise exception 'Draft edit permission required'; end if;

  -- Lock in the same version-then-recording order as the geometry importer.
  select q.* into r from public.path_recordings q where q.id = recording for update;
  if not found then raise exception 'Recording not found'; end if;
  if r.building_id is distinct from b or r.status not in ('submitted','approved') then
    raise exception 'Choose a submitted recording from the same building';
  end if;
  if r.start_anchor_key is not null and start_node is distinct from r.start_anchor_key then
    raise exception 'Select the start anchor saved with this branch recording';
  end if;
  if r.end_anchor_key is not null and end_node is distinct from r.end_anchor_key then
    raise exception 'Select the end anchor saved with this recording';
  end if;

  select i.* into prior from public.walking_imports i where i.recording_id = recording;
  if found then
    if prior.version_id <> draft then raise exception 'Recording was imported into another version'; end if;
    if prior.places_imported or not exists(select 1 from public.path_recording_places q where q.recording_id = recording) then return; end if;
    raise exception 'Legacy import lacks place alignment receipt; do not guess joins. Review or re-record this route';
  end if;
  perform public.import_walking_recording(recording,draft,floor_name,floor_number,start_node,facing_node,end_node);
  select min(q.seq),max(q.seq) into first_seq,last_seq from public.path_recording_points q where q.recording_id = recording;
  for p in select q.* from public.path_recording_places q where q.recording_id = recording order by q.place_id loop
    anchor_key := 'walk_' || replace(recording::text,'-','') || '_' || p.sample_seq;
    if p.sample_seq = first_seq and start_node is not null then anchor_key := start_node; end if;
    if p.sample_seq = last_seq and end_node is not null then anchor_key := end_node; end if;
    if not exists(select 1 from public.map_nodes n join public.path_recordings q on q.id = recording
      where n.version_id = draft and n.node_key = anchor_key and n.floor_key = q.floor_key and q.building_id = b) then
      raise exception 'Place has no exact imported route point; correct alignment instead of guessing a destination';
    end if;
    insert into public.map_places(version_id,place_id,node_key,name,category)
      values(draft,p.place_id,anchor_key,p.name,p.category);
  end loop;
  update public.walking_imports i set places_imported = true where i.recording_id = recording and i.version_id = draft;
end;
$$;
revoke all on function public.import_walking_recording_with_places(uuid,uuid,text,integer,text,text,text) from public,anon;
grant execute on function public.import_walking_recording_with_places(uuid,uuid,text,integer,text,text,text) to authenticated;
notify pgrst,'reload schema';
commit;
