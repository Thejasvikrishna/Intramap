begin;
create function public.save_draft_map_edits(v uuid,expected_fingerprint text,changes jsonb)
returns void language plpgsql security definer set search_path = '' set lock_timeout = '5s' as $$
declare b uuid; actual text; p jsonb; n public.map_nodes%rowtype; a public.map_nodes%rowtype;
  z public.map_nodes%rowtype; f public.floors%rowtype; e public.map_edges%rowtype;
  nx real; ny real; label text; kind text; join_length real;
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  select m.building_id into b from public.map_versions m where m.id = v and m.status = 'draft' for update;
  if b is null or not private.can_edit_building(b) then raise exception 'Draft edit permission required'; end if;
  if (changes->>'building_id') is distinct from b::text then raise exception 'Snapshot building mismatch'; end if;
  lock table public.floors,public.map_nodes,public.map_edges,public.reference_anchors in share row exclusive mode;
  select s.fingerprint into actual from public.review_draft_map(v) s;
  if expected_fingerprint is null or actual is distinct from expected_fingerprint then raise exception 'Draft changed; reload before saving'; end if;
  if jsonb_typeof(changes->'nodes') is distinct from 'array' or jsonb_typeof(changes->'edges') is distinct from 'array' then raise exception 'Invalid edit arrays'; end if;
  if jsonb_array_length(changes->'nodes') > 10000 or jsonb_array_length(changes->'edges') > 20000
     or jsonb_array_length(changes->'nodes') + jsonb_array_length(changes->'edges') = 0 then raise exception 'Invalid edit count'; end if;
  if (select count(distinct x.value->>'key') from jsonb_array_elements(changes->'nodes') as x(value)) <> jsonb_array_length(changes->'nodes')
     or (select count(distinct x.value->>'key') from jsonb_array_elements(changes->'edges') as x(value)) <> jsonb_array_length(changes->'edges') then raise exception 'Duplicate edit keys'; end if;
  for p in select value from jsonb_array_elements(changes->'nodes') loop
    select q.* into n from public.map_nodes q where q.version_id = v and q.node_key = p->>'key';
    if not found then raise exception 'Unknown edited node'; end if;
    label := btrim(p->>'name'); kind := p->>'type'; nx := (p->>'x')::real; ny := (p->>'y')::real;
    if label is null or length(label) not between 1 and 120 or kind is null or kind not in ('ROOM','ENTRANCE','JUNCTION','STAIRS','ELEVATOR') then raise exception 'Invalid place metadata'; end if;
    select q.* into f from public.floors q where q.version_id = v and q.floor_key = n.floor_key;
    if not found then raise exception 'Unknown node floor'; end if;
    if nx is null or ny is null or nx::text in ('NaN','Infinity','-Infinity') or ny::text in ('NaN','Infinity','-Infinity')
       or nx < 0 or ny < 0 or nx > f.width_m or ny > f.height_m
       or sqrt(power(nx-n.x,2)+power(ny-n.y,2)) > 3 then raise exception 'Node correction outside bounds or beyond 3 m'; end if;
    if (nx <> n.x or ny <> n.y) and exists(select 1 from public.reference_anchors q where q.version_id = v and q.floor_key = n.floor_key)
      then raise exception 'Floor geometry has visual references; re-register anchors before moving points'; end if;
    if kind <> n.node_type and exists(select 1 from public.map_edges q
      join public.map_nodes s on s.version_id = q.version_id and s.node_key = q.from_key
      join public.map_nodes t on t.version_id = q.version_id and t.node_key = q.to_key
      where q.version_id = v and (q.from_key = n.node_key or q.to_key = n.node_key) and s.floor_key <> t.floor_key)
      then raise exception 'Cannot change the type of a linked floor connector'; end if;
    update public.map_nodes set name = label,node_type = kind,x = nx,y = ny where id = n.id;
  end loop;
  for p in select value from jsonb_array_elements(changes->'edges') loop
    if p->>'key' is null or length(p->>'key') not between 1 and 120 or left(p->>'key',6) = '__nav_' then raise exception 'Invalid path key'; end if;
    select q.* into e from public.map_edges q where q.version_id = v and q.edge_key = p->>'key';
    if found then
      if e.from_key is distinct from p->>'from' or e.to_key is distinct from p->>'to' then raise exception 'Existing path endpoints cannot change'; end if;
      update public.map_edges set is_available = (p->>'available')::boolean where id = e.id;
    else
      if (changes->>'joins_verified')::boolean is distinct from true then raise exception 'Explicit physical join confirmation required'; end if;
      select q.* into a from public.map_nodes q where q.version_id = v and q.node_key = p->>'from';
      if not found then raise exception 'Unknown join origin'; end if;
      select q.* into z from public.map_nodes q where q.version_id = v and q.node_key = p->>'to';
      if not found then raise exception 'Unknown join target'; end if;
      join_length := sqrt(power(a.x-z.x,2)+power(a.y-z.y,2));
      if a.node_key = z.node_key or a.floor_key <> z.floor_key or join_length <= 0.05 or join_length > 3
        then raise exception 'Only nearby same-floor endpoints may be joined'; end if;
      if exists(select 1 from public.map_edges q where q.version_id = v and
        ((q.from_key = a.node_key and q.to_key = z.node_key) or (q.from_key = z.node_key and q.to_key = a.node_key))) then raise exception 'Endpoints already connected'; end if;
      insert into public.map_edges(version_id,edge_key,from_key,to_key,distance_m,is_available)
        values(v,p->>'key',a.node_key,z.node_key,join_length,(p->>'available')::boolean);
    end if;
  end loop;
  if exists(select 1 from public.map_edges q
    join public.map_nodes s on s.version_id = q.version_id and s.node_key = q.from_key
    join public.map_nodes t on t.version_id = q.version_id and t.node_key = q.to_key
    where q.version_id = v and s.floor_key = t.floor_key and sqrt(power(s.x-t.x,2)+power(s.y-t.y,2)) <= 0.05)
    then raise exception 'Corrections cannot collapse paths'; end if;
  update public.map_edges q set distance_m = sqrt(power(s.x-t.x,2)+power(s.y-t.y,2))
    from public.map_nodes s,public.map_nodes t
    where q.version_id = v and s.version_id = v and t.version_id = v and s.node_key = q.from_key and t.node_key = q.to_key
      and s.floor_key = t.floor_key and
      (s.node_key in (select x.value->>'key' from jsonb_array_elements(changes->'nodes') as x(value))
       or t.node_key in (select x.value->>'key' from jsonb_array_elements(changes->'nodes') as x(value)));
end;
$$;
revoke all on function public.save_draft_map_edits(uuid,text,jsonb) from public,anon;
grant execute on function public.save_draft_map_edits(uuid,text,jsonb) to authenticated;
notify pgrst,'reload schema';
commit;
