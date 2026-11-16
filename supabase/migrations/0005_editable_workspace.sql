begin;

create function public.editable_map_buildings()
returns setof public.buildings language sql stable security definer set search_path = '' as $$
  select x.* from public.buildings x
  where auth.uid() is not null and private.can_edit_building(x.id)
  order by lower(x.name),x.id;
$$;
revoke all on function public.editable_map_buildings() from public,anon;
grant execute on function public.editable_map_buildings() to authenticated;

create function public.open_map_draft(b uuid)
returns setof public.map_versions language plpgsql security definer set search_path = '' set lock_timeout = '5s' as $$
declare current_draft public.map_versions%rowtype; source_version uuid; next_number integer;
begin
  if auth.uid() is null or not private.can_edit_building(b) then raise exception 'Building edit permission required'; end if;
  -- Serialize new draft creation for this building; existing drafts are never overwritten.
  perform 1 from public.buildings x where x.id = b for update;
  if not found or not private.can_edit_building(b) then raise exception 'Building edit permission required'; end if;
  select m.* into current_draft from public.map_versions m
    where m.building_id = b and m.status = 'draft' order by m.version_no desc limit 1 for update;
  if found then return next current_draft; return; end if;
  select m.id into source_version from public.map_versions m
    where m.building_id = b and m.status = 'published' order by m.version_no desc limit 1 for share;
  select coalesce(max(m.version_no),0)+1 into next_number from public.map_versions m where m.building_id = b;
  insert into public.map_versions(building_id,version_no,status,created_by)
    values(b,next_number,'draft',auth.uid()) returning * into current_draft;
  if source_version is not null then
    insert into public.floors(version_id,floor_key,floor_number,name,width_m,height_m,elevation_m,plan_path)
      select current_draft.id,f.floor_key,f.floor_number,f.name,f.width_m,f.height_m,f.elevation_m,f.plan_path
      from public.floors f where f.version_id = source_version;
    insert into public.map_nodes(version_id,node_key,floor_key,name,node_type,x,y,connector_group_id,keywords,description,is_destination)
      select current_draft.id,n.node_key,n.floor_key,n.name,n.node_type,n.x,n.y,n.connector_group_id,n.keywords,n.description,n.is_destination
      from public.map_nodes n where n.version_id = source_version;
    insert into public.map_edges(version_id,edge_key,from_key,to_key,distance_m,accessibility_type,is_available)
      select current_draft.id,e.edge_key,e.from_key,e.to_key,e.distance_m,e.accessibility_type,e.is_available
      from public.map_edges e where e.version_id = source_version;
    insert into public.reference_anchors(version_id,anchor_key,floor_key,name,image_path,physical_width_m,x,y,heading_deg)
      select current_draft.id,a.anchor_key,a.floor_key,a.name,a.image_path,a.physical_width_m,a.x,a.y,a.heading_deg
      from public.reference_anchors a where a.version_id = source_version;
  end if;
  return next current_draft;
end;
$$;
revoke all on function public.open_map_draft(uuid) from public,anon;
grant execute on function public.open_map_draft(uuid) to authenticated;

create function public.pending_walking_recordings(b uuid,f text)
returns setof public.path_recordings language sql stable security definer set search_path = '' as $$
  select r.* from public.path_recordings r
  where auth.uid() is not null and private.can_edit_building(b)
    and r.building_id = b and r.floor_key = f and r.status = 'submitted'
    and not exists(select 1 from public.walking_imports i where i.recording_id = r.id)
  order by r.created_at,r.id;
$$;
revoke all on function public.pending_walking_recordings(uuid,text) from public,anon;
grant execute on function public.pending_walking_recordings(uuid,text) to authenticated;
notify pgrst,'reload schema';
commit;
