package com.anythingwithawire.bsdetector

import com.anthropic.core.jsonMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorTest {

    @Test
    fun thresholdSpansTenToNinety() {
        assertEquals(90, DetectorSettings.threshold(0))
        assertEquals(50, DetectorSettings.threshold(50))
        assertEquals(10, DetectorSettings.threshold(100))
    }

    @Test
    fun requestCarriesOnlyTheUtteranceAndAScoreSchema() {
        val d = Detector("test-key", "claude-opus-5-5")
        d.prepare("first utterance")
        val params = d.prepare("ninety percent of statistics are made up on the spot")
        val body = jsonMapper().readTree(jsonMapper().writeValueAsString(params._body()))

        assertEquals(1, body["messages"].size())
        assertTrue(body["messages"].toString().contains("ninety percent"))
        assertEquals("integer", body["output_config"]["format"]["schema"]["properties"]["score"]["type"].asText())
        assertEquals("default", body["fallbacks"].asText())
        assertEquals(listOf("server-side-fallback-2026-07-01"), params._headers().values("anthropic-beta"))
    }
}
