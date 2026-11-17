alter table public.map_nodes
add column if not exists image_url text;

comment on column public.map_nodes.image_url is
'Optional visitor-facing Cloudinary reference photo URL';
