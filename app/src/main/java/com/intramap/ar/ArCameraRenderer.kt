package com.intramap.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Frame
import com.google.ar.core.Session
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the ARCore camera background and drives Session.update().
 * It does not draw map content, enroll images, or update visitor position.
 */
class ArCameraRenderer(
    private val sessionProvider: () -> Session?,
    private val onFrame: (Frame) -> Unit
) : GLSurfaceView.Renderer {
    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )
    }

    private var program = 0
    private var positionLocation = 0
    private var texCoordLocation = 0
    private var textureLocation = 0
    private var quadBuffer: FloatBuffer? = null
    private var cameraTextureId = 0
    private var width = 0
    private var height = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram()
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureLocation = GLES20.glGetUniformLocation(program, "uTexture")
        quadBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(QUAD); position(0) }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        cameraTextureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        this.width = width
        this.height = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = sessionProvider() ?: return
        if (width > 0 && height > 0) {
            session.setDisplayGeometry(session.displayRotation, width, height)
        }
        if (cameraTextureId != 0) {
            session.setCameraTextureName(cameraTextureId)
        }
        val frame = try {
            session.update()
        } catch (_: Exception) {
            return
        }
        drawCameraBackground(frame)
        onFrame(frame)
    }

    private fun drawCameraBackground(frame: Frame) {
        val buffer = quadBuffer ?: return
        if (program == 0 || cameraTextureId == 0) return
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTextureId)
        GLES20.glUniform1i(textureLocation, 0)
        buffer.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 3, GLES20.GL_FLOAT, false, 20, buffer)
        GLES20.glEnableVertexAttribArray(positionLocation)
        buffer.position(3)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 20, buffer)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
    }

    private fun buildProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            throw IllegalStateException("AR camera background shader failed to link.")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            throw IllegalStateException("AR camera background shader failed to compile.")
        }
        return shader
    }

    fun release() {
        if (cameraTextureId != 0) {
            val ids = intArrayOf(cameraTextureId)
            GLES20.glDeleteTextures(1, ids, 0)
            cameraTextureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }
}
