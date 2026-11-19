package com.ldartools.ktus.test

import com.ldartools.ktus.test.CutGapProbe.Event.Activity
import com.ldartools.ktus.test.CutGapProbe.Event.Confirmed
import com.ldartools.ktus.test.CutGapProbe.Event.Progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The probe finds a cut from the callbacks alone, whatever position the retry reports. */
class CutGapProbeTest {
    // One PATCH attempt: start, progress events (each preceded by its own activity), outcome.
    private fun attempt(start: Long, vararg progressAt: Pair<Long, Long>, end: Long) =
        listOf(Activity(start)) + progressAt.flatMap { (at, sent) -> listOf(Activity(at), Progress(at, sent)) } + Activity(end)

    private fun head(at: Long) = listOf(Activity(at), Activity(at + 5))

    @Test
    fun aRetryThatStartsLower_isFound() {
        val events = attempt(0, 10L to 100, 20L to 200, end = 2_520) + head(2_530) +
            attempt(2_540, 2_550L to 50, end = 2_600) + Confirmed(2_600)
        assertEquals(2_500, CutGapProbe.silenceBeforeFirstCut(events))
    }

    @Test
    fun aRetryThatStartsAtTheSamePosition_isFound() {
        // Copilot's case: the cut attempt stalled after its first progress event, and the retry reports the same position.
        val events = attempt(0, 10L to 100, end = 2_510) + head(2_520) +
            attempt(2_530, 2_540L to 100, end = 2_600) + Confirmed(2_600)
        assertEquals(2_500, CutGapProbe.silenceBeforeFirstCut(events))
    }

    @Test
    fun aRetryThatStartsHigher_afterTheServerAppliedTheAbandonedBody_isFound() {
        // The recovery HEAD shows more data and fires its own onConfirmed; the cut attempt is still the cut.
        val events = attempt(0, 10L to 100, end = 2_510) + head(2_520) + Confirmed(2_525) +
            attempt(2_530, 2_540L to 300, end = 2_600) + Confirmed(2_600)
        assertEquals(2_500, CutGapProbe.silenceBeforeFirstCut(events))
    }

    @Test
    fun successfulAttemptsAreSkipped_andTheFirstCutIsMeasured() {
        val events = attempt(0, 10L to 100, end = 50) + Confirmed(50) +
            attempt(60, 70L to 200, end = 3_070) + head(3_080) +
            attempt(3_090, 3_100L to 200, end = 3_150) + Confirmed(3_150)
        assertEquals(3_000, CutGapProbe.silenceBeforeFirstCut(events))
    }

    @Test
    fun noCut_isNull() {
        val events = attempt(0, 10L to 100, end = 50) + Confirmed(50) + attempt(60, 70L to 200, end = 90) + Confirmed(90)
        assertNull(CutGapProbe.silenceBeforeFirstCut(events))
    }
}
