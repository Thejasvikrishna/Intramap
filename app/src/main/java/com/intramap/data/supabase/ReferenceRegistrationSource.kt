package com.intramap.data.supabase

import com.intramap.domain.localization.RegisteredReferencePose
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable

@Serializable
data class ReferenceImageRow(
    val building_id: String, val version_id: String, val reference_key: String, val floor_id: String,
    val physical_width_m: Double, val map_x: Double, val map_y: Double, val map_heading_deg: Double,
    val storage_path: String, val content_sha256: String
)

/** Reads persisted registrations; it does not verify that the physical marker is correctly placed. */
class ReferenceRegistrationSource(private val client: SupabaseClient) {
    suspend fun registrations(buildingId: String,versionId: String): List<RegisteredReferencePose> =
        client.postgrest["reference_images"].select {
            filter { eq("building_id",buildingId); eq("version_id",versionId) }
        }.decodeList<ReferenceImageRow>().map {
            RegisteredReferencePose(it.building_id,it.version_id,it.reference_key,it.floor_id,
                com.intramap.domain.localization.PlanarPose(it.map_x,it.map_y,it.map_heading_deg))
        }
}
