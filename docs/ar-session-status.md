# AR session lifecycle status

This batch adds an ARCore Session owner, frame flow, lifecycle pause/resume, failure states, and a Compose availability/permission screen. It does not implement a complete visitor AR experience: no GL camera preview, no image enrollment, no storage upload/download, no visitor navigation integration, and no stride calibration.

The session owns one Session, configures autofocus, resumes/pauses it with lifecycle events, emits frames, and closes it on disposal. It creates a new session token/adapter/aligner on start, so stale world transforms cannot survive a restart. It does not render the camera or own GL resources; the eventual renderer must call onFrameReady after session.update() and release GL resources.

The screen checks optional ARCore availability and camera permission, starts/stops the session, and forwards accepted alignment candidates through onAcceptedPose. It does not automatically update the visitor map position; that integration remains pending. The aligner is constructed from the supplied registrations and building/version/session identity; registration loading and actual visitor integration remain pending.

Remaining: actual camera preview/GL lifecycle; image selection/enrollment and SDK image-quality checks; reference-images bucket/policies; actual timestamp conversion; visitor navigation/recording integration; tracking-loss recovery; calibrated stride; visual-reference re-registration; membership UI; integrated builds, Kotlin/SQL tests and physical-device validation. The full application is not complete or production validated.

No main merge, workflow change, or live SQL execution is included. Migration 0008 must be reviewed and applied separately if not already applied.
