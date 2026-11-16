package com.intramap.data.supabase

import android.graphics.Bitmap
import com.intramap.ar.ReferenceImageCandidate
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

@Serializable
data class ReferenceImageRecord(
    val building_id: String,
    val version_id: String,
    val reference_key: String,
    val floor_id: String,
    val physical_width_m: Double,
    val map_x: Double,
    val map_y: Double,
    val map_heading_deg: Double,
    val storage_path: String,
    val content_sha256: String
)

class ReferenceImageSource(private val client: SupabaseClient) {

    suspend fun existingRegistrations(
        buildingId: String,
        versionId: String
    ): List<ReferenceImageRecord> =
        client.postgrest["reference_images"].select {
            filter {
                eq("building_id", buildingId)
                eq("version_id", versionId)
            }
        }.decodeList<ReferenceImageRecord>()

    suspend fun register(
        candidate: ReferenceImageCandidate,
        buildingId: String,
        versionId: String
    ) {
        val existing = existingRegistrations(buildingId, versionId)
        require(existing.none { it.reference_key == candidate.referenceKey }) {
            "Reference key already exists. Use replacement instead."
        }

        upload(candidate, buildingId, versionId)
    }

    suspend fun replace(
        candidate: ReferenceImageCandidate,
        buildingId: String,
        versionId: String
    ) {
        val existing = existingRegistrations(buildingId, versionId)
        require(existing.any { it.reference_key == candidate.referenceKey }) {
            "Reference key does not exist. Use registration instead."
        }

        upload(candidate, buildingId, versionId)
    }

    private suspend fun upload(
        candidate: ReferenceImageCandidate,
        buildingId: String,
        versionId: String
    ) {
        val bytes = pngBytes(candidate.image)
        val hash = sha256(bytes)
        val storagePath = "$buildingId/$versionId/${candidate.referenceKey}.png"

        client.storage.from("reference_images").upload(
            path = storagePath,
            data = bytes
        ) {
            upsert = true
        }

        val record = ReferenceImageRecord(
            building_id = buildingId,
            version_id = versionId,
            reference_key = candidate.referenceKey,
            floor_id = candidate.floorId,
            physical_width_m = candidate.physicalWidthM.toDouble(),
            map_x = candidate.mapX,
            map_y = candidate.mapY,
            map_heading_deg = candidate.mapHeadingDeg,
            storage_path = storagePath,
            content_sha256 = hash
        )

        client.postgrest["reference_images"].upsert(record) {
            onConflict = "building_id,version_id,reference_key"
        }
    }

    private fun pngBytes(image: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
