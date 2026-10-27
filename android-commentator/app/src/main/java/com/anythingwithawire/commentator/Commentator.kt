package com.anythingwithawire.commentator

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONObject

enum class Kind { QUESTION, DOUBT, INFO, CORRECTION }

data class Comment(val kind: Kind, val quote: String, val text: String)

/** Outcome of one commentary request: either comments, or a reason the model gave none. */
sealed class Outcome {
    data class Comments(val comments: List<Comment>) : Outcome()
    data class Declined(val reason: String) : Outcome()
}

/**
 * Sends transcript chunks to Claude and returns its comments.
 *
 * Ephemeral mode: each request carries only the new chunk plus Claude's own earlier
 * comments. No transcript is kept here once a request is built.
 * Full mode: one growing, append-only conversation (user turn = chunk, assistant
 * turn = comments as JSON), so each request reuses the cached prefix of the last.
 */
class Commentator(
    apiKey: String,
    private val model: String,
    context: String,
    jurisdiction: String,
    basis: Basis,
    private val ephemeral: Boolean,
) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    private val history = mutableListOf<BetaMessageParam>()
    private val earlierComments = mutableListOf<Comment>()
    private val systemPrompt = buildSystemPrompt(context, jurisdiction, basis, ephemeral)

    /** Blocking network call: run off the main thread. */
    fun comment(chunk: String): Outcome {
        val params = prepare(chunk)
        val response = client.beta().messages().create(params)
        if (!ephemeral) history.add(response.toParam())

        val stopReason = response.stopReason().get()
        if (stopReason == BetaStopReason.REFUSAL) {
            val category = response.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse("unspecified")
            return Outcome.Declined("Claude declined to comment on this part (category: $category).")
        }
        if (stopReason == BetaStopReason.MAX_TOKENS) {
            return Outcome.Declined("Response hit the token limit and was cut off.")
        }

        // Text before a fallback block came from a model that declined part-way, so only
        // the text after the last switch point is the answer.
        val content = response.content()
        val answer = content.subList(content.indexOfLast { it.isFallback() } + 1, content.size)
        val text = answer.mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        val comments = parse(text)
        earlierComments.addAll(comments)
        return Outcome.Comments(comments)
    }

    /** Builds the request for a new chunk; in full mode this also appends the chunk to the conversation. */
    internal fun prepare(chunk: String): MessageCreateParams {
        val messages = if (ephemeral) {
            listOf(BetaMessageParam.ofUser(ephemeralTurn(chunk)))
        } else {
            history.add(BetaMessageParam.ofUser(chunk))
            history
        }
        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(systemPrompt).build()))
            .messages(messages)
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.LOW)
                    .format(BetaJsonOutputFormat.builder().schema(SCHEMA).build())
                    .build()
            )
            // Server-side refusal fallback: a declined request is re-run on a fallback model.
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacks(BetaFallbacksParam.ofDefault())
            .build()
    }

    private fun ephemeralTurn(chunk: String): String {
        val earlier = if (earlierComments.isEmpty()) {
            "(none yet)"
        } else {
            earlierComments.joinToString("\n") { "- ${it.kind.name.lowercase()}: \"${it.quote}\": ${it.text}" }
        }
        return "Your comments so far this session:\n$earlier\n\nNew speech:\n$chunk"
    }

    private fun parse(json: String): List<Comment> {
        val array = JSONObject(json).getJSONArray("comments")
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Comment(
                kind = Kind.valueOf(item.getString("kind").uppercase()),
                quote = item.getString("quote"),
                text = item.getString("text"),
            )
        }
    }

    companion object {
        private val SCHEMA: BetaJsonOutputFormat.Schema = BetaJsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .putAdditionalProperty("required", JsonValue.from(listOf("comments")))
            .putAdditionalProperty(
                "properties", JsonValue.from(
                    mapOf(
                        "comments" to mapOf(
                            "type" to "array",
                            "items" to mapOf(
                                "type" to "object",
                                "additionalProperties" to false,
                                "required" to listOf("kind", "quote", "text"),
                                "properties" to mapOf(
                                    "kind" to mapOf("type" to "string", "enum" to listOf("question", "doubt", "info", "correction")),
                                    "quote" to mapOf("type" to "string"),
                                    "text" to mapOf("type" to "string"),
                                ),
                            ),
                        ),
                    )
                )
            )
            .build()

        private fun buildSystemPrompt(context: String, jurisdiction: String, basis: Basis, ephemeral: Boolean): String {
            val contextLine = context.ifBlank { "None given: treat it as a general conversation." }
            val memoryLine = if (ephemeral) {
                "Earlier speech is discarded once you have seen it: each request shows you only the new speech and the comments you have already made, so treat those comments as your notes of the conversation so far."
            } else {
                "Each new chunk arrives as a new turn; earlier chunks stay in the conversation."
            }
            return """
                You are a live commentator listening to a conversation through a phone microphone. You receive the speech-to-text transcript in timestamped chunks as the conversation happens, and your comments appear on the phone screen of the person who started you.

                Context given by the user: $contextLine
                Default jurisdiction and locale: $jurisdiction
                Setting: ${basis.promptLine}

                Bring the judgement of a senior expert in the subject of the context. For each new chunk, decide whether anything in it deserves a comment of one of these kinds:
                - question: a point that should be asked, pinned down or clarified
                - doubt: a claim that is questionable, unsupported, or inconsistent with what was said earlier
                - info: additional information that would materially help the listener
                - correction: a statement that is wrong, with the correct position

                $memoryLine

                The transcript has no speaker labels and contains recognition errors. Allow for misheard words: a likely transcription error is not a factual error.

                Comment on the new speech, using what came before to spot contradictions and to avoid repeating yourself. Return an empty list when nothing is worth saying: silence beats noise. Give at most 3 comments per chunk, most important first. Keep each comment to one or two sentences, written to be read at a glance. Set quote to the short phrase from the transcript that the comment is about.

                When you refer to legislation, a standard, a code of practice or case law, name the instrument and the section or clause. If you are not certain the provision exists and says what you claim, write "unverified" in the comment. Never invent a citation.

                Use British English spelling.
            """.trimIndent()
        }
    }
}
