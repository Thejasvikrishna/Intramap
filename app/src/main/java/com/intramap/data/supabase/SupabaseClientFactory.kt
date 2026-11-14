package com.intramap.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseClientFactory {
    val client: SupabaseClient? by lazy {
        if (!SupabaseConfig.isConfigured) null
        else createSupabaseClient(
            supabaseUrl = SupabaseConfig.url,
            supabaseKey = SupabaseConfig.publishableKey
        ) {
            install(Postgrest)
            install(Auth)
        }
    }
}
