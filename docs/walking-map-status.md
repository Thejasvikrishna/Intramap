# Walking map status

Source implementation only; the full application is not complete or production validated.

Implemented in source: walking traces/markers, retryable uploads, account-scoped recording recovery, shared workspaces, published-map cloning, trace imports, map-first visitor UI, draft review/editing, undo, snapshot-checked saves/publication, cross-floor link authoring/removal, reference-pose alignment math, guarded ARCore live-frame adapter, reference-image persistence schema, registration reader, optional ARCore manifest and availability/permission checks.

Migration 0008 adds a version-scoped reference_images table with unique reference keys and storage paths, plus owner-only RLS. It does not create the reference-images storage bucket or storage policies. The registration reader returns domain registrations for the aligner; it does not verify physical marker placement.

The ARCore adapter requires live camera/image tracking, FULL_TRACKING, current-frame membership, matching identity, positive frame timestamp and explicitly supplied elapsed capture time. It preserves camera-to-marker offset and rejects near-vertical projections. The availability checker distinguishes installed, install/update-needed, unsupported and unknown states; it does not start a camera session.

Still pending: AR session/lifecycle and GL camera rendering; CameraX/ARCore camera ownership; image selection/enrollment and SDK image-quality checks; storage bucket/policies; actual timestamp conversion; visitor navigation/session/recording integration; tracking-loss handling; stride calibration; visual-reference re-registration; membership management; integrated builds, Kotlin/SQL tests and physical-device validation.

GitHub commits do not apply SQL. Review and apply only unapplied migrations 0003–0008 in order; keep RLS enabled and do not rerun the initial schema on populated data. No main merge, workflow changes or live migration execution is included.
