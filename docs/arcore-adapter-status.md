# ARCore adapter status

This patch adds a pinned ARCore dependency and a stateless SDK-to-domain adapter. It does not implement complete visitor AR positioning. Dependency resolution, compilation, Kotlin tests and device behavior have not been verified here.

The adapter uses image local +X versus camera optical -Z, projects both into world X/Z, converts Float coordinates explicitly to Double, and rejects unstable horizontal projections. It requires matching building/version/image key, camera and image TRACKING, FULL_TRACKING image tracking, membership in the current frame's updated images, a positive frame ID and an explicitly supplied elapsed capture time. It never relabels callback receipt time as capture time. The 500 ms freshness and 0.5 horizontal-projection cutoffs are engineering policies, not measured accuracy.

Feed returned observations into ReferencePoseAligner; do not treat an observation as an accepted fix. The aligner retains bounds, frame ordering and physical floor-confirmation guards. The session owner must supply a trustworthy elapsed-time conversion and a new session token/aligner whenever the AR world changes. Do not reuse world transforms across sessions or map versions.

Still pending: optional-AR manifest/availability/install/permission flow; mutually exclusive CameraX and ARCore camera ownership; GL camera preview and lifecycle; image selection/enrollment and SDK image-quality checks; protected version-scoped persistence, downloads and database population; actual timestamp conversion; visitor/session and recording integration; tracking-loss handling; stride calibration; visual-reference re-registration; membership UI; integrated builds, SQL and physical-device validation. No previously proposed reference_images table or bucket is assumed to exist. The failed seven-file enrollment/visitor draft is not part of this patch.

The geometry tests use pure array inputs and do not invoke ARCore or Bitmap native APIs. Their presence does not mean they were executed. No main merge, workflow change or live database execution is included.
