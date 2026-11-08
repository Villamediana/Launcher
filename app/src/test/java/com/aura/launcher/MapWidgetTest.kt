package com.aura.launcher

import com.aura.launcher.ui.home.distanceLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The one line under the map has to read right at every distance. */
class MapWidgetTest {

    private val br = Locale.forLanguageTag("pt-BR")

    @Test
    fun `short distances are metres, rounded to tens`() {
        assertEquals("450 m", distanceLabel(452f, br))
        assertEquals("0 m", distanceLabel(4f, br))
    }

    @Test
    fun `middle distances keep one decimal, in the phone's separator`() {
        assertEquals("3,2 km", distanceLabel(3_240f, br))
        assertEquals("3.2 km", distanceLabel(3_240f, Locale.US))
    }

    @Test
    fun `long distances are whole kilometres`() {
        assertEquals("18 km", distanceLabel(18_400f, br))
    }
}
