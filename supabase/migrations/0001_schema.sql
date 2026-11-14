create schema if not exists extensions;
create extension if not exists postgis with schema extensions;
create schema if not exists private;

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text,
  created_at timestamptz not null default now()
);

create table public.buildings (
  id uuid primary key default gen_random_uuid(),
  building_key text not null unique,
  name text not null,
  description text not null default '',
  owner_id uuid not null references auth.users(id),
  latitude double precision,
  longitude double precision,
  north_bearing_deg double precision,
  created_at timestamptz not null default now()
);

create table public.building_members (
  building_id uuid references public.buildings(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  role text not null check (role in ('editor','admin')),
  primary key (building_id, user_id)
);

create table public.map_versions (
  id uuid primary key default gen_random_uuid(),
  building_id uuid not null references public.buildings(id) on delete cascade,
  version_no int not null,
  status text not null default 'draft' check (status in ('draft','in_review','published','archived')),
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  published_at timestamptz,
  unique (building_id, version_no)
);
create unique index one_published_per_building on public.map_versions(building_id) where status = 'published';

create table public.floors (
  id uuid primary key default gen_random_uuid(),
  version_id uuid not null references public.map_versions(id) on delete cascade,
  floor_key text not null,
  floor_number int not null,
  name text not null,
  width_m real not null check (width_m > 0),
  height_m real not null check (height_m > 0),
  elevation_m real not null default 0,
  plan_path text,
  unique (version_id, floor_key)
);

create table public.map_nodes (
  id uuid primary key default gen_random_uuid(),
  version_id uuid not null references public.map_versions(id) on delete cascade,
  node_key text not null,
  floor_key text not null,
  name text not null,
  node_type text not null,
  x real not null,
  y real not null,
  connector_group_id text,
  keywords text[] not null default '{}',
  description text,
  is_destination boolean not null default true,
  unique (version_id, node_key)
);

create table public.map_edges (
  id uuid primary key default gen_random_uuid(),
  version_id uuid not null references public.map_versions(id) on delete cascade,
  edge_key text not null,
  from_key text not null,
  to_key text not null,
  distance_m real not null check (distance_m > 0),
  accessibility_type text not null default 'NORMAL',
  is_available boolean not null default true,
  unique (version_id, edge_key)
);

create table public.reference_anchors (
  id uuid primary key default gen_random_uuid(),
  version_id uuid not null references public.map_versions(id) on delete cascade,
  anchor_key text not null,
  floor_key text not null,
  name text not null,
  image_path text,
  physical_width_m real,
  x real not null,
  y real not null,
  heading_deg real not null,
  unique (version_id, anchor_key)
);

create table public.path_recordings (
  id uuid primary key default gen_random_uuid(),
  building_id uuid not null references public.buildings(id) on delete cascade,
  floor_key text not null,
  recorder_id uuid not null references auth.users(id),
  start_anchor_key text,
  end_anchor_key text,
  status text not null default 'draft' check (status in ('draft','submitted','approved','rejected')),
  created_at timestamptz not null default now()
);

create table public.path_recording_points (
  recording_id uuid not null references public.path_recordings(id) on delete cascade,
  seq int not null,
  x real not null,
  y real not null,
  t_ms bigint not null,
  heading_deg real,
  marker text,
  primary key (recording_id, seq)
);
