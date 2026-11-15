# Walking map status

Source implementation only; full scope and validation remain incomplete.

Implemented in source: walking traces/markers, retryable uploads, account-scoped recording recovery, shared workspaces, published-map cloning, trace imports, map-first visitor UI, draft review/editing, up to 20 unsaved undo steps, snapshot-checked saves/publication, and explicit cross-floor link creation/removal controls.

In draft review, expand Cross-floor links, select a saved STAIRS/ELEVATOR source point, choose its matching landing on another floor, and confirm the physical bidirectional connection. All matching candidates are offered; proximity and shared names never create a link. Removal has a separate dialog naming both landings. These operations save immediately to the draft through migration 0007, not through local undo. They are disabled for dirty, invalid or unreconciled drafts. The whole write/reload sequence remains busy; failures require reconciliation. Every operation resets all floor reviews.

Routing separates walking metres from nominal transfer cost: new links use policy penalties 20 for stairs and 30 for elevator in the legacy distance_m column. Those are not measured metres, seconds or calibrated estimates. Route.totalCost includes them; walking distance and instruction/progress distances exclude transfers. Independent floor coordinates are not compared for cross-floor heuristics. Deploy matching clients before authoring links.

Remaining: ARCore image enrollment, map/pose registration and heading recovery; calibrated stride (currently 0.70 m); visual-reference re-registration; membership management; integrated compilation, Kotlin/SQL tests and physical-device validation. The full app is not complete or production validated.

Draft edits/undo are memory-only until saved; recording checkpoints are separate and unacknowledged pending samples may be lost. GitHub commits do not apply SQL. Review and apply only unapplied migrations 0003–0007 in order; keep RLS enabled and do not rerun the initial schema on populated data. No main merge, workflow changes or live migration execution is included.
