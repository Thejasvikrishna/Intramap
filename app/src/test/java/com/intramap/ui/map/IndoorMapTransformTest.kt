package com.intramap.ui.map

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class IndoorMapTransformTest {
    @Test fun `map coordinates survive projection and inverse projection`() {
        val view = IndoorMapTransform(Offset(200f,150f),Offset(35f,-12f),Offset(8f,4f),15f)
        val point = Offset(11.25f,6.5f)
        val restored = view.unproject(view.project(point))
        assertEquals(point.x,restored.x,0.0001f)
        assertEquals(point.y,restored.y,0.0001f)
    }
    @Test fun `camera center projects to the panned viewport origin`() {
        val view = IndoorMapTransform(Offset(120f,80f),Offset(-20f,5f),Offset(7f,9f),3f)
        assertEquals(Offset(100f,85f),view.project(Offset(7f,9f)))
    }
    @Test(expected = IllegalArgumentException::class)
    fun `zero scale is rejected`() { IndoorMapTransform(Offset.Zero,Offset.Zero,Offset.Zero,0f) }
}
