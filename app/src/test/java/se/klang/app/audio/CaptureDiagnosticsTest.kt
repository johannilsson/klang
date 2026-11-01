package se.klang.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.klang.app.TestFixtures
import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.DecayAnalyzer
import se.klang.dsp.PcmSignal
import se.klang.dsp.QualityState
import se.klang.dsp.ReasonCode
import se.klang.sessions.AnalysisService
import se.klang.sessions.CaptureEvent

/** Route changes, silencing and the mapping of capture events to analysis discontinuities. */
class CaptureDiagnosticsTest {

    @Test
    fun firstRouteIsTheInitialOneAndOnlyLaterDifferencesAreChanges() {
        val routes = RouteTracker()
        assertNull("first callback reports the initial route", routes.onRouted(7, "BUILTIN_MIC a", 0))
        assertNull("same device again", routes.onRouted(7, "BUILTIN_MIC a", 100))
        assertEquals(CaptureEvent("ROUTE_CHANGED", 200, "WIRED_HEADSET b"), routes.onRouted(9, "WIRED_HEADSET b", 200))
        assertEquals(CaptureEvent("ROUTE_CHANGED", 300, "no input device"), routes.onRouted(null, "", 300))
    }

    @Test
    fun routeReadAtStartIsTheInitialRouteUnlessACallbackCameFirst() {
        val started = RouteTracker().apply { start(7) }
        assertNull(started.onRouted(7, "BUILTIN_MIC a", 10))
        assertEquals("ROUTE_CHANGED", started.onRouted(8, "USB_DEVICE c", 20)?.type)

        val callbackFirst = RouteTracker()
        assertNull(callbackFirst.onRouted(7, "BUILTIN_MIC a", 0))
        callbackFirst.start(8) // ignored: the initial route is already known
        assertNull(callbackFirst.onRouted(7, "BUILTIN_MIC a", 10))
    }

    @Test
    fun noDeviceBeforeAnyRouteIsAChange() {
        assertEquals("no input device", RouteTracker().onRouted(null, "", 0)?.detail)
    }

    @Test
    fun silencingBecomesAnEvent() {
        assertEquals("SILENCED", CaptureDiagnostics.silenced(true, 480)?.type)
        assertEquals(480L, CaptureDiagnostics.silenced(true, 480)?.atFrame)
        assertNull(CaptureDiagnostics.silenced(false, 480))
    }

    @Test
    fun differentDeviceAtEndIsAChangeOnlyWhenNoCallbackReportedOne() {
        val e = CaptureDiagnostics.routeChangedAtEnd(1, "Mic", 2, "Headset", emptyList(), 4800)
        assertEquals(CaptureEvent("ROUTE_CHANGED", 4800, "Input device changed from Mic to Headset"), e)
        val reported = listOf(CaptureEvent("ROUTE_CHANGED", 10, "Headset"))
        assertNull(CaptureDiagnostics.routeChangedAtEnd(1, "Mic", 2, "Headset", reported, 4800))
        assertNull(CaptureDiagnostics.routeChangedAtEnd(1, "Mic", 1, "Mic", emptyList(), 4800))
        assertNull("route unknown at one end", CaptureDiagnostics.routeChangedAtEnd(null, null, 2, "Headset", emptyList(), 4800))
    }

    @Test
    fun timelineBreakingEventsBecomeDiscontinuities() {
        val events = listOf(
            CaptureEvent("OVERRUN", 10), CaptureEvent("SHORT_READ", 20), CaptureEvent("ROUTE_CHANGED", 30),
            CaptureEvent("SILENCED", 40), CaptureEvent("INTERRUPTED", 50),
            CaptureEvent("READ_ERROR", 60), CaptureEvent("CANCELLED", 70), CaptureEvent("SOMETHING_ELSE", 80),
            CaptureEvent("OVERRUN", -5), CaptureEvent("OVERRUN", Long.MAX_VALUE),
        )
        assertEquals(listOf(10, 20, 30, 40, 50, 0, Int.MAX_VALUE), CaptureDiagnostics.discontinuityFrames(events))
        // The test capture uses the same event types as the analysis of stored attempts.
        assertEquals(setOf("OVERRUN", "SHORT_READ", "ROUTE_CHANGED", "INTERRUPTED", "SILENCED"), AnalysisService.DISCONTINUITY_EVENTS)
    }

    @Test
    fun routeChangeOrShortReadMakesTheAttemptInvalid() {
        val signal = clap()
        val analyzer = DecayAnalyzer(AnalysisConfiguration())
        val clean = analyzer.analyze(PcmSignal(signal, RATE, CaptureDiagnostics.discontinuityFrames(emptyList())))
        assertTrue(clean.reasons.none { it.code == ReasonCode.CAPTURE_DISCONTINUITY })

        for (event in listOf(CaptureEvent("ROUTE_CHANGED", 96_000, "WIRED_HEADSET"), CaptureEvent("SHORT_READ", 72_000, "100 of 960 frames"))) {
            val r = analyzer.analyze(PcmSignal(signal, RATE, CaptureDiagnostics.discontinuityFrames(listOf(event))))
            val reason = r.reasons.single { it.code == ReasonCode.CAPTURE_DISCONTINUITY }
            assertEquals(QualityState.INVALID, reason.severity)
            assertEquals(QualityState.INVALID, r.state)
        }
    }

    private companion object {
        const val RATE = TestFixtures.RATE
        fun clap() = TestFixtures.clap()
    }
}
