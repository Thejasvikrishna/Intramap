package com.intramap.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Frame
import com.google.ar.core.Session
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the AR camera background and drives Session.update().
 * It does not draw map content, enroll images, or update visitor position.
 */
class ArCameraRenderer(
    private val sessionProvider: () -> Session?,
    private val onFrame: (Frame) -> Unit
) : GLSurfaceView.Renderer {
    private var width = 0
    private var height = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f,0f,0f,1f)
    }

    override fun onSurfaceChanged(gl: GL10?,width: Int,height: Int) {
        GLES20.glViewport(0,0,width,height)
        this.width = width; this.height = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = sessionProvider() ?: return
        val frame = try { session.update() } catch (_: Exception) { return }
        if (width > 0 && height > 0) {
            session.setDisplayGeometry(session.displayRotation,width,height)
        }
        onFrame(frame)
    }
}
