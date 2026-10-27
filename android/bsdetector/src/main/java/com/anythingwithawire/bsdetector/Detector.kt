package com.anythingwithawire.bsdetector

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.MessageCreateParams
import com.anythingwithawire.core.Claude
import org.json.JSONException
import org.json.JSONObject

sealed class Verdict {
    data class Score(val score: Int) : Verdict()
    data class Declined(val reason: String) : Verdict()
}

/**
 * Scores each utterance for bullshit, 0 to 100. Stateless: every request carries
 * only the utterance itself, and nothing is kept once the request is built.
 */
class Detector(apiKey: String, private val model: String) {

    private val client = Claude.client(apiKey)

    /** Blocking network call: run off the main thread. */
    fun score(utterance: String): Verdict {
        val reply = client.beta().messages().create(prepare(utterance))
        Claude.declineReason(reply)?.let { return Verdict.Declined(it) }
        val score = JSONObject(Claude.answerText(reply)).getInt("score")
        if (score !in 0..100) throw JSONException("score $score is outside 0 to 100")
        return Verdict.Score(score)
    }

    internal fun prepare(utterance: String): MessageCreateParams =
        Claude.params(model, SYSTEM_PROMPT, listOf(BetaMessageParam.ofUser(utterance)), SCHEMA)

    companion object {
        private val SCHEMA: BetaJsonOutputFormat.Schema = BetaJsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .putAdditionalProperty("required", JsonValue.from(listOf("score")))
            .putAdditionalProperty("properties", JsonValue.from(mapOf("score" to mapOf("type" to "integer"))))
            .build()

        internal val SYSTEM_PROMPT = """
            You are a bullshit detector. You receive one utterance at a time, transcribed by speech-to-text from a conversation the phone can hear, with no speaker labels and no earlier context. Give it a score from 0 to 100 for how much of it is bullshit.

            Bullshit means: claims that are false or fabricated, invented statistics or sources, confident assertions with no basis, evasion dressed up as an answer, weasel words or spin meant to mislead, and jargon used to obscure rather than explain. Judge with the knowledge of a sharp, well-informed expert in whatever the utterance is about. Default locale: Western Australia, Australia.

            Not bullshit (score 0 to 10): ordinary talk, questions, greetings, honest opinions offered as opinions, jokes that are plainly jokes, and anything you cannot judge from the utterance alone. Misheard words from speech-to-text are not bullshit.

            Calibration: 30 is vague puffery; 60 is probably misleading or unsupported; 85 and above is clearly false or fabricated.
        """.trimIndent()
    }
}
