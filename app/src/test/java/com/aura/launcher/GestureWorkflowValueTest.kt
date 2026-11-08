package com.aura.launcher

import com.aura.launcher.gestures.appendCarried
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureWorkflowValueTest {
    @Test
    fun `results from producing steps accumulate in order`() {
        var carried = ""

        carried = appendCarried(carried, "Today is September 14")
        carried = appendCarried(carried, null)
        carried = appendCarried(carried, "Calendar screen: Dentist at 3 PM")

        assertEquals(
            "Today is September 14\n\n---\n\nCalendar screen: Dentist at 3 PM",
            carried,
        )
    }

    @Test
    fun `blank steps pass accumulated results through unchanged`() {
        assertEquals("first result", appendCarried("first result", "  "))
    }
}
