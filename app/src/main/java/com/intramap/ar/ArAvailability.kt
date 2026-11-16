package com.intramap.ar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk

sealed class ArAvailability {
    data object Supported : ArAvailability()
    data class InstallNeeded(val userRequestedInstall: Boolean) : ArAvailability()
    data class UpdateNeeded(val userRequestedInstall: Boolean) : ArAvailability()
    data object Unsupported : ArAvailability()
    data class Error(val message: String) : ArAvailability()
}

object ArAvailabilityChecker {
    fun check(activity: Activity,userRequestedInstall: Boolean = false): ArAvailability = try {
        when (ArCoreApk.getInstance().checkAvailability(activity)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArAvailability.Supported
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArAvailability.InstallNeeded(userRequestedInstall)
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArAvailability.UpdateNeeded(userRequestedInstall)
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArAvailability.Unsupported
            else -> ArAvailability.Error("AR availability is not currently known.")
        }
    } catch (e: Exception) { ArAvailability.Error(e.message ?: "Could not check AR availability.") }

    fun hasCameraPermission(context: Context) =
        ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
