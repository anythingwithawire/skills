package com.anythingwithawire.commentator

/**
 * The legal basis the user asserts for listening, chosen afresh for every session
 * and stamped on the session log. References are to the Surveillance Devices Act 1998 (WA).
 */
enum class Basis(val label: String, val promptLine: String) {
    COURT_LEAVE(
        label = "In court, with the court's leave",
        promptLine = "The user is a self-represented party in a court hearing, using you with the court's leave in the way a McKenzie friend helps: quietly. Favour questions they could put, points to note for their own submissions, and errors they could raise with the court. Never suggest interrupting or addressing the court out of turn.",
    ),
    ALL_CONSENT(
        label = "Every principal party has consented (s 5(3)(c), s 9(2)(a)(ii))",
        promptLine = "Everyone in the conversation has consented to you listening.",
    ),
    LAWFUL_INTERESTS(
        label = "Reasonably necessary to protect my lawful interests (s 5(3)(d), s 9(2)(a)(vi))",
        promptLine = "The user is one of the speakers and is relying on you to help protect their own lawful interests in this conversation.",
    ),
}
