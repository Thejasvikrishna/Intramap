# IntraMap deployment and validation

## What is implemented

- Supabase schema for buildings, map versions, floors, nodes, edges, reference anchors, path recordings and recording points.
- Row Level Security, contributor/editor policies, published-map read access and validated publishing.
- Supabase Kotlin client configuration using a publishable key.
- Remote published-map loading and conversion to the existing navigation models.
- Admin building, floor, place/node and map-version publishing workflow.
- Path-recording workflow with turn and door markers, draft submission and review status.
- Clean visitor map-first UI with search, floor selector, route display, voice guidance and accessibility preference.
- Approximate-position routing using same-floor candidates, distance, heading agreement and graph reachability.
- Route-progress tracking using arrival and passed-node detection.
- Interactive floor map with pinch zoom, pan, route rendering, destination marker and position indicator.
- Bounded nearby-place assistance for common requests.
- Unit tests for routing, route progress, navigation session and place assistance.

## What still requires real-device validation

- Sensor-based movement accuracy.
- Camera landmark recognition reliability.
- Floor-transition detection and confirmation.
- Voice guidance quality.
- Supabase authentication and RLS behavior with real accounts.
- Real-building map alignment and route correctness.
- Performance on the target Android device.

## Supabase setup

1. Create a Supabase project.
2. Open **Database → Extensions** and enable `postgis` in the `extensions` schema.
3. Open **SQL Editor** and run:

```text
supabase/migrations/0001_schema.sql
supabase/migrations/0002_rls.sql
```

4. Create private Storage buckets:

```text
floor-plans
reference-images
path-recordings
```

5. Enable Email authentication and create an admin account.
6. Add the building owner or editor to `building_members` with role `editor` or `admin`.

## Android configuration

Add the following to `local.properties`. Do not commit these values.

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=YOUR_PUBLISHABLE_KEY
```

Never add the service-role key, database password, or JWT secret to the Android app or repository.

## Build and test

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Run the app on a physical Android device. The prior successful unit-test command verified only the selected test task at that time; it did not validate device behavior.

## Validation checklist

1. Configure Supabase and run both migrations.
2. Build and install the app.
3. Sign in as an authorized building editor.
4. Create a real building and draft map version.
5. Add a real floor with measured width and height.
6. Add real entrance, room, stairs and elevator nodes.
7. Record a path from a known entrance to a known destination.
8. Mark turns and doors during the walk.
9. Submit the recording for review.
10. Publish the map version after reviewing its geometry and connections.
11. Open the published building as a visitor.
12. Search for a mapped place.
13. Start navigation from an approximate position.
14. Walk the route and verify that instructions, position and floor transitions behave correctly.
15. Test step-free routing where mapped accessibility data exists.

## Important limitation

A recorded path is a draft observation, not automatically a verified route. A published map should be reviewed by an authorized building editor before visitors rely on it.
