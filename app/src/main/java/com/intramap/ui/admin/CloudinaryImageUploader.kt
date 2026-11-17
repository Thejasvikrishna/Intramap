package com.intramap.ui.admin

import android.content.Context
import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable

@Serializable
private data class CloudinaryUploadResponse(
    @kotlinx.serialization.SerialName("secure_url") val secureUrl: String? = null,
    @kotlinx.serialization.SerialName("error") val error: CloudinaryError? = null
)

@Serializable
private data class CloudinaryError(val message: String? = null)

/** Uploads visitor-facing place reference photos. No camera-route frames are uploaded. */
class CloudinaryImageUploader(private val context: Context) {
    private val client = HttpClient()

    suspend fun uploadPlacePhoto(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not read the selected photo")
        require(bytes.size <= 5 * 1024 * 1024) { "Choose an image smaller than 5 MB" }
        val response = client.submitFormWithBinaryData(
            url = "https://api.cloudinary.com/v1_1/dq1uoawcq/image/upload",
            formData = formData {
                append("upload_preset","place_preview_photos")
                append("folder","intramap/images")
                append("file",bytes,Headers.build {
                    append(HttpHeaders.ContentType,"image/*")
                    append(HttpHeaders.ContentDisposition,"filename=\"place.jpg\"")
                })
            }
        ).body<CloudinaryUploadResponse>()
        response.error?.message?.let { throw IllegalStateException(it) }
        return requireNotNull(response.secureUrl) { "Cloudinary did not return an image URL" }
    }
}
