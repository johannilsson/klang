package se.klang.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClapPeakTrackerTest {
    private fun run(levels: List<Double>): List<Double> {
        val t = ClapPeakTracker()
        return levels.mapNotNull { t.update(it) }
    }

    @Test
    fun `reports the peak of each clap when its level falls away`() {
        // quiet, clap (peak -5), decay, quiet, second clap (peak -8), decay
        val claps = run(listOf(-60.0, -60.0, -10.0, -5.0, -25.0, -45.0, -60.0, -8.0, -35.0, -50.0))
        assertEquals(listOf(-5.0, -8.0), claps)
    }

    @Test
    fun `background noise below the start level is ignored`() {
        assertEquals(emptyList(), run(List(100) { -45.0 }))
        assertEquals(emptyList(), run(List(100) { -31.0 }))
    }

    @Test
    fun `continuous loud noise cannot hold a clap open`() {
        val claps = run(List(25) { -20.0 })
        assertEquals(listOf(-20.0, -20.0), claps) // closed after 10 windows each; 5 left open
    }

    @Test
    fun `a clap still open at the end is not reported`() {
        val t = ClapPeakTracker()
        assertNull(t.update(-10.0))
        assertNull(t.update(-20.0))
    }
}
