alter policy recordings_read on public.path_recordings to authenticated
using (private.can_edit_building(building_id));
