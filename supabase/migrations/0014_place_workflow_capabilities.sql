begin;
create function public.place_workflow_capabilities()
returns jsonb language sql stable set search_path = '' as $$
  select jsonb_build_object('apiVersion',1,'submission',true,'draftImport',true,'draftEdit',true,'publishedPlaces',true);
$$;
revoke all on function public.place_workflow_capabilities() from public,anon;
grant execute on function public.place_workflow_capabilities() to authenticated;
notify pgrst,'reload schema';
commit;
