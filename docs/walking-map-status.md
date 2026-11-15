# Walking map status

Source implementation only; full scope and validation remain incomplete.

Implemented in source: walking traces/markers, retryable uploads, account-scoped recording recovery, shared workspaces, published-map cloning, trace imports, map-first visitor UI, draft review/editing, up to 20 unsaved undo steps and snapshot-checked saves/publication.

Cross-floor foundation: migration 0007 and its client adapter add explicit confirmed link/removal operations. They preserve landing coordinates, require matching stairs/elevator types, reject implicit group merges/duplicate floor landings and enforce draft permissions/fingerprints. The authoring SCREEN IS STILL PENDING; this endpoint does not automatically connect floors.

Routing now disables Euclidean heuristics for independent floor frames, defensively excludes stairs from step-free routes, and separates walking metres from transfer cost. The legacy distance_m column carries nominal transfer penalties (20 stairs, 30 elevator) for new links. These are policy weights, not measured distances, seconds or calibrated travel estimates. Route.totalCost includes them; totalDistance, instruction distances and progress distance exclude transfers. Deploy matching clients before using newly authored links.

Remaining: cross-floor authoring UI; ARCore image enrollment, map/pose registration and heading recovery; calibrated stride (currently 0.70 m); visual-reference re-registration; membership management; integrated builds, SQL tests and physical-device validation.

Draft edits/undo are memory-only until Save. Recording checkpoints are separate; unacknowledged pending samples can be lost. Failed/unconfirmed saves need reload reconciliation. Review all floors again after edits.

GitHub commits do not execute SQL. Apply only unapplied migrations 0003–0007 in order after reviewing matching client changes. Keep RLS enabled; do not rerun the initial schema on populated data. Test authorization, stale writes, grouping, removal rollback and concurrent publication. Kotlin tests are added but have not been executed here. No main merge or workflow changes are included.
