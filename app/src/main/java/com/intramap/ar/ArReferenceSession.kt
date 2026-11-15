package com.intramap.ar

import android.content.Context
import android.os.SystemClock
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.intramap.domain.localization.ReferenceAlignmentResult
import com.intramap.domain.localization.ReferencePoseAligner
import com.intramap.domain.localization.RegisteredReferencePose
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class ArSessionState { IDLE, STARTING, RUNNING, PAUSED, FAILED, CLOSED }

data class ArSessionEvent(
    val state: ArSessionState, val message: String,
    val trackingFailure: TrackingFailureReason? = null
)

/**
 * Owns one ARCore Session. The owner must render frames and release GL resources; this class does not.
 * A session token changes whenever the underlying AR world is recreated, preventing stale transforms.
 */
class ArReferenceSession(
    context: Context,
    private val buildingId: String,
    private val versionId: String,
    private val registrations: List<RegisteredReferencePose>,
    private val onObservation: (ReferenceAlignmentResult) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private var session: Session? = null
    private var adapter: ArCoreReferenceAdapter? = null
    private var aligner: ReferencePoseAligner? = null
    private val _state = MutableStateFlow(ArSessionEvent(ArSessionState.IDLE,"AR not started."))
    val state: StateFlow<ArSessionEvent> = _state
    private val _frames = MutableSharedFlow<Frame>(extraBufferCapacity = 1,onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames: SharedFlow<Frame> = _frames

    init {
        require(buildingId.isNotBlank() && versionId.isNotBlank())
        require(registrations.all { it.buildingId == buildingId && it.versionId == versionId })
    }

    fun start(): Boolean {
        if (closed.get()) return false
        synchronized(lock) {
            if (_state.value.state == ArSessionState.RUNNING) return true
            _state.value = ArSessionEvent(ArSessionState.STARTING,"Starting AR…")
            try {
                val created = Session(appContext)
                val config = Config(created)
                config.focusMode = Config.FocusMode.AUTO
                created.configure(config)
                created.resume()
                session = created
                val token = created.sessionId ?: "ar-session-${System.nanoTime()}"
                adapter = ArCoreReferenceAdapter(buildingId,versionId,token)
                aligner = ReferencePoseAligner(
                    Building(buildingId,"","",emptyList(),emptyList(),emptyList()),versionId,token,registrations
                )
                _state.value = ArSessionEvent(ArSessionState.RUNNING,"Point the camera at a registered reference image.")
                return true
            } catch (e: UnavailableArcoreNotInstalledException) {
                fail("ARCore is not installed.")
            } catch (e: UnavailableApkTooOldException) {
                fail("ARCore must be updated.")
            } catch (e: UnavailableDeviceNotCompatibleException) {
                fail("This device does not support ARCore.")
            } catch (e: UnavailableSdkTooOldException) {
                fail("The ARCore SDK is too old.")
            } catch (e: CameraNotAvailableException) {
                fail("Camera unavailable. Close other camera apps and retry.")
            } catch (e: Exception) {
                fail(e.message ?: "Could not start AR.")
            }
            return false
        }
    }

    fun pause() {
        synchronized(lock) {
            val current = session ?: return
            try { current.pause(); _state.value = ArSessionEvent(ArSessionState.PAUSED,"AR paused.") }
            catch (_: SessionPausedException) { _state.value = ArSessionEvent(ArSessionState.PAUSED,"AR already paused.") }
        }
    }

    fun resume() {
        synchronized(lock) {
            val current = session ?: return
            try { current.resume(); _state.value = ArSessionEvent(ArSessionState.RUNNING,"AR resumed.") }
            catch (e: CameraNotAvailableException) { fail("Camera unavailable on resume.") }
        }
    }

    /** Call from the GL/render thread after session.update(); emits the frame once. */
    fun onFrameReady(frame: Frame) {
        if (closed.get()) return
        _frames.tryEmit(frame)
        val currentAdapter = adapter ?: return
        val currentAligner = aligner ?: return
        val now = SystemClock.elapsedRealtime()
        for (image in frame.getUpdatedTrackables(com.google.ar.core.AugmentedImage::class.java)) {
            val reference = registrations.firstOrNull { it.referenceKey == image.name } ?: continue
            val observation = currentAdapter.observation(frame,image,reference,now,now) ?: continue
            onObservation(currentAligner.observe(observation,now))
        }
    }

    fun trackingFailure(): TrackingFailureReason? = session?.let {
        try { it.camera.trackingFailureReason } catch (_: Exception) { null }
    }

    private fun fail(message: String) {
        closeQuietly(); _state.value = ArSessionEvent(ArSessionState.FAILED,message)
    }

    private fun closeQuietly() {
        synchronized(lock) {
            session?.let { try { it.close() } catch (_: Exception) {} }
            session = null; adapter = null; aligner = null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        closeQuietly(); _state.value = ArSessionEvent(ArSessionState.CLOSED,"AR closed.")
    }
}
