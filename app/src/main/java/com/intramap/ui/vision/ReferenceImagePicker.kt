package com.intramap.ui.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun ReferenceImagePicker(
    enabled: Boolean,onImageSelected: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    var selectedName by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val stream = context.contentResolver.openInputStream(uri)
        val bitmap = stream?.use { BitmapFactory.decodeStream(it) }
        if (bitmap != null) { selectedName = uri.lastPathSegment; onImageSelected(bitmap) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = enabled,onClick = { launcher.launch("image/*") }) { Text("Choose reference image") }
        selectedName?.let { Text("Selected: $it") }
    }
}
