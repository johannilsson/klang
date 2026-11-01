package se.klang.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepUnitsTest {
    private val nb = "\u00A0"

    @Test
    fun joinsANumberAndItsUnit() {
        assertEquals("sound takes about 0.7${nb}s to die away at 500${nb}Hz, 45${nb}m³ and 22${nb}%", "sound takes about 0.7 s to die away at 500 Hz, 45 m³ and 22 %".keepUnits())
        assertEquals("0.13${nb}s above, 60${nb}dB", "0.13 s above, 60 dB".keepUnits())
    }

    @Test
    fun leavesOtherTextAlone() {
        val text = "Seat A: 1 of 5 claps usable. It sounds short. Hz is a unit; 5 seconds; 3 sofas."
        assertEquals(text, text.keepUnits())
        assertEquals("2 samples", "2 samples".keepUnits())
    }
}
