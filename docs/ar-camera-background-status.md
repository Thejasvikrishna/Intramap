# AR camera background status

This patch adds a GL camera-background shader, quad, texture, and render loop to ArCameraRenderer. It replaces the prior renderer that updated the session without drawing camera imagery.

The renderer allocates a GL texture, assigns it to the ARCore session, updates the session, draws a full-screen textured quad, and forwards the resulting frame to ArReferenceSession.onFrameReady. The renderer exposes release() for GL cleanup; the Compose screen stores it in the view tag and calls it on release.

Important limitation: the screen integration uses a reflection-based sessionOrNull() placeholder that currently returns null, so it cannot yet provide the Session to the renderer. The next patch must expose a safe session accessor on ArReferenceSession and remove that placeholder. Until then, the camera background will not render.

Still pending: safe Session accessor, device validation of the camera feed, correct texture transform handling for all display rotations, reference loading into visitor positioning, visitor navigation and tracking integration, storage policies, builds, tests, and physical-device validation.

No main merge, workflow change, or live SQL execution is included.
