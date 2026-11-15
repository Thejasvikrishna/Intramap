# Walking map status

Source implementation only; full scope and validation remain incomplete.

Implemented in source: walking traces and markers; retryable uploads; account-scoped recording checkpoints and paused recovery; shared workspaces; new-draft cloning; atomic trace imports; map-first visitor UI; visual draft review; snapshot-checked edits and publication; on-map corrections, labels, path blocking, verified nearby same-floor joins, and undo/redo for up to 20 unsaved edits.

Draft edits are memory-only until saved. Recording checkpoints are separate; a sudden termination can lose pending, unacknowledged samples. Failed or unconfirmed edit saves require reconciliation by reload. All floor reviews reset after edits/saves. Publication requires a clean reviewed snapshot.

Outstanding: ARCore marker enrollment and pose/heading recovery; calibrated stride (current assumption 0.70 m); cross-floor connector authoring; visual-reference re-registration; membership-management UI; build, SQL and physical-device validation.

Apply only unapplied migrations 0003–0006 in order, after reviewing the matching app changes. These commits do not run SQL. Do not rerun the initial schema on a populated database or disable RLS. Migration 0004 replaces legacy authenticated publication. Migration 0005 clones only newly created drafts; existing drafts remain unchanged. Migration 0006 guards draft edits and rejects geometry moves on floors with visual references.

Tests are source additions, not verified results. Validate touch transforms, undo/redo, discard prompts, stale saves, authorization, atomic rollback, recovery, real walking and floor transitions before release. No workflow changes are part of this patch.
