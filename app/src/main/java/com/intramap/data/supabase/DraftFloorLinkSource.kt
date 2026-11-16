package com.intramap.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DraftFloorLinkSource(private val client: SupabaseClient) {
    suspend fun link(version: String,fingerprint: String,from: String,to: String,kind: String,verified: Boolean) {
        require(verified && from != to && kind in setOf("STAIRS","ELEVATOR"))
        save(version,fingerprint,buildJsonObject {
            put("action","link"); put("from",from); put("to",to); put("kind",kind); put("confirmed",verified)
        })
    }
    suspend fun remove(version: String,fingerprint: String,edge: String,confirmed: Boolean) {
        require(confirmed && edge.isNotBlank())
        save(version,fingerprint,buildJsonObject { put("action","remove"); put("edge",edge); put("confirmed",confirmed) })
    }
    private suspend fun save(version: String,fingerprint: String,change: JsonObject) {
        client.postgrest.rpc("save_draft_floor_link",buildJsonObject {
            put("v",version); put("expected_fingerprint",fingerprint); put("change",change)
        })
    }
}
