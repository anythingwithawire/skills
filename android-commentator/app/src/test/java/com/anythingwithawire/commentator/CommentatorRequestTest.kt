package com.anythingwithawire.commentator

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fasterxml.jackson.databind.JsonNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentatorRequestTest {

    private fun body(params: MessageCreateParams): JsonNode =
        jsonMapper().readTree(jsonMapper().writeValueAsString(params._body()))

    private fun commentator(ephemeral: Boolean, basis: Basis = Basis.ALL_CONSENT) = Commentator(
        "test-key", "claude-opus-5-5", "Family law property settlement", "Western Australia, Australia", basis, ephemeral,
    )

    @Test
    fun requestCarriesContextSchemaEffortAndFallback() {
        val params = commentator(ephemeral = false).prepare("[10:00:00] hello")
        val body = body(params)

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

    @Test
    fun fullModeKeepsEarlierChunks() {
        val c = commentator(ephemeral = false)
        c.prepare("[10:00:00] first chunk")
        val messages = body(c.prepare("[10:00:30] second chunk"))["messages"]
        assertEquals(2, messages.size())
        assertTrue(messages.toString().contains("first chunk"))
    }

    @Test
    fun ephemeralModeSendsOnlyTheNewChunk() {
        val c = commentator(ephemeral = true)
        c.prepare("[10:00:00] first chunk")
        val second = body(c.prepare("[10:00:30] second chunk"))
        val messages = second["messages"]
        assertEquals(1, messages.size())
        assertFalse(messages.toString().contains("first chunk"))
        assertTrue(messages.toString().contains("second chunk"))
        assertTrue(messages.toString().contains("Your comments so far this session"))
        assertTrue(second["system"][0]["text"].asText().contains("Earlier speech is discarded"))
    }

    @Test
    fun courtBasisFramesClaudeAsAQuietHelper() {
        val system = body(commentator(ephemeral = true, basis = Basis.COURT_LEAVE).prepare("x"))["system"][0]["text"].asText()
        assertTrue(system.contains("McKenzie friend"))
        assertTrue(system.contains("Never suggest interrupting"))
    }
}
