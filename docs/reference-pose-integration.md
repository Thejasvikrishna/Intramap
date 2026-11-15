# Reference-pose positioning: foundation, not a working AR scanner

The current app still uses OCR and step-based positioning. This patch does NOT add an ARCore dependency, camera session, image database, enrollment UI, storage upload or NavigationSession integration.

## Added
- Rigid planar registration and inverse transforms. No inferred scale calibration.
- Image and camera observations are distinct from CameraLandmarkObservation (OCR).
- Candidate generation scoped to building, immutable map version and world-session epoch.
- Live-image/camera flags, elapsed-age policy, increasing frame IDs, bounds and explicit floor-confirmation guards.
- Rejected inputs do not update the accepted frame/floor state. Candidates are not calibrated confidence or proof of accuracy.

## Coordinate contract for the forthcoming SDK adapter
Use a single horizontal plane consistently (for example AR world X/Z), with metres and heading from plane +X toward +Y. This is not compass north. Preserve camera-to-marker offset; never put the visitor directly at the marker centre.
An image orientation means its projected local +X direction; camera heading means projected optical forward. Validate that those axes have usable horizontal projections before constructing planar poses. The adapter must map that convention explicitly into the existing navigation heading convention.
ARCore image +X is left-to-right, +Y is the outward normal, and +Z is top-to-bottom. Camera optical forward is -Z. The image/camera poses must come from the same current frame with live camera tracking and FULL_TRACKING image tracking, not LAST_KNOWN_POSE or an identity/cached pose.
The adapter must normalize capture time to Android elapsed time. Do not substitute callback-receive time when actual capture time cannot be established. SDK frame IDs are only ordered within the current world epoch and must not be compared to elapsed milliseconds. Generate a new session token and aligner whenever the world frame/session is replaced or reset.
The default 500 ms age cutoff is an engineering policy, not measured sensor accuracy. Do not persist a world-to-map transform across epochs or reuse enrollment metadata from another map version.

## Remaining work
Image enrollment, quality/duplicate-image checks and multi-reference consistency validation; version-scoped image/pose persistence and downloads; actual ARCore session/lifecycle/camera rendering; timestamp/axis projection; visitor initialization and tracking-loss handling; recording integration; stride calibration from independently measured motion; visual-reference re-registration; build, SQL and physical-device validation.
The mathematical model requires map and AR coordinates in compatible metric scale. Aligning an inaccurately scaled walking map does not correct that map's scale.

## API references
- https://developers.google.com/ar/reference/java/com/google/ar/core/AugmentedImage
- https://developers.google.com/ar/reference/java/com/google/ar/core/AugmentedImage.TrackingMethod
- https://developers.google.com/ar/reference/java/com/google/ar/core/Camera

Kotlin tests are source additions; they have not been run here. Existing visitors and recordings continue through their current pipelines until the SDK adapter is implemented and validated.
