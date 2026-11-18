package com.intramap.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable

@Serializable
data class PlaceWorkflowCapabilities(
    val apiVersion: Int = 0,val submission: Boolean = false,val draftImport: Boolean = false,
    val draftEdit: Boolean = false,val publishedPlaces: Boolean = false
) {
    val supported: Boolean get() = apiVersion == 1 && submission && draftImport && draftEdit && publishedPlaces
}
class PlaceWorkflowSource(private val client: SupabaseClient) {
    suspend fun requireInstalled() {
        val capabilities = client.postgrest.rpc("place_workflow_capabilities").decodeSingle<PlaceWorkflowCapabilities>()
        check(capabilities.supported) { "Install and verify place workflow migrations 0011 through 0014" }
    }
}
