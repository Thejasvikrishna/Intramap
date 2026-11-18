begin;

create table public.map_places (
  version_id uuid not null references public.map_versions(id) on delete cascade,
  place_id uuid not null,
  node_key text not null,
  name text not null check(length(name) between 1 and 120 and name = btrim(name) and name !~ '[[:cntrl:]]'),
  category text not null check(category in ('ROOM','SHOP','OFFICE','ENTRANCE','JUNCTION','STAIRS','ELEVATOR')),
  primary key(version_id,place_id),
  foreign key(version_id,node_key) references public.map_nodes(version_id,node_key) on delete cascade
);
create index map_places_node_idx on public.map_places(version_id,node_key);
alter table public.map_places enable row level security;
revoke all on public.map_places from public,anon,authenticated;
grant select on public.map_places to anon,authenticated;
create policy published_places_read on public.map_places for select to anon,authenticated using (
  exists(select 1 from public.map_versions v where v.id = map_places.version_id and v.status = 'published')
);
create policy editor_places_read on public.map_places for select to authenticated using (
  exists(select 1 from public.map_versions v where v.id = map_places.version_id and private.can_edit_building(v.building_id))
);
alter table public.walking_imports add column places_imported boolean not null default false;

create function public.import_walking_recording_with_places(
  recording uuid,draft uuid,floor_name text,floor_number integer,
  start_node text default null,facing_node text default null,end_node text default null
) returns void language plpgsql security definer set search_path = '' set lock_timeout = '5s' as $$
declare
  b uuid; prior public.walking_imports%rowtype; p public.path_recording_places%rowtype;
  first_seq integer; last_seq integer; anchor_key text;
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  select v.building_id into b from public.map_versions v where v.id = draft and v.status = 'draft' for update;
  if b is null or not private.can_edit_building(b) then raise exception 'Draft edit permission required'; end if;
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
    if not exists(select 1 from public.map_nodes n join public.path_recordings r on r.id = recording
      where n.version_id = draft and n.node_key = anchor_key and n.floor_key = r.floor_key and r.building_id = b) then
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

-- The existing draft-copy routine inserts graph nodes. Preserve attached destinations there.
create function private.copy_published_places_to_draft_node()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.map_places(version_id,place_id,node_key,name,category)
    select new.version_id,p.place_id,new.node_key,p.name,p.category
    from public.map_places p join public.map_versions source on source.id = p.version_id
    join public.map_versions target on target.id = new.version_id
    where target.status = 'draft' and source.status = 'published'
      and source.building_id = target.building_id and p.node_key = new.node_key
    on conflict(version_id,place_id) do nothing;
  return new;
end;
$$;
revoke all on function private.copy_published_places_to_draft_node() from public,anon,authenticated;
create trigger copy_published_places_to_draft_node after insert on public.map_nodes
for each row execute function private.copy_published_places_to_draft_node();

create or replace function public.review_draft_map(v uuid)
returns table(version jsonb,floors jsonb,nodes jsonb,edges jsonb,fingerprint text)
language plpgsql security definer set search_path = '' as $$
declare b uuid; payload jsonb;
begin
  select m.building_id into b from public.map_versions m where m.id = v and m.status = 'draft';
  if auth.uid() is null or b is null or not private.can_edit_building(b) then raise exception 'Only building editors may review a draft'; end if;
  select jsonb_build_object(
    'version',to_jsonb(m),
    'floors',coalesce((select jsonb_agg(to_jsonb(f) order by f.floor_key) from public.floors f where f.version_id = v),'[]'::jsonb),
    'nodes',coalesce((select jsonb_agg(to_jsonb(n) || jsonb_build_object('mappedPlaces',
      coalesce((select jsonb_agg(jsonb_build_object('placeId',p.place_id,'name',p.name,'category',p.category) order by p.place_id)
      from public.map_places p where p.version_id = v and p.node_key = n.node_key),'[]'::jsonb)) order by n.node_key)
      from public.map_nodes n where n.version_id = v),'[]'::jsonb),
    'edges',coalesce((select jsonb_agg(to_jsonb(e) order by e.edge_key) from public.map_edges e where e.version_id = v),'[]'::jsonb),
    'anchors',coalesce((select jsonb_agg(to_jsonb(a) order by a.anchor_key) from public.reference_anchors a where a.version_id = v),'[]'::jsonb),
    'places',coalesce((select jsonb_agg(to_jsonb(p) order by p.place_id) from public.map_places p where p.version_id = v),'[]'::jsonb)
  ) into payload from public.map_versions m where m.id = v;
  return query select payload->'version',payload->'floors',payload->'nodes',payload->'edges',md5(payload::text);
end;
$$;
revoke all on function public.review_draft_map(uuid) from public,anon;
grant execute on function public.review_draft_map(uuid) to authenticated;

create function public.save_draft_place(
  v uuid,expected_fingerprint text,p_place_id uuid,p_node_key text,p_name text,p_category text,p_delete boolean default false
) returns void language plpgsql security definer set search_path = '' set lock_timeout = '5s' as $$
declare b uuid; actual text;
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  select m.building_id into b from public.map_versions m where m.id = v and m.status = 'draft' for update;
  if b is null or not private.can_edit_building(b) then raise exception 'Draft edit permission required'; end if;
  select s.fingerprint into actual from public.review_draft_map(v) s;
  if expected_fingerprint is null or actual is distinct from expected_fingerprint then raise exception 'Draft changed; reload before saving'; end if;
  if p_place_id is null or p_delete is null then raise exception 'Place ID and explicit operation required'; end if;
  if p_delete then
    delete from public.map_places p where p.version_id = v and p.place_id = p_place_id;
    if not found then raise exception 'Unknown place'; end if;
    return;
  end if;
  if p_name is null or length(p_name) not between 1 and 120 or p_name <> btrim(p_name) or p_name ~ '[[:cntrl:]]'
    or p_category is null or p_category not in ('ROOM','SHOP','OFFICE','ENTRANCE','JUNCTION','STAIRS','ELEVATOR') then raise exception 'Invalid place metadata'; end if;
  if p_node_key is null or not exists(select 1 from public.map_nodes n where n.version_id = v and n.node_key = p_node_key) then
    raise exception 'Choose an existing route point in this draft';
  end if;
  insert into public.map_places(version_id,place_id,node_key,name,category) values(v,p_place_id,p_node_key,p_name,p_category)
    on conflict(version_id,place_id) do update set node_key = excluded.node_key,name = excluded.name,category = excluded.category;
end;
$$;
revoke all on function public.save_draft_place(uuid,text,uuid,text,text,text,boolean) from public,anon;
grant execute on function public.save_draft_place(uuid,text,uuid,text,text,text,boolean) to authenticated;
notify pgrst,'reload schema';
commit;
