# Walking map implementation status

Source implementation only: the full requested feature set is not complete or production validated.

## Implemented in source
- Walking traces, named markers, pan/zoom preview and retryable point uploads.
- Account-scoped local checkpoints, paused recovery and frozen geometry after upload attempts. Only acknowledged writes are durable; sudden termination may lose pending samples.
- Owned/shared editable-building selection, unchanged draft resumption and atomic cloning of the published graph into a newly created draft.
- Cloning preserves floors, node keys, connectors, keywords, edges, accessibility and reference-anchor metadata. Existing image/storage paths are reused; objects are not copied or uploaded.
- Pending-recording selection excludes recordings already imported into any version.
- Atomic recording import, explicit start/facing alignment, optional endpoint joins and import provenance.
- Map-first visitor interface connected to the existing movement-driven session.
- Floor-by-floor visual draft review and publication checked against the server snapshot fingerprint.

## Outstanding
- ARCore marker registration, pose alignment and automatic visitor heading recovery. Current scanner is OCR-based.
- Calibrated stride estimation; existing motion controls assume 0.70 m.
- On-map editing, undo, and explicit cross-floor connector authoring.
- Membership-management UI; shared selection currently respects memberships already stored in the database.
- Verified app compilation, test execution, SQL integration and physical-device positioning validation.

## Deployment and data preservation
GitHub commits do not apply SQL to Supabase. Apply new migrations 0003, 0004 and 0005 in order only if not already applied. Do not rerun the initial schema on a populated database. Keep RLS enabled.
Migration 0004 replaces direct authenticated access to the legacy publication RPC with the reviewed wrapper; deploy the matching app before relying on publication.
Migration 0005 changes workspace opening: an existing draft is returned unchanged, including a previously empty draft. Only a newly created draft clones the current published version. With no published version, a new draft is empty by design.
The published version is not edited by cloning. New row UUIDs are generated while version-scoped graph keys are preserved. Any failure rolls back the clone transaction.
Floor dimensions derived from recorded geometry are observed display extents, not architectural boundaries.

## Validation still required
Compile and run unit tests; test recovery, failed storage, failed uploads, account changes and device reboot.
Verify owner/member access and anonymous/nonmember rejection. Test simultaneous draft opens, unchanged draft resumption, exact metadata copying and rollback on clone failure.
Verify stale-review publication rejection, floor connections and real-building navigation before release.
CI infrastructure exists but is not a substitute for these checks. No additional CI-reporting workflow is part of this feature patch.
