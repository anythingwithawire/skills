package com.anythingwithawire.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONException

/** Claude API plumbing shared by the apps: one request shape, one way of reading replies. */
object Claude {

    fun client(apiKey: String): AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    /** A low-effort request whose reply must match [schema], with server-side refusal fallback on. */
    fun params(
        model: String,
        system: String,
        messages: List<BetaMessageParam>,
        schema: BetaJsonOutputFormat.Schema,
    ): MessageCreateParams = MessageCreateParams.builder()
        .model(model)
        .maxTokens(16000L)
        .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(system).build()))
        .messages(messages)
        .cacheControl(BetaCacheControlEphemeral.builder().build())
        .outputConfig(
            BetaOutputConfig.builder()
                .effort(BetaOutputConfig.Effort.LOW)
                .format(BetaJsonOutputFormat.builder().schema(schema).build())
                .build()
        )
        // Server-side refusal fallback: a declined request is re-run on a fallback model.
        .addBeta("server-side-fallback-2026-07-01")
        .fallbacks(BetaFallbacksParam.ofDefault())
        .build()

    /** Why the reply has no usable answer, or null when it has one. */
    fun declineReason(reply: BetaMessage): String? = when (reply.stopReason().get()) {
        BetaStopReason.REFUSAL -> {
            val category = reply.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse("unspecified")
            "Claude declined to assess this part (category: $category)."
        }
        BetaStopReason.MAX_TOKENS -> "Response hit the token limit and was cut off."
        else -> null
    }

    /**
     * The reply's answer text. Text before a fallback block came from a model that
     * declined part-way, so only the text after the last switch point counts.
     */
    fun answerText(reply: BetaMessage): String {
        val content = reply.content()
        val answer = content.subList(content.indexOfLast { it.isFallback() } + 1, content.size)
        return answer.mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
    }
}

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Failed(val message: String, val fatal: Boolean) : ApiResult<Nothing>()
}

/** Turns API and parsing failures into a message for the screen. A rejected key is fatal. */
inline fun <T> callClaude(block: () -> T): ApiResult<T> = try {
    ApiResult.Ok(block())
} catch (e: UnauthorizedException) {
    ApiResult.Failed("Claude API key was rejected (401). Check it in Settings.", fatal = true)
} catch (e: AnthropicServiceException) {
    ApiResult.Failed("Claude API error ${e.statusCode()}: ${e.message}", fatal = false)
} catch (e: AnthropicException) {
    ApiResult.Failed("Could not reach Claude: ${e.message}", fatal = false)
} catch (e: JSONException) {
    ApiResult.Failed("Claude's reply was not in the expected format: ${e.message}", fatal = false)
}
