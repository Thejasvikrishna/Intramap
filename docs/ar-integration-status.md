# AR integration status

This batch integrates the minimal GL renderer into ArReferenceCameraScreen and opens reference-image enrollment from a selected draft point. It does not complete the application.

The screen creates a GLSurfaceView only while its AR session is running, scopes it to the active session with key(activeSession), pauses it on release, and forwards each rendered frame to ArReferenceSession.onFrameReady. The renderer still does not draw the actual camera background; it only updates the session and emits frames. A production implementation needs a real AR background shader/texture pipeline and correct GL resource ownership.

Draft review can open enrollment for the selected point. The current enrollment uses a generated reference key, a fixed 0.2 m physical width, the selected point coordinates, and zero heading. Those are placeholders, not verified physical registration. The screen does not validate that the selected point is a suitable marker location, nor does it load registrations into visitor positioning.

Still pending: real camera-background rendering and GL lifecycle; verified enrollment metadata and image-quality checks; storage bucket/policies; loading registrations into visitor positioning; actual timestamp conversion; visitor navigation/recording integration; tracking recovery; calibrated stride; re-registration; membership UI; integrated builds, Kotlin/SQL tests and physical-device validation.

No main merge, workflow change, or live SQL execution is included.
