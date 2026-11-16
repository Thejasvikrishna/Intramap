package com.intramap.ui.vision

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.ar.ReferenceEnrollmentStatus
import com.intramap.ar.ReferenceImageCandidate
import com.intramap.ar.ReferenceImagePolicy
import com.intramap.data.supabase.ReferenceImageSource
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ReferenceImageEnrollmentScreen(
    client: SupabaseClient,
    buildingId: String,
    versionId: String,
    referenceKey: String,
    floorId: String,
    physicalWidthM: Float,
    mapX: Double,
    mapY: Double,
    mapHeadingDeg: Double,
    onDone: () -> Unit
) {
    val source = remember(client) { ReferenceImageSource(client) }
    val scope = rememberCoroutineScope()

    var image by remember(buildingId, versionId, referenceKey) { mutableStateOf<Bitmap?>(null) }
    var widthText by rememberSaveable(buildingId, versionId, referenceKey) { mutableStateOf("") }
    var headingText by rememberSaveable(buildingId, versionId, referenceKey) { mutableStateOf("") }
    var xText by rememberSaveable(buildingId, versionId, referenceKey, mapX) { mutableStateOf(mapX.toString()) }
    var yText by rememberSaveable(buildingId, versionId, referenceKey, mapY) { mutableStateOf(mapY.toString()) }
    var verified by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var existingKey by remember { mutableStateOf(false) }
    var existingHashes by remember { mutableStateOf(emptySet<String>()) }
    var loaded by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("Checking for an existing reference registration…")
    }

    val width = widthText.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
    val heading = headingText.toDoubleOrNull()?.takeIf {
        it.isFinite() && it >= 0.0 && it < 360.0
    }
    val x = xText.toDoubleOrNull()?.takeIf { it.isFinite() }
    val y = yText.toDoubleOrNull()?.takeIf { it.isFinite() }
    val validIds = listOf(buildingId, versionId, referenceKey, floorId).all { it.isNotBlank() }

    val candidate = image?.let { bitmap ->
        if (validIds && width != null && heading != null && x != null && y != null) {
            ReferenceImageCandidate(
                referenceKey = referenceKey,
                floorId = floorId,
                imageBytes = ReferenceImagePolicy.pngBytes(bitmap),
                physicalWidthM = width,
                mapX = x,
                mapY = y,
                mapHeadingDeg = heading
            )
        } else null
    }

    val validation = candidate?.let {
        ReferenceImagePolicy.validate(it, existingHashes)
    }

    LaunchedEffect(buildingId, versionId, referenceKey) {
        loaded = false
        runCatching {
            source.existingRegistrations(buildingId, versionId)
        }.onSuccess { registrations ->
            existingKey = registrations.any { it.reference_key == referenceKey }
            existingHashes = registrations.map { it.content_sha256 }.toSet()
            status = if (existingKey) {
                "An existing reference uses this key. You can replace its image and measured metadata."
            } else {
                "Choose an image and enter measured marker metadata. No width or heading is assumed."
            }
        }.onFailure {
            status = "Could not check existing registrations. Check sign-in, connectivity and permissions."
        }
        loaded = true
    }

    BackHandler(enabled = busy) {}

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (existingKey) "Replace reference image" else "Register reference image",
            style = MaterialTheme.typography.titleLarge
        )
        Text(status)
        Text("Floor: $floorId · reference: $referenceKey")

        ReferenceImagePicker(enabled = !busy) { selected ->
            image = selected
            verified = false
        }

        OutlinedTextField(
            widthText,
            { widthText = it; verified = false },
            label = { Text("Measured printed image width (metres)") },
            enabled = !busy,
            singleLine = true,
            isError = widthText.isNotBlank() && width == null
        )

        OutlinedTextField(
            headingText,
            { headingText = it; verified = false },
            label = { Text("Reference map heading (0 to less than 360 degrees)") },
            enabled = !busy,
            singleLine = true,
            isError = headingText.isNotBlank() && heading == null
        )

        OutlinedTextField(
            xText,
            { xText = it; verified = false },
            label = { Text("Reference centre map X (metres)") },
            enabled = !busy,
            singleLine = true,
            isError = x == null
        )

        OutlinedTextField(
            yText,
            { yText = it; verified = false },
            label = { Text("Reference centre map Y (metres)") },
            enabled = !busy,
            singleLine = true,
            isError = y == null
        )

        Text(
            "Use the mapping coordinate system and reference orientation convention. " +
                    "The selected node coordinates are only a starting point, not a measured marker centre."
        )

        if (validation != null && validation != ReferenceEnrollmentStatus.READY) {
            Text(
                "Image issue: $validation",
                color = MaterialTheme.colorScheme.error
            )
        }

        Row {
            Checkbox(
                checked = verified,
                onCheckedChange = { verified = it },
                enabled = !busy
            )
            Text(
                "I measured the printed width and verified the reference centre and heading on this floor.",
                Modifier.padding(top = 12.dp)
            )
        }

        Text(
            "Client validation does not establish ARCore recognition quality or detect existing server duplicates."
        )

        Button(
            enabled = loaded && !busy && verified &&
                    validation == ReferenceEnrollmentStatus.READY,
            onClick = {
                val value = candidate ?: return@Button
                busy = true
                status = if (existingKey) "Replacing reference image…" else "Uploading reference image…"
                scope.launch {
                    try {
                        if (existingKey) {
                            source.replace(value, buildingId, versionId)
                        } else {
                            source.register(value, buildingId, versionId)
                        }
                        status = if (existingKey) {
                            "Reference replaced for this draft version. Visitors must rescan it."
                        } else {
                            "Reference registered for this draft version."
                        }
                        onDone()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        status = "Registration failed or is unconfirmed. Check server state before retrying; storage policies or duplicate content may reject registration."
                    } finally {
                        busy = false
                    }
                }
            }
        ) {
            Text(
                when {
                    busy -> "Saving…"
                    existingKey -> "Replace measured reference"
                    else -> "Register measured reference"
                }
            )
        }

        OutlinedButton(enabled = !busy, onClick = onDone) {
            Text("Back to draft")
        }
    }
}
