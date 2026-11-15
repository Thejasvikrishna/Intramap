begin;

create function public.review_draft_map(v uuid)
returns table(version jsonb, floors jsonb, nodes jsonb, edges jsonb, fingerprint text)
language plpgsql security definer set search_path = '' as $$
declare b uuid; payload jsonb;
begin
  select m.building_id into b from public.map_versions m where m.id = v and m.status = 'draft';
  if auth.uid() is null or b is null or not private.can_edit_building(b) then
    raise exception 'Only building editors may review a draft';
  end if;
  select jsonb_build_object(
    'version',to_jsonb(m),
    'floors',coalesce((select jsonb_agg(to_jsonb(f) order by f.floor_key) from public.floors f where f.version_id = v),'[]'::jsonb),
    'nodes',coalesce((select jsonb_agg(to_jsonb(n) order by n.node_key) from public.map_nodes n where n.version_id = v),'[]'::jsonb),
    'edges',coalesce((select jsonb_agg(to_jsonb(e) order by e.edge_key) from public.map_edges e where e.version_id = v),'[]'::jsonb),
    'anchors',coalesce((select jsonb_agg(to_jsonb(a) order by a.anchor_key) from public.reference_anchors a where a.version_id = v),'[]'::jsonb)
  ) into payload from public.map_versions m where m.id = v;
  return query select payload->'version',payload->'floors',payload->'nodes',payload->'edges',md5(payload::text);
end;
$$;
revoke all on function public.review_draft_map(uuid) from public, anon;
grant execute on function public.review_draft_map(uuid) to authenticated;

create function public.publish_reviewed_map(v uuid, reviewed_fingerprint text)
returns void language plpgsql security definer set search_path = '' set lock_timeout = '5s' as $$
declare b uuid; actual text;
begin
  if auth.uid() is null then raise exception 'Sign in first'; end if;
  select m.building_id into b from public.map_versions m where m.id = v and m.status = 'draft' for update;
  if b is null or not private.can_edit_building(b) then raise exception 'Draft publication is not permitted'; end if;
  if reviewed_fingerprint is null or reviewed_fingerprint !~ '^[0-9a-f]{32}$' then raise exception 'Review the current draft first'; end if;
  -- Short publication-only locks prevent graph writes between comparison and publication.
  lock table public.floors, public.map_nodes, public.map_edges, public.reference_anchors in share mode;
  select s.fingerprint into actual from public.review_draft_map(v) s;
  if actual is distinct from reviewed_fingerprint then raise exception 'Draft changed; reload and review every floor again'; end if;
  if not exists(select 1 from public.map_nodes n where n.version_id = v)
     or not exists(select 1 from public.map_edges e where e.version_id = v) then raise exception 'Draft needs recorded nodes and paths'; end if;
  if exists(select 1 from public.map_nodes n left join public.floors f on f.version_id = n.version_id and f.floor_key = n.floor_key
    where n.version_id = v and (f.id is null or n.x::text in ('NaN','Infinity','-Infinity') or n.y::text in ('NaN','Infinity','-Infinity')
      or n.x < 0 or n.y < 0 or n.x > f.width_m or n.y > f.height_m)) then raise exception 'Invalid node or floor bounds'; end if;
  if exists(select 1 from public.map_edges e where e.version_id = v and
    (e.distance_m <= 0 or e.distance_m::text in ('NaN','Infinity','-Infinity'))) then raise exception 'Invalid path distance'; end if;
  perform public.publish_map_version(v);
end;
$$;
revoke all on function public.publish_reviewed_map(uuid,text) from public, anon;
grant execute on function public.publish_reviewed_map(uuid,text) to authenticated;
-- The reviewed wrapper replaces direct app access to the legacy publication RPC.
revoke execute on function public.publish_map_version(uuid) from authenticated;
commit;
