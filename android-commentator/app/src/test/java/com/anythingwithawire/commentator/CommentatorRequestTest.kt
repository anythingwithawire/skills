package com.anythingwithawire.commentator

import com.anthropic.core.jsonMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentatorRequestTest {

    @Test
    fun requestCarriesContextSchemaEffortAndFallback() {
        val params = Commentator("test-key", "claude-opus-5-5", "Family law property settlement", "Western Australia, Australia")
            .buildParams()
        val body = jsonMapper().readTree(jsonMapper().writeValueAsString(params._body()))
        println(body.toPrettyString())

        assertEquals("claude-opus-5-5", body["model"].asText())
        assertEquals("low", body["output_config"]["effort"].asText())
        assertEquals("json_schema", body["output_config"]["format"]["type"].asText())
        val kinds = body["output_config"]["format"]["schema"]["properties"]["comments"]["items"]["properties"]["kind"]["enum"]
        assertEquals(listOf("question", "doubt", "info", "correction"), kinds.map { it.asText() })
        assertEquals("default", body["fallbacks"].asText())
        assertEquals("ephemeral", body["cache_control"]["type"].asText())
        assertTrue(body["system"][0]["text"].asText().contains("Context given by the user: Family law property settlement"))
        assertEquals(listOf("server-side-fallback-2026-07-01"), params._headers().values("anthropic-beta"))
    }
}
