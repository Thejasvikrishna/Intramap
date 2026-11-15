begin;
create function public.save_draft_floor_link(v uuid,expected_fingerprint text,change jsonb)
returns void language plpgsql security definer set search_path = '' set lock_timeout = '5s' as $$
declare b uuid; actual text; a public.map_nodes%rowtype; z public.map_nodes%rowtype;
  e public.map_edges%rowtype; kind text; g text; action text;
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  select m.building_id into b from public.map_versions m where m.id = v and m.status = 'draft' for update;
  if b is null or not private.can_edit_building(b) then raise exception 'Draft edit permission required'; end if;
  lock table public.floors,public.map_nodes,public.map_edges,public.reference_anchors in share row exclusive mode;
  select s.fingerprint into actual from public.review_draft_map(v) s;
  if expected_fingerprint is null or actual is distinct from expected_fingerprint then raise exception 'Draft changed; reload first'; end if;
  if (change->>'confirmed')::boolean is distinct from true then raise exception 'Explicit connector confirmation required'; end if;
  action := change->>'action';
  if action = 'remove' then
    select q.* into e from public.map_edges q where q.version_id = v and q.edge_key = change->>'edge';
    if not found then raise exception 'Unknown floor link'; end if;
    select n.* into a from public.map_nodes n where n.version_id = v and n.node_key = e.from_key;
    if not found then raise exception 'Missing source landing'; end if;
    select n.* into z from public.map_nodes n where n.version_id = v and n.node_key = e.to_key;
    if not found or a.floor_key = z.floor_key then raise exception 'Only cross-floor links may be removed here'; end if;
    delete from public.map_edges where id = e.id;
    update public.map_nodes n set connector_group_id = null
      where n.version_id = v and n.node_key in (a.node_key,z.node_key) and not exists(
        select 1 from public.map_edges q join public.map_nodes other
          on other.version_id = q.version_id and other.node_key = case when q.from_key = n.node_key then q.to_key else q.from_key end
        where q.version_id = v and (q.from_key = n.node_key or q.to_key = n.node_key) and other.floor_key <> n.floor_key);
    return;
  end if;
  if action is distinct from 'link' then raise exception 'Unsupported connector action'; end if;
  kind := change->>'kind';
  if kind is null or kind not in ('STAIRS','ELEVATOR') then raise exception 'Choose stairs or elevator'; end if;
  select n.* into a from public.map_nodes n where n.version_id = v and n.node_key = change->>'from';
  if not found then raise exception 'Unknown source landing'; end if;
  select n.* into z from public.map_nodes n where n.version_id = v and n.node_key = change->>'to';
  if not found then raise exception 'Unknown target landing'; end if;
  if a.floor_key = z.floor_key or a.node_type <> kind or z.node_type <> kind then raise exception 'Choose matching connector types on different floors'; end if;
  if not exists(select 1 from public.floors f where f.version_id = v and f.floor_key = a.floor_key)
     or not exists(select 1 from public.floors f where f.version_id = v and f.floor_key = z.floor_key) then raise exception 'Unknown landing floor'; end if;
  if exists(select 1 from public.map_edges q where q.version_id = v and
    ((q.from_key = a.node_key and q.to_key = z.node_key) or (q.from_key = z.node_key and q.to_key = a.node_key))) then raise exception 'Landings already connected'; end if;
  if nullif(btrim(a.connector_group_id),'') is not null and nullif(btrim(z.connector_group_id),'') is not null
     and a.connector_group_id <> z.connector_group_id then raise exception 'Different connector groups cannot be merged implicitly'; end if;
  if (a.connector_group_id is not null and a.connector_group_id <> btrim(a.connector_group_id))
     or (z.connector_group_id is not null and z.connector_group_id <> btrim(z.connector_group_id)) then raise exception 'Clean up invalid connector group identifiers first'; end if;
  if exists(select 1 from public.map_edges q
    join public.map_nodes s on s.version_id = q.version_id and s.node_key = q.from_key
    join public.map_nodes t on t.version_id = q.version_id and t.node_key = q.to_key
    where q.version_id = v and s.floor_key <> t.floor_key and
      (q.from_key in (a.node_key,z.node_key) or q.to_key in (a.node_key,z.node_key)) and
      (nullif(s.connector_group_id,'') is null or nullif(t.connector_group_id,'') is null or s.connector_group_id is distinct from t.connector_group_id))
    then raise exception 'Repair inconsistent existing floor links before extending the connector'; end if;
  g := coalesce(nullif(btrim(a.connector_group_id),''),nullif(btrim(z.connector_group_id),''),'connector_'||gen_random_uuid()::text);
  if exists(select 1 from public.map_nodes n where n.version_id = v and n.connector_group_id = g and
    (n.node_type <> kind or (n.floor_key = a.floor_key and n.node_key <> a.node_key) or (n.floor_key = z.floor_key and n.node_key <> z.node_key)))
    or exists(select 1 from public.map_nodes n where n.version_id = v and n.connector_group_id = g group by n.floor_key having count(*) > 1)
    then raise exception 'Connector group needs one matching landing per floor'; end if;
  update public.map_nodes set connector_group_id = g where version_id = v and node_key in (a.node_key,z.node_key);
  -- Legacy distance_m stores a nominal routing penalty for transfers, NOT measured metres.
  -- Matching clients exclude cross-floor edges from walking-distance totals/instructions.
  insert into public.map_edges(version_id,edge_key,from_key,to_key,distance_m,accessibility_type)
    values(v,'floor_link_'||gen_random_uuid()::text,a.node_key,z.node_key,
      case when kind = 'STAIRS' then 20 else 30 end,case when kind = 'STAIRS' then 'STAIRS_ONLY' else 'NORMAL' end);
end;
$$;
revoke all on function public.save_draft_floor_link(uuid,text,jsonb) from public,anon;
grant execute on function public.save_draft_floor_link(uuid,text,jsonb) to authenticated;
notify pgrst,'reload schema';
commit;
