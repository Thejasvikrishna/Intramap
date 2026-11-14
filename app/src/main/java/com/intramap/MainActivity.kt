package com.intramap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.intramap.data.repository.MapRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = MapRepository(applicationContext)
        setContent { MaterialTheme { Surface { IntraMapApp(repo) } } }
    }
}