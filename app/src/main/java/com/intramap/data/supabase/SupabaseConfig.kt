package com.intramap.data.supabase

import com.intramap.BuildConfig

object SupabaseConfig {
    val url: String = BuildConfig.SUPABASE_URL
    val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    val isConfigured: Boolean get() = url.startsWith("https://") && publishableKey.isNotBlank()
}
