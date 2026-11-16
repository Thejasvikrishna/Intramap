package com.intramap.ui.vision

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.ar.ReferenceEnrollmentStatus
import com.intramap.ar.ReferenceImageCandidate
import com.intramap.ar.ReferenceImagePolicy
import com.intramap.data.supabase.ReferenceImageSource
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.launch

@Composable
fun ReferenceImageEnrollmentScreen(
    client: SupabaseClient, buildingId: String, versionId: String,
    referenceKey: String, floorId: String,
    physicalWidthM: Float, mapX: Double, mapY: Double, mapHeadingDeg: Double,
    onDone: () -> Unit
) {
    val source = remember(client) { ReferenceImageSource(client) }
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose a clear, uniquely patterned reference image.") }
    val candidate = image?.let { ReferenceImageCandidate(referenceKey,floorId,it,physicalWidthM,mapX,mapY,mapHeadingDeg) }
    val validation = candidate?.let { ReferenceImagePolicy.validate(it,emptySet()) } ?: ReferenceEnrollmentStatus.LOW_CONTRAST
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Register reference image",style = MaterialTheme.typography.titleLarge)
        Text(status)
        ReferenceImagePicker(enabled = !busy) { selected -> image = selected; status = "Image selected. Review the registration details." }
        if (validation != ReferenceEnrollmentStatus.READY) Text("Image issue: $validation",color = MaterialTheme.colorScheme.error)
        Text("Physical width: ${physicalWidthM} m · map: ($mapX, $mapY) · heading: $mapHeadingDeg°")
        Button(enabled = !busy && validation == ReferenceEnrollmentStatus.READY,onClick = {
            val value = candidate ?: return@Button
            busy = true; status = "Uploading reference image…"
            scope.launch {
                try { source.register(value,buildingId,versionId); status = "Reference registered for this draft version."; onDone() }
                catch (_: Exception) { status = "Registration failed. Check storage bucket, policies and duplicate content." }
                finally { busy = false }
            }
        }) { Text("Register") }
    }
}
