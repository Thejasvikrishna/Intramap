# Walking map implementation status

This branch is incremental and is not production validated.

## Implemented in source
- Sensor step events feed a relative walking trace using the localization engine's heading convention.
- Trace preview, named markers, explicit review and retryable point uploads.
- Owner building selection and resuming an existing draft, rather than mandatory duplicate creation.
- Admin recording entry without manual X/Y, width, height or edge-distance fields.
- Atomic, permission-checked recording import into draft map nodes and edges.
- Explicit start/facing landmark alignment for additional routes and an optional endpoint join within 3 m.
- Idempotent import provenance and floor display bounds derived from observed geometry.
- Explicit publication confirmation. Visitors consume the existing published-map repository.

## Deployment
Apply supabase/migrations/0003_walking_import.sql once after existing migrations, in a transaction.
A GitHub commit does not apply SQL to Supabase. Verify live policies separately. Do not disable RLS.
The migration uses SECURITY DEFINER only for the atomic import, with an empty search path,
an authenticated-user check, parent-building authorization, draft/recording row locks and no public/anon execute.
It never publishes a version and never infers a connection merely from crossing paths.

## Required checks (not executed here)
- :app:compileDebugKotlin and :app:testDebugUnitTest.
- Physical-device straight walks, corners, stopping, resume alignment, permission denial and background pause.
- Owner import success; nonmember and anonymous rejection; published-version import rejection.
- Repeat import creates no duplicate nodes/edges; importing to another version is rejected.
- A failing import rolls back all nodes, edges, bounds and provenance.
- Start/facing/end nodes must belong to the same draft and floor; endpoint drift >3 m is rejected.
- Published recording appears after visitor refresh; unpublished draft remains private.

## Still outstanding
- Actual ARCore/reference-marker alignment and visitor heading recovery. Existing scanner is OCR-based, not ARCore.
- Automatic stride estimation/calibration: current controls assume 0.70 m; distances are estimates.
- Durable local recording recovery after process death; unsubmitted samples remain memory-only.
- On-map draft graph and imported-recording review, editing, undo, and richer map-first visitor UI.
- Explicit cross-floor connector-link authoring. Marking stairs/elevator alone does not link floors.
- Cloning published maps into editable drafts and exposing shared-editor buildings (current picker is owner-only).
- Build/device/SQL integration validation, CI and release readiness.

A new draft is empty, not an automatic copy of the published version. Review all floors before publishing.
Floor width/height here are observed display extents, not measured architectural boundaries or walls.
