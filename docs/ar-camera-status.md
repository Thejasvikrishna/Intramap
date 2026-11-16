# AR camera preview and enrollment status

This batch adds a minimal ARCore GL renderer, an image picker, and an enrollment screen. It does not complete the application.

The renderer calls Session.update(), sets display geometry, and forwards the resulting Frame. It intentionally does not draw overlays, enroll images, or update visitor position. The renderer must be attached to a GLSurfaceView owned by an Android lifecycle-aware screen; this patch does not replace the prior Compose screen or wire it into navigation.

The enrollment screen validates the selected image and metadata, then calls ReferenceImageSource.register. It assumes the reference-images bucket and database table/policies already exist; it does not create them. The duplicate check currently passes an empty known-digest set, so it prevents duplicate uploads only when the caller supplies existing hashes; server constraints remain the durable safeguard.

Still pending: integrate the renderer with ArReferenceCameraScreen and lifecycle; wire enrollment into draft review; create/verify storage bucket and policies; load registrations into visitor positioning; actual timestamp conversion; visitor navigation/recording integration; tracking recovery; calibrated stride; re-registration; membership UI; integrated builds, Kotlin/SQL tests and physical-device validation.

No main merge, workflow change, or live SQL execution is included.
